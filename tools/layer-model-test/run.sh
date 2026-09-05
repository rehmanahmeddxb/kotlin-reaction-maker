#!/usr/bin/env bash
# JVM test for the layer rules (placement stagger, topmost-first hit test,
# z-order verbs). Model.kt imports org.json, so android.jar is on the COMPILE
# classpath only; nothing under test calls into it at runtime.
set -euo pipefail
cd "$(dirname "$0")/../.."
TC_ROOT="${TC_ROOT:-/tmp/ahmed-tc}"
export JAVA_HOME="${JAVA_HOME:-$TC_ROOT/java-runtime}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT=build_out/layer-model-test
rm -rf "$OUT"; mkdir -p "$OUT/classes"
"$TC_ROOT/kotlinc/bin/kotlinc" -nowarn -jvm-target 1.8 \
  -cp "$TC_ROOT/android.jar" \
  app/src/com/rehman/ahmedreactionstudio/core/Model.kt \
  app/src/com/rehman/ahmedreactionstudio/core/Sources.kt \
  tools/layer-model-test/LayerModelTest.kt \
  -d "$OUT/classes"
java -cp "$OUT/classes:$TC_ROOT/kotlinc/lib/kotlin-stdlib.jar" \
  com.rehman.ahmedreactionstudio.core.LayerModelTest
