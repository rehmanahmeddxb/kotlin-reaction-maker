#!/usr/bin/env bash
# On-device smoke test for the Studio chrome. Runs in the GitHub Actions
# emulator job (.github/workflows/android.yml → emulator-smoke); the offline
# sandbox has no KVM, so it cannot run there.
#
# The APK is a release build (not debuggable), so nothing is seeded through
# run-as: every tier goes the user's way — launcher → Home → "+ New project"
# → aspect chip → Create → EditorActivity. The editor pins its orientation to
# the canvas (16:9 → landscape, 9:16 → portrait), so the four tiers are
#   phone  × 9:16 (portrait)  · phone  × 16:9 (landscape)
#   tablet × 9:16 (portrait)  · tablet × 16:9 (landscape)
# on one emulator, re-sized with `wm size` / `wm density`.
#
# Per tier it dumps the live view hierarchy (uiautomator) + a screenshot and
# asserts, through check_window.py, what only a real window can show:
#   · every clickable node fully on screen and ≥ 44dp (40dp strip items excepted)
#   · no two clickable nodes overlapping
#   · the canvas present, ≥ 96dp, and not covered by chrome
#   · the controls the tier promises (Back · aspect · Save · Export · ⋯ ·
#     Play · Stop · REC · a tab strip or the Sources header)
# then walks the part of the regression list that needs no file picker:
# add text (rail tool or Add ring) · select / hide / show it in Sources ·
# Mixer / Props / Effects tabs · collapse + re-open the panel · live camera
# (the emulator's virtual camera; adds the camera row) · undo / redo · play /
# stop · aspect switch (16:9 ↔ 9:16 = the onConfigurationChanged re-layout)
# — after each step the process must be alive and the invariants must hold.
#
# Output: out/emulator-smoke/<tier>/{window*.xml,shot*.png,report.txt} and
# out/emulator-smoke/SUMMARY.md (also appended to the job summary, which is
# readable through the API even when artifact downloads are blocked).
set -uo pipefail
cd "$(dirname "$0")/../.."

APK="${APK:-artifacts/AhmedReactionStudio-1.0.0.apk}"
PKG=com.rehman.ahmedreactionstudio
OUT="${OUT:-out/emulator-smoke}"
SERIAL="${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}"
CHECK=tools/emulator-smoke/check_window.py
adb() { command adb -s "$SERIAL" "$@"; }
say() { echo "[smoke] $*"; }

rm -rf "$OUT"; mkdir -p "$OUT"
FAILS=0

# tier  width  height  density  aspect   (px in the display's natural orientation)
#   1080x2400 @480 → 360x800dp phone · 1600x2560 @320 → 800x1280dp tablet
TIERS=(
  "phone-portrait    1080 2400 480 9:16"
  "phone-landscape   1080 2400 480 16:9"
  "tablet-portrait   1600 2560 320 9:16"
  "tablet-landscape  1600 2560 320 16:9"
)

alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }

dump() {  # $1 file — uiautomator refuses while the UI is busy: retry, never reuse a stale file
  local i
  for i in 1 2 3 4 5 6; do
    adb shell rm -f /sdcard/window.xml >/dev/null 2>&1
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1
    if adb pull /sdcard/window.xml "$1" >/dev/null 2>&1 && head -c 5 "$1" | grep -q '<?xml'; then
      return 0
    fi
    sleep 1.5
  done
  say "uiautomator dump failed"; return 1
}

shot() { adb exec-out screencap -p > "$1" 2>/dev/null || true; }

# tap the node whose content-desc / text equals (or starts with) $1, using dump $2
tap() {  # $1 label, $2 dumpfile, [$3 settle seconds]
  local b
  b=$(python3 "$CHECK" --bounds-of "$1" "$2") || return 1
  adb shell input tap $b
  sleep "${3:-1.2}"
}

close_wheel() {  # a radial menu left open would scrim every later dump (BACK pops one level)
  local i
  for i in 1 2 3; do
    dump /tmp/w.xml || return 0
    python3 "$CHECK" --wheel-open /tmp/w.xml || return 0
    adb shell input keyevent 4; sleep 1
  done
}

check() {  # $1 dumpfile, $2 tier, $3 step, extra args…
  local f="$1" tier="$2" step="$3"; shift 3
  if python3 "$CHECK" --tier "$tier" --step "$step" "$@" "$f" >> "$OUT/$tier/report.txt"; then
    say "$tier · $step: ok"
  else
    say "$tier · $step: FAIL"; FAILS=$((FAILS+1))
  fi
}

require_alive() {  # $1 tier, $2 step
  alive && return 0
  say "$1 · $2: PROCESS DIED"; FAILS=$((FAILS+1))
  { echo "PROCESS DIED at: $2"; adb logcat -d -b crash | tail -n 80; } >> "$OUT/$1/report.txt" 2>/dev/null || true
  return 1
}

# ---- install ----------------------------------------------------------------
adb install -r -g "$APK" >/dev/null
for perm in CAMERA RECORD_AUDIO; do adb shell pm grant "$PKG" "android.permission.$perm" >/dev/null 2>&1 || true; done
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb logcat -c || true

