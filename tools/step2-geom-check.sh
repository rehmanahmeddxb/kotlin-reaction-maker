#!/usr/bin/env bash
# STEP 2 — JVM geometry check for the selection-border math.
#
# Compiles ONLY the pure-geometry pieces (core/Model.kt's LayerFit + the
# check) against the AOSP stub jar and runs them on a plain JVM — no
# emulator. It guards the ONE formula shared by the renderer and the editor
# chrome: LayerFit.drawnFrame (via the mirrored Compositor.chromeRect and
# StageView.resizeTo math). If the border math and the compositor math ever
# drift apart, this fails.
set -euo pipefail
cd "$(dirname "$0")/.."
TC="${TC_ROOT:-/tmp/ahmed-tc}"
export JAVA_HOME="${JAVA_HOME:-$TC/java-runtime}"
export PATH="$JAVA_HOME/bin:$PATH"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$TC/kotlinc/bin/kotlinc" -nowarn -jvm-target 1.8 \
    -classpath "$TC/android.jar" \
    -d "$OUT" \
    app/src/com/rehman/ahmedreactionstudio/core/Model.kt \
    tools/geomcheck/Step2ChromeGeomCheck.kt

java -cp "$OUT:$TC/android.jar:$TC/kotlin-stdlib.jar" \
    com.rehman.ahmedreactionstudio.geomcheck.Step2ChromeGeomCheckKt
