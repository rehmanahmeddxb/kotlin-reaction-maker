#!/usr/bin/env bash
# Compiles the device-independent canvas fit rule (ViewportFit.kt) together
# with the JVM test and runs it with the toolchain the APK build uses.
set -euo pipefail
cd "$(dirname "$0")/../.."
TC_ROOT="${TC_ROOT:-/tmp/ahmed-tc}"
export JAVA_HOME="${JAVA_HOME:-$TC_ROOT/java-runtime}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT=build_out/viewport-fit-test
rm -rf "$OUT"; mkdir -p "$OUT"
"$TC_ROOT/kotlinc/bin/kotlinc" -nowarn -jvm-target 1.8 \
  app/src/com/rehman/ahmedreactionstudio/core/ViewportFit.kt \
  tools/viewport-fit-test/ViewportFitTest.kt \
  -d "$OUT/classes" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/classes:$TC_ROOT/kotlinc/lib/kotlin-stdlib.jar" \
  com.rehman.ahmedreactionstudio.core.ViewportFitTest
