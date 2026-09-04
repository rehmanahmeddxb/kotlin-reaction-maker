#!/usr/bin/env bash
#
# Ahmed Reaction Studio - offline native APK builder.
#
# Builds the Kotlin app into a signed, installable APK WITHOUT Gradle / AGP /
# AndroidX, using only:
#   kotlinc            (from npm "kotlin-compiler")
#   Temurin JDK 21     (from PyPI "jdk4py")
#   android-30.jar     (AOSP platform stub, committed on GitHub)
#   aapt2 (Linux)      (PyPI "aapt2")
#   d8.jar             (Android build-tools 28 lib)
#   apksigner.jar      (Android build-tools 26 lib)
#
# Set these env vars (or rely on the documented defaults):
#   TC_ROOT   root dir that holds: kotlinc/ java-runtime/ android.jar ...
#   JAVA_HOME path to a JDK/JRE 17+ runtime
#   KOTLINC   path to kotlinc executable
#   AAPT2     path to aapt2 (linux) binary
#   D8_JAR    path to d8.jar
#   APKSIGNER_JAR path to apksigner.jar
#   ANDROID_JAR   path to android.jar
#
# Output: artifacts/AhmedReactionStudio-1.0.0.apk  (signed, v1+v2)

set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="AhmedReactionStudio"
VER="1.0.0"
PKG="com.rehman.ahmedreactionstudio"

TC_ROOT="${TC_ROOT:-/tmp/ahmed-tc}"
JAVA_HOME="${JAVA_HOME:-$TC_ROOT/java-runtime}"
KOTLINC="${KOTLINC:-$TC_ROOT/kotlinc/bin/kotlinc}"
AAPT2="${AAPT2:-$TC_ROOT/aapt2}"
D8_JAR="${D8_JAR:-$TC_ROOT/d8.jar}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$TC_ROOT/apksigner.jar}"
ANDROID_JAR="${ANDROID_JAR:-$TC_ROOT/android.jar}"
ANDROID_JAR_D8="${ANDROID_JAR_D8:-$TC_ROOT/android-d8.jar}"
STD_LIB="${STD_LIB:-$TC_ROOT/kotlin-stdlib.jar}"

KS="${KS:-$TC_ROOT/ahmed.keystore}"
KS_PASS="${KS_PASS:-ahmed123}"
KS_ALIAS="${KS_ALIAS:-app}"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

BUILD="build_out"
rm -rf "$BUILD"
mkdir -p "$BUILD/dex" "artifacts"

echo "== 1/6 compile Kotlin =="
find app/src -name '*.kt' > "$BUILD/sources.txt"
"$KOTLINC" -jvm-target 1.8 -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 2/6 dex with d8 =="
python3 - "$BUILD" <<'PY'
import sys, zipfile, os
b = sys.argv[1]
if not os.path.isdir(os.path.join(b, 'classes')):
    raise SystemExit('missing classes dir')
with zipfile.ZipFile(os.path.join(b, 'classes.jar'), 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(os.path.join(b, 'classes')):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, os.path.join(b, 'classes')))
PY
# d8 only needs the library jar for API-level resolution; the newer API
# stub is compiled with Java 17 bytecode which old d8 can't parse, so feed
# it the Java-8 API-30 stub while compiling sources against the newer one.
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --lib "$ANDROID_JAR_D8" --min-api 26 \
    --output "$BUILD/dex" "$BUILD/classes.jar" "$STD_LIB"

echo "== 3/6 aapt2 resources =="
"$AAPT2" compile --dir res -o "$BUILD/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" \
    --manifest app/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 30 \
    -o "$BUILD/base.apk" "$BUILD/res.zip"

echo "== 4/6 assemble apk =="
python3 - "$BUILD" "$APP_NAME" "$VER" "$PKG" <<'PY'
import sys, zipfile, os
b, name, ver, pkg = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with zipfile.ZipFile(os.path.join(b, 'base.apk')) as src:
    entries = {i.filename: src.read(i.filename) for i in src.infolist()}
with zipfile.ZipFile(os.path.join(b, 'unsigned.apk'), 'w', zipfile.ZIP_DEFLATED) as z:
    for fn, data in entries.items():
        z.writestr(fn, data)
    z.write(os.path.join(b, 'dex', 'classes.dex'), 'classes.dex')
print('unsigned.apk entries:', list(zipfile.ZipFile(os.path.join(b, 'unsigned.apk')).namelist()))
PY

echo "== 5/6 sign (v1+v2) =="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Ahmed Reaction Studio,O=Ahmed Studio,C=US" >/dev/null 2>&1
fi
java -jar "$APKSIGNER_JAR" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "artifacts/$APP_NAME-$VER.apk" "$BUILD/unsigned.apk"

echo "== 6/6 verify =="
java -jar "$APKSIGNER_JAR" verify --print-certs "artifacts/$APP_NAME-$VER.apk" | head -6
ls -la "artifacts/$APP_NAME-$VER.apk"
echo "BUILD OK"
