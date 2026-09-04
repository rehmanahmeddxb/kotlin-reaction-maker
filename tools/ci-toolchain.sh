#!/usr/bin/env bash
# Installs the offline APK toolchain into /tmp/ahmed-tc on a CI runner.
# Every external tool has more than one source so restricted networks
# (release-asset CDNs blocked) still build.
set -euxo pipefail

TC=/tmp/ahmed-tc
mkdir -p "$TC"

echo "== [0/4] JDK 17 runtime (kotlinc 1.9 cannot parse Java 25 version strings) =="
# The runner's default JDK may be 21/25; kotlinc 1.9.24 throws
# "IllegalArgumentException: 25.0.2" on those. Ship our own JDK 17 through a
# venv (PEP-668 safe) so the build is independent of the runner image.
if [ ! -x "$TC/java-runtime/bin/java" ]; then
  python3 -m venv /tmp/jdkvenv
  /tmp/jdkvenv/bin/pip install --quiet --upgrade pip
  /tmp/jdkvenv/bin/pip install --quiet "jdk4py==17.0.9.2"
  JH="$(/tmp/jdkvenv/bin/python -c 'import jdk4py,sys; sys.stdout.write(str(jdk4py.JAVA_HOME))')"
  rm -rf "$TC/java-runtime"
  cp -R "$JH" "$TC/java-runtime"
  chmod -R +x "$TC/java-runtime/bin"
fi
export JAVA_HOME="$TC/java-runtime"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "== [1/4] kotlin compiler (npm registry, CDN-independent) =="
if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  KC=/tmp/kcnode
  rm -rf "$KC"; mkdir -p "$KC"; cd "$KC"
  npm init -y >/dev/null 2>&1
  # npm registry is a regular API host, not a release-asset CDN -> works
  # through proxies that block github release downloads.
  npm install --no-audit --no-fund kotlin-compiler@1.9.24
  cd - >/dev/null
  rm -rf "$TC/kotlinc"
  cp -R "$KC/node_modules/kotlin-compiler" "$TC/kotlinc"
  chmod +x "$TC/kotlinc/bin/kotlinc"
  ln -sf "$TC/kotlinc/lib/kotlin-stdlib.jar" "$TC/kotlin-stdlib.jar"
fi
test -x "$TC/kotlinc/bin/kotlinc"
"$TC/kotlinc/bin/kotlinc" -version

echo "== [2/4] android platform jars (API-34 compile stub + API-30 d8 stub) =="
# kotlinc compiles against a recent stub (MediaRecorder(Context),
# getParcelableExtra(name, Class), FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION…);
# d8 is fed the Java-8 API-30 stub because this d8 build cannot parse
# Java-17-class newer platform jars. GitHub mirrors only (no dl.google.com).
if [ ! -s "$TC/android.jar" ] || [ ! -s "$TC/android-d8.jar" ]; then
  if [ ! -d "$TC/aj/.git" ]; then
    rm -rf "$TC/aj"
    git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/Reginer/aosp-android-jar.git "$TC/aj"
  fi
  (cd "$TC/aj" && git sparse-checkout set android-34 android-30)
  cp "$TC/aj/android-34/android.jar" "$TC/android.jar"
  cp "$TC/aj/android-30/android.jar" "$TC/android-d8.jar"
fi
test -s "$TC/android.jar" && test -s "$TC/android-d8.jar"

echo "== [3/4] d8 + apksigner (git mirrors, no release CDN) =="
if [ ! -s "$TC/d8.jar" ]; then
  rm -rf "$TC/lr8"
  git clone --depth 1 --filter=blob:none --sparse -b lineage-17.1 \
    https://github.com/LineageOS/android_prebuilts_r8.git "$TC/lr8"
  (cd "$TC/lr8" && git sparse-checkout set buildtools)
  cp "$TC/lr8/buildtools/d8-master.jar" "$TC/d8.jar"
fi

if [ ! -s "$TC/apksigner.jar" ]; then
  rm -rf "$TC/paks"
  git clone --depth 1 --filter=blob:none --sparse -b apksigner \
    https://github.com/warren-bank/print-apk-signature.git "$TC/paks"
  (cd "$TC/paks" && git sparse-checkout set libs/apksigner)
  cp "$TC/paks/libs/apksigner/apksigner.jar" "$TC/apksigner.jar"
fi

echo "== [4/4] aapt2 =="
# source 1: PyPI aapt2 wheel ships the prebuilt Linux binary (works on
# networks that block dl.google.com); venv keeps PEP-668 runners happy.
if ! "$TC/aapt2" version >/dev/null 2>&1; then
  rm -f "$TC/aapt2"
  python3 -m venv /tmp/aaptvenv
  /tmp/aaptvenv/bin/pip install --quiet --upgrade pip
  /tmp/aaptvenv/bin/pip install --quiet aapt2==0.2.1
  PIP_AAPT="$(find /tmp/aaptvenv -path '*aapt2/bin/Linux/aapt2' -type f 2>/dev/null | head -1)"
  [ -n "$PIP_AAPT" ] && cp "$PIP_AAPT" "$TC/aapt2"
  [ -f "$TC/aapt2" ] && chmod +x "$TC/aapt2"
fi
# source 2: google CDN build-tools (only reachable on open runners)
if ! "$TC/aapt2" version >/dev/null 2>&1; then
  rm -f "$TC/aapt2"
  curl -fsSL --retry 2 -o /tmp/build-tools.zip \
    https://dl.google.com/android/repository/build-tools_r30.0.3-linux.zip || true
  if [ -s /tmp/build-tools.zip ] && unzip -tq /tmp/build-tools.zip >/dev/null 2>&1; then
    rm -rf /tmp/sdk/bt
    unzip -q /tmp/build-tools.zip -d /tmp/sdk/bt
    AAPT_CANDIDATE="$(find /tmp/sdk/bt -name aapt2 -type f | head -1)"
    [ -n "$AAPT_CANDIDATE" ] && cp "$AAPT_CANDIDATE" "$TC/aapt2"
    [ -f "$TC/aapt2" ] && chmod +x "$TC/aapt2"
  fi
fi
# guard: an aapt2 that can't even print its version is useless on this host
"$TC/aapt2" version >/dev/null 2>&1 || { echo "aapt2 binary missing or non-functional"; exit 1; }
test -s "$TC/d8.jar"
test -s "$TC/apksigner.jar"
"$TC/aapt2" version
echo "toolchain ready"
