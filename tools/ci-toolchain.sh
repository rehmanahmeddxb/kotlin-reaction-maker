#!/usr/bin/env bash
# Installs the offline APK toolchain into /tmp/ahmed-tc on a CI runner.
# Every external tool has more than one source so restricted networks
# (release-asset CDNs blocked) still build. Called with `set -euxo pipefail`.
set -euxo pipefail

TC=/tmp/ahmed-tc
mkdir -p "$TC"

echo "== [1/3] kotlin compiler (npm registry, CDN-independent) =="
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

echo "== [2/3] android platform jars (API-34 compile stub + API-30 d8 stub) =="
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

echo "== [3/3] build-tools (aapt2, d8.jar, apksigner.jar) =="
# d8 + apksigner come from GitHub mirrors (blob data over the git protocol,
# no release-asset CDN); aapt2 tries the google CDN, then pip, then a mirror.
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

if [ ! -x "$TC/aapt2" ]; then
  # first try the google CDN (works on open runners)
  curl -fsSL --retry 2 -o /tmp/build-tools.zip \
    https://dl.google.com/android/repository/build-tools_r30.0.3-linux.zip || true
  if [ -s /tmp/build-tools.zip ] && unzip -tq /tmp/build-tools.zip >/dev/null 2>&1; then
    rm -rf /tmp/sdk/bt
    unzip -q /tmp/build-tools.zip -d /tmp/sdk/bt
    cp "$(find /tmp/sdk/bt -name aapt2 -type f | head -1)" "$TC/aapt2"
    chmod +x "$TC/aapt2"
  fi
fi
if [ ! -x "$TC/aapt2" ]; then
  # pip mirror (PyPI is a regular API host)
  pip3 install --quiet aapt2 || true
  cp "$(find "$HOME/.local/lib" /usr/lib/python3* /usr/local/lib/python3* \
      -path '*aapt2/bin/Linux/aapt2' -type f 2>/dev/null | head -1)" "$TC/aapt2" || true
  chmod +x "$TC/aapt2" || true
fi
test -x "$TC/aapt2"
test -s "$TC/d8.jar"
test -s "$TC/apksigner.jar"
"$TC/aapt2" version
echo "toolchain ready"
