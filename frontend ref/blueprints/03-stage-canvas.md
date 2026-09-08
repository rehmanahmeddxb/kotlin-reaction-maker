# Blueprint 03 — Canvas Stage, Layers, Chrome & Transport

`StageView.kt` + `FloatingControls.kt`. Stage root: `BoxWithConstraints(fillMaxSize, bg #07080B,
center)` — **tap backdrop = deselect**. Tag `stage_canvas` on canvas.

## 03-A · Canvas fitting
- Fitted rect preserving project ratio (16:9=1.778 / 9:16=0.5625 / 1:1=1.0); normal insets: w−8dp,
  h−48dp; **immersive** (FullCanvas + no chrome) = max area, no insets.
- Canvas frame: 16dp shadow radius 6 · 1dp white-15% border · clip radius 6 · bg = canvas color.

## 03-B · Empty state
```
┌─ canvas ──────────────┐
│   ┌─────┐             │
│   │ 🎬  │  frosted tile: white-6% bg, white-12% ring, radius 16,
│   └─────┘  cyan-90% 28dp icon, pad 14
│  Your canvas is ready      15sp Bold white
│  Add a camera, video…      12sp #94A3B8
└───────────────────────┘
```

## 03-C · Layer visuals (painted bottom→top, only `isVisible`)
- **VIDEO**: real `MediaPlayer`+`TextureView` (loop, volume/mute/speed applied; error tile
  `#1E293B` + `Unable to open media file` 11sp muted) · bottom-left badge (black-60% radius 4,
  pad h6/v2): ▶ 12dp cyan if `transport&&layer playing` else ⏸ `#94A3B8` + name 10sp Medium white.
  No media → demo: bg `#131A2A`/`#0D1424` + 9 neon grid lines + animated cyan waveform dots
  (4s loop, only while source active) + same badge.
- **CAMERA**: `PreviewView` FILL_CENTER/PERFORMANCE, 10dp clip, **2dp facing border** (front amber
  `#F59E0B` / back red `#EF4444` / none amber); facing pill top-start (black-70% radius 12, pad h6/v2):
  6dp dot (green bound / facing color) + `FRONT`/`BACK`/`CAM` 9sp Bold facing-color; torch dot top-end
  10dp yellow when on; fallback: `#262D3D` glow + `#384358` head/shoulders silhouette + 5 green audio
  bars bottom-right (animated while active & unmuted).
- **IMAGE**: `AsyncImage` Fit, or placeholder `Surface` radius 8 green-15% bg + 1.5dp green ring:
  ★ 24dp green + `REACTION` 9sp Bold green, centered.
- **SCREEN**: bg `#1A1B2F` + 1dp purple radius-4 border; centered 🖥 28dp purple + `Display Screen
  Stream` 9sp Bold purple.
- **TEXT**: centered text, pad 4: color/size/weight from TextData + optional black shadow (offset
  3,3 blur 6). Double-tap → D6 editor.
- Opacity via `graphicsLayer alpha`; rotation `rotationZ`; camera layers clipped radius 10.

## 03-D · Snap guides
While a layer is selected & unlocked: cyan-60% dashed (8,8) hairlines at canvas center-X / center-Y
when layer center within 8px.

## 03-E · Selection chrome (topmost; same rect as layer, rotated with it)
- Border 2dp radius 2: cyan, **amber if locked**. Label pill top-start (radius 0/6/0/6, cyan or amber
  bg): `🔒? TYPE • name[:14]` 9sp Bold black, pad h5/v2.
- Unlocked: center **move** 44dp (black-75% circle, cyan ring, ✥ 16dp cyan) · **4 corner dots** 15dp
  cyan + 2dp black ring on 44dp targets, offset ±8dp (each anchors opposite corner; min size 8%,
  no flip, clamped to canvas) · **4 edge pills** white + black ring on 36dp targets, offset 6dp
  (L/R: 6×18 stretch-W; T/B: 18×6 stretch-H) · **rotation knob** above top-center (−40dp, 44dp target):
  `n°` badge 9sp Bold amber (black-85% radius 4, amber ring) + 16dp amber dot + black ring
  (drag-X ×0.9°).
