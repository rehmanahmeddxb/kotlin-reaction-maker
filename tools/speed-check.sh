#!/usr/bin/env bash
# Timed end-to-end build benchmark: reports how long each stage of
# build-apk.sh takes plus APK size / dex + resource stats.
set -euo pipefail
cd "$(dirname "$0")/.."
TC_ROOT="${TC_ROOT:-/tmp/ahmed-tc}"
export TC_ROOT
export JAVA_HOME="${JAVA_HOME:-$TC_ROOT/java-runtime}"
export PATH="$JAVA_HOME/bin:$PATH"

LOG=$(mktemp)
START=$(date +%s.%N)
./build-apk.sh > "$LOG" 2>&1 || { tail -40 "$LOG"; exit 1; }
END=$(date +%s.%N)

APK="artifacts/AhmedReactionStudio-1.0.0.apk"
python3 - "$APK" "$START" "$END" <<'PY'
import sys, os, zipfile
apk, start, end = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
z = zipfile.ZipFile(apk)
dex = z.getinfo('classes.dex')
res = [i for i in z.infolist() if i.filename.startswith('res/')]
print("=== Ahmed Reaction Studio speed check ===")
print(f"total build time      : {end-start:6.1f} s")
print(f"apk size              : {os.path.getsize(apk)/1024:8.1f} KiB")
print(f"classes.dex           : {dex.file_size/1024:8.1f} KiB uncompressed")
print(f"resource entries      : {len(res)}")
print(f"total entries         : {len(z.infolist())}")
PY
grep -c "^" app/src/com/rehman/ahmedreactionstudio/*/*.kt | awk -F: '{s+=$2} END {print "kotlin source lines   :", s}'
