#!/usr/bin/env bash
# Compiles the device-independent chrome budget (ChromeBudget.kt: how the
# usable window is divided between tool rail, canvas cell and context panel)
# together with its JVM test and runs it with the toolchain the APK build uses.
set -euo pipefail
cd "$(dirname "$0")/../.."
TC_ROOT="${TC_ROOT:-/tmp/ahmed-tc}"
export JAVA_HOME="${JAVA_HOME:-$TC_ROOT/java-runtime}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT=build_out/chrome-budget-test
rm -rf "$OUT"; mkdir -p "$OUT"
"$TC_ROOT/kotlinc/bin/kotlinc" -nowarn -jvm-target 1.8 \
  app/src/com/rehman/ahmedreactionstudio/core/ChromeBudget.kt \
  tools/chrome-budget-test/ChromeBudgetTest.kt \
  -d "$OUT/classes"
java -cp "$OUT/classes:$TC_ROOT/kotlinc/lib/kotlin-stdlib.jar" \
  com.rehman.ahmedreactionstudio.core.ChromeBudgetTest