for spec in "${TIERS[@]}"; do
  read -r tier w h dens aspect <<<"$spec"
  mkdir -p "$OUT/$tier"; : > "$OUT/$tier/report.txt"
  d="$OUT/$tier/window.xml"
  landscape=portrait; [[ $aspect == 16:9 ]] && landscape=landscape

  adb shell am force-stop "$PKG"
  adb shell wm size "${w}x${h}"; adb shell wm density "$dens"; sleep 2

  # launcher → splash (2.4 s) → Home
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  require_alive "$tier" "launch" || continue
  dump "$d" || { FAILS=$((FAILS+1)); continue; }
  # Home → new project of the tier's aspect
  if ! tap "Create a new project" "$d"; then
    say "$tier: Home has no '+ New project' button"; FAILS=$((FAILS+1)); shot "$OUT/$tier/shot-home.png"; continue
  fi
  dump "$d"; tap "Canvas aspect ratio $aspect" "$d" 0.5; dump "$d"; tap "Create" "$d" 4
  require_alive "$tier" "open editor" || continue
  dump "$d"; shot "$OUT/$tier/shot-empty.png"
  check "$d" "$tier" "editor opened (empty project)" --density "$dens" --expect-bars --expect-orientation "$landscape"

  # ---- add a text source: rail tool (tablets) / Sources shortcut, else Add ring → "Text overlay"
  if tap "Text" "$d" 0.8 || { tap "Add source" "$d" && dump "$d" && tap "Text overlay" "$d" 0.8; }; then
    dump "$d"; tap "Add" "$d" 1.5 || adb shell input keyevent 66
  fi
  close_wheel
  require_alive "$tier" "add text" || continue
  dump "$d"; shot "$OUT/$tier/shot-text.png"
  check "$d" "$tier" "text source added" --density "$dens" --expect-bars --expect-orientation "$landscape"

  # ---- the Sources list on screen: open a collapsed panel, pick the tab (no tab when pinned)
  for a in "Open panel" "Expand panel"; do tap "$a" "$d" && { dump "$d"; break; }; done
  tap "Sources tab" "$d" && dump "$d"
  check "$d" "$tier" "Sources list shows the new source" --density "$dens" --expect "Select Text" --expect "Hide Text"

  # ---- Sources: select · hide · show (the snack stays 3.5 s: settle 4 s so it is gone)
  tap "Select Text" "$d" && { dump "$d"; check "$d" "$tier" "source selected" --density "$dens"; }
  tap "Hide Text" "$d" 4 && { dump "$d"; check "$d" "$tier" "source hidden" --density "$dens" --expect "Show Text"; }
  tap "Show Text" "$d" 4 && { dump "$d"; check "$d" "$tier" "source shown" --density "$dens" --expect "Hide Text"; }

  # ---- tabs · collapse · re-open
  for t in Mixer Props Effects; do
    tap "$t tab" "$d" && { dump "$d"; check "$d" "$tier" "$t tab" --density "$dens"; }
  done
  tap "Collapse panel" "$d" && { dump "$d"; shot "$OUT/$tier/shot-collapsed.png"; check "$d" "$tier" "panel collapsed (canvas reclaims)" --density "$dens" --expect-bars; }
  for a in "Open panel" "Expand panel" "Show or hide panel"; do tap "$a" "$d" && break; done
  dump "$d"; check "$d" "$tier" "panel re-opened" --density "$dens" --expect-bars --expect-orientation "$landscape"

  # ---- undo · redo · play · stop
  for a in Undo Redo Play Stop; do tap "$a" "$d" 1 || true; require_alive "$tier" "$a" || break; done
  dump "$d"; check "$d" "$tier" "after undo / redo / play / stop" --density "$dens" --expect-bars

  # ---- aspect switch = the onConfigurationChanged re-layout (16:9 ↔ 9:16), source kept
  if tap "Canvas aspect ratio" "$d"; then
    dump "$d"
    if [[ $aspect == 16:9 ]]; then other="9:16  —  Reels · Shorts · TikTok"; flipped=portrait
    else other="16:9  —  YouTube · landscape"; flipped=landscape; fi
    tap "$other" "$d" 4 || adb shell input keyevent 4
    require_alive "$tier" "aspect switch" || continue
    dump "$d"; shot "$OUT/$tier/shot-aspect.png"
    check "$d" "$tier" "aspect switched → $flipped (re-layout, no restart)" --density "$dens" --expect-bars --expect-orientation "$flipped"
    for a in "Open panel" "Expand panel"; do tap "$a" "$d" && { dump "$d"; break; }; done
    tap "Sources tab" "$d" && dump "$d"
    check "$d" "$tier" "source survived the re-layout" --density "$dens" --expect "Select Text"
  fi

  # ---- live camera (the emulator's virtual camera) last: camera row + LIVE status;
  #      frames stream from here on, so this is the busiest window the dump sees
  if tap "Live camera" "$d" 5 || { tap "Add source" "$d" && dump "$d" && tap "Camera (live on canvas)" "$d" 5; }; then
    close_wheel
    require_alive "$tier" "live camera" || continue
    dump "$d"; shot "$OUT/$tier/shot-camera.png"
    check "$d" "$tier" "live camera on canvas (camera row)" --density "$dens" --expect-bars --expect "Select Camera"
  fi

  # ---- Back to Home (flushes the save); the project must reopen
  adb shell input keyevent 4; sleep 2
  adb shell am force-stop "$PKG"
done

adb shell wm size reset >/dev/null 2>&1; adb shell wm density reset >/dev/null 2>&1

{
  echo "# Emulator smoke — Studio chrome (API 30 x86_64)"
  echo
  for spec in "${TIERS[@]}"; do
    read -r tier _ <<<"$spec"
    echo "## $tier"; echo '```'; cat "$OUT/$tier/report.txt"; echo '```'
  done
  echo; echo "**failures: $FAILS**"
} | tee "$OUT/SUMMARY.md"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$OUT/SUMMARY.md" >> "$GITHUB_STEP_SUMMARY"
[ "$FAILS" -eq 0 ]
