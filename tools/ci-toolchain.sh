#!/usr/bin/env bash
# Installs the offline APK toolchain into /tmp/ahmed-tc on a GitHub runner.
# Called from .github/workflows/android.yml with `set -euxo pipefail` enabled
# by the caller so failures stop immediately and the caller sees the log.
set -euxo pipefail

TC=/tmp/ahmed-tc
mkdir -p "$TC"

echo "== [1/3] kotlin compiler =="
curl -fsSL -o /tmp/kotlinc.zip \
  https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip
ls -la /tmp/kotlinc.zip
unzip -q /tmp/kotlinc.zip -d "$TC"
# the archive extracts to a folder named kotlin-compiler-1.9.24
KDIR=$(find "$TC" -maxdepth 1 -type d -name 'kotlin-compiler-*' | head -1)
echo "kotlin dir: $KDIR"
mv "$KDIR" "$TC/kotlinc"
ln -sf "$TC/kotlinc/lib/kotlin-stdlib.jar" "$TC/kotlin-stdlib.jar"
test -x "$TC/kotlinc/bin/kotlinc"
"$TC/kotlinc/bin/kotlinc" -version

echo "== [2/3] android platform 30 jar =="
curl -fsSL -o /tmp/platform30.zip \
  https://dl.google.com/android/repository/platform-30_r03.zip
ls -la /tmp/platform30.zip
unzip -q /tmp/platform30.zip -d /tmp/sdk
cp /tmp/sdk/android-30/android.jar "$TC/android.jar"
test -f "$TC/android.jar"

echo "== [3/3] build-tools 30.0.3 =="
curl -fsSL -o /tmp/build-tools.zip \
  https://dl.google.com/android/repository/build-tools_r30.0.3-linux.zip
ls -la /tmp/build-tools.zip
unzip -q /tmp/build-tools.zip -d /tmp/sdk/bt
echo "-- build-tools layout --"
find /tmp/sdk/bt -maxdepth 2 -type f | head -40
AAPT2=$(find /tmp/sdk/bt -name aapt2 -type f | head -1)
D8=$(find /tmp/sdk/bt -name d8.jar | head -1)
AS=$(find /tmp/sdk/bt -name apksigner.jar | head -1)
test -n "$AAPT2$D8$AS" || { echo "missing tool"; exit 1; }
cp "$AAPT2" "$TC/aapt2"
chmod +x "$TC/aapt2"
cp "$D8" "$TC/d8.jar"
cp "$AS" "$TC/apksigner.jar"
test -x "$TC/aapt2" && test -f "$TC/d8.jar" && test -f "$TC/apksigner.jar"
"$TC/aapt2" version
echo "toolchain ready"
