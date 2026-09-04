#!/usr/bin/env bash
# Installs the offline APK toolchain into /tmp/ahmed-tc on a GitHub runner.
# Robust to the archive being nested (kotlin-compiler-1.9.24/...) or flat
# (bin/, lib/ at the zip root). Called with `set -euxo pipefail`.
set -euxo pipefail

TC=/tmp/ahmed-tc
mkdir -p "$TC"

echo "== [1/3] kotlin compiler =="
if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  curl -fsSL --retry 3 -o /tmp/kotlinc.zip \
    https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip
  mkdir -p "$TC/kotlin-x"
  unzip -q /tmp/kotlinc.zip -d "$TC/kotlin-x"
  KROOT=$(dirname "$(find "$TC/kotlin-x" -type f -path '*/bin/kotlinc' | head -1)")
  echo "kotlin root: $KROOT"
  mkdir -p "$TC/kotlinc"
  cp -R "$KROOT"/. "$TC/kotlinc/"
  ln -sf "$TC/kotlinc/lib/kotlin-stdlib.jar" "$TC/kotlin-stdlib.jar"
fi
test -x "$TC/kotlinc/bin/kotlinc"
"$TC/kotlinc/bin/kotlinc" -version

echo "== [2/3] android platform 30 jar =="
if [ ! -s "$TC/android.jar" ]; then
  curl -fsSL --retry 3 -o /tmp/platform30.zip \
    https://dl.google.com/android/repository/platform-30_r03.zip || true
  if [ -s /tmp/platform30.zip ] && unzip -tq /tmp/platform30.zip >/dev/null 2>&1; then
    unzip -q /tmp/platform30.zip -d /tmp/sdk
    cp /tmp/sdk/android-30/android.jar "$TC/android.jar"
  else
    echo "dl.google.com unavailable - cloning API-30 stub from GitHub mirror"
    git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/Reginer/aosp-android-jar.git "$TC/aj"
    (cd "$TC/aj" && git sparse-checkout set android-30)
    cp "$TC/aj/android-30/android.jar" "$TC/android.jar"
  fi
fi
test -s "$TC/android.jar"

echo "== [3/3] build-tools (aapt2, d8.jar, apksigner.jar) =="
AAPT2_OK=0; D8_OK=0; AS_OK=0
[ -x "$TC/aapt2" ] && AAPT2_OK=1
[ -s "$TC/d8.jar" ] && D8_OK=1
[ -s "$TC/apksigner.jar" ] && AS_OK=1
if [ "$AAPT2_OK$D8_OK$AS_OK" != "111" ]; then
  curl -fsSL --retry 3 -o /tmp/build-tools.zip \
    https://dl.google.com/android/repository/build-tools_r30.0.3-linux.zip || true
  if [ -s /tmp/build-tools.zip ] && unzip -tq /tmp/build-tools.zip >/dev/null 2>&1; then
    unzip -q /tmp/build-tools.zip -d /tmp/sdk/bt
    [ "$AAPT2_OK" = "0" ] && cp "$(find /tmp/sdk/bt -name aapt2 -type f | head -1)" "$TC/aapt2" && chmod +x "$TC/aapt2"
    [ "$D8_OK" = "0" ] && cp "$(find /tmp/sdk/bt -name d8.jar | head -1)" "$TC/d8.jar"
    [ "$AS_OK" = "0" ] && cp "$(find /tmp/sdk/bt -name apksigner.jar | head -1)" "$TC/apksigner.jar"
  fi
fi
# Fallbacks from committed-on-GitHub mirrors (no release-asset CDN involved)
if [ ! -x "$TC/aapt2" ]; then
  pip3 install --quiet --user aapt2
  cp "$HOME/.local/lib/python"*/site-packages/aapt2/bin/Linux/aapt2 "$TC/aapt2" || true
  chmod +x "$TC/aapt2" || true
fi
if [ ! -s "$TC/d8.jar" ]; then
  git clone --depth 1 --filter=blob:none --sparse -b lineage-17.1 \
    https://github.com/LineageOS/android_prebuilts_r8.git "$TC/lr8"
  (cd "$TC/lr8" && git sparse-checkout set buildtools)
  cp "$TC/lr8/buildtools/d8-master.jar" "$TC/d8.jar"
fi
if [ ! -s "$TC/apksigner.jar" ]; then
  git clone --depth 1 --filter=blob:none --sparse -b apksigner \
    https://github.com/warren-bank/print-apk-signature.git "$TC/paks"
  (cd "$TC/paks" && git sparse-checkout set libs/apksigner)
  cp "$TC/paks/libs/apksigner/apksigner.jar" "$TC/apksigner.jar"
fi
test -x "$TC/aapt2" && test -s "$TC/d8.jar" && test -s "$TC/apksigner.jar"
"$TC/aapt2" version
echo "toolchain ready"