- Locked: center 44dp black-75% circle amber ring + 🔒 18dp amber (no handles).
- **Quick pill** (always, even locked): `Surface` `#F0111520` radius 14, cyan-50% ring, elev 4:
  👁 28dp (white/muted) + ▶/⏸ 28dp cyan **only for VIDEO/CAMERA/SCREEN**; floats above top-right
  (−4,−38) when ≥44dp room else docks inside (+6,+6). Tags `canvas_visibility_button`,
  `canvas_play_pause_button`.

## 03-F · Quick Action Bar (S5) — on selection, top-center
`Surface #F0111520` radius 20, cyan-60% ring, elev 8, pad top 10. Tag `canvas_quick_action_bar`.
Row pad h8/v4: cyan **Auto Fill** (radius 14, h28, ✥-fill 13dp black + 11sp Black `Auto Fill`;
tag `auto_fill_button`) · `Fit` · `Center` (outlined white-20%, radius 14, h28, cyan 13dp icons,
10sp white; tags `fit_frame_button`, `center_button`) · ↔ · ↕ (28dp white 14dp) · `0°` reset
(amber `RotateLeft`) · 🗑 (red `Delete`).

## 03-G · Workspace pill (S6) — top-right, tag `stage_full_canvas_toggle`
- Normal / FC+chrome: radius 18, elev 6, pad h10/v4: ⛶ 15dp + Bold 11sp `Full Canvas`/`Exit Full`
  (FC+chrome = cyan bg + black content; else black-75% + white) + divider + **eye** 28dp
  (`VisibilityOff`, tag `studio_chrome_eye_button`): hides STUDIO chrome only (never sources).
- Immersive: eye-restore (`Visibility` cyan on cyan-18% 30dp circle, same tag) + exit ⛶ cyan 30dp.

## 03-H · REC pill (S7) + Timeline (S8) + Transport (S9) + HUD (S10)
- **REC pill** (immersive + recording, top-end below pill, tag `workspace_rec_indicator`): `#E60E1016`
  radius 14, red-70% ring, pad h9/v3: 8dp red dot + `REC mm:ss` 10sp Bold red.
- **Timeline** (chrome only, bottom-center, w85%, h44, tag `timeline_bar`): `#D90E1016` radius 20,
  white-10% ring, pad-h14: ▶/⏸ 20dp (cyan if playing) + `mm:ss / mm:ss` 11sp Medium white + cyan
  slider weight-1 (**disabled + `00:00 / 00:00` when duration=0**) + 10dp red dot if recording.
- **Transport** (`FloatingControls`, BottomEnd end16/bottom64, fade 150ms): column gap 10, align end.
  REC pill (recording): `#0E1016`@90% radius 14, 1.5dp red ring, pad h10/v4, end-pad 4: 8dp red dot +
  `REC mm:ss` 11sp Bold red. Main pill: radius 28, `#0E1016`@85%, white-15% ring, elev 8, pad 6, gap 8:
  ▶/⏸ 48dp (cyan-25% bg, 24dp cyan icon; tags `floating_play_pause_button`) · ⏹ 48dp (white-10% bg,
  22dp `#F1F5F9`; `floating_stop_button`) · ⏺ 54dp (idle: red-20% bg + 1.5dp red ring + 26dp red ●;
  recording: solid red + 2dp white ring + 26dp white ⏹, pulse 1→1.15 600ms; `floating_record_button`).
- **HUD** (tag-less, top-start pad 12): black-85% radius 8, cyan-40% ring, pad 10:
  `STUDIO ENGINE HUD` 10sp Bold cyan · `FPS: 60 · Res: 1920x1080` 10sp white ·
  `FrameTime: 16.4ms · Decoder: HW MediaCodec + OES` 10sp `#94A3B8` ·
  `Layers: n active · Latency: 12ms` 10sp `#94A3B8`.
