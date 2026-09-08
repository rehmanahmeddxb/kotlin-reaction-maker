# Blueprint 02 — Sidebar (S3)

`SidebarView.kt` · `Column(width 240/260dp, fillMaxHeight, bg #0E1016, verticalScroll, bottom-pad 24dp)`
Container tag: `sidebar_container`. Panel itself slides/fades via `AnimatedVisibility` in shell
(in: slide 220ms + fade 200ms · out: slide 180ms + fade 150ms). Each section body: `AnimatedVisibility` +
`animateContentSize`. Submenu bodies: black-25% bg + 24dp start indent.

```
┌─ SIDEBAR ─────────────────┐
│ ▸ 📁 SOURCES          [3] │  section header 44dp, badge, chevron
│   ├─ [🎥 Front Camera 👁🔊↑↓] │  layer rows 46dp (newest first)
│   ├─ [🎬 Video Clip #1 …]   │
│   ─────────────            │
│   ┌ Add Source          ▸ │  submenu header 42dp
│   │  Pick Video… / Sample…│  action rows 44dp (indented, dark bg)
│   ├─ 🗑 Remove Selected   │  danger red, disabled if none
│   ├─ Duplicate Selected   │
│ ▸ 🎚 SOURCE CONTROLS [sel]│
│ ▸ 🔊 AUDIO                │
│ ▸ ⏺ RECORD & TRANSPORT[REC]│
│ ▸ ⬒ CANVAS           [16:9]│
│ ▸ 📤 EXPORT               │
│ ▸ 📁 PROJECT              │
│ ▸ ⚙ SETTINGS              │
└───────────────────────────┘
```

## Row anatomy (exact)
- **SectionHeader** (`section_<id>`): h44, pad-h12; icon 18dp (expanded→cyan else `#94A3B8`); label
  13sp Bold caps (expanded→white else `#F1F5F9`); badge = radius-10 pill cyan-20% bg, cyan 10sp Bold,
  pad h6/v2; chevron 18dp `#64748B` (▾/▸); expanded row bg `#1F2432`; 5%-white divider under each.
- **SubMenuHeader**: h42, pad start16/end12; icon 16dp (open→purple `#818CF8` else `#94A3B8`); label 13sp
  Medium (open→white); chevron 16dp muted.
- **ActionItem**: h44, radius 6, pad-h16; icon 16dp + gap 10 + label 13sp 1-line ellipsis.
  States: default (`#F1F5F9`/icon `#94A3B8`) · **active** (cyan text+icon, bg `#244878`@40%) ·
  **danger** (red `#EF4444`) · **disabled** (muted@40%, not clickable).
- **LayerRowItem**: h46, margin h6/v2, radius 8, pad-h8; bg selected `#244878` else `#161922`@40%.
  Type icon 16dp in **layer accent** (front-cam amber / back-cam red / video cyan / image green /
  screen purple / text red) → name 12sp (selected Bold white) weight-1 → 👁 28dp (visible `#94A3B8` /
  hidden muted@40%, 14dp icon) → 🔊 28dp (muted amber else `#94A3B8`) → ↑ 24dp → ↓ 24dp (12dp muted icons).

## Full tree (every item + icon + behavior)

### §1 SOURCES — icon `Folder`, badge = layer count · tag `section_sources`
1. Layer rows ×N (reversed) — tap select; 👁 visibility; 🔊 mute; ↑↓ reorder.
2. Divider (white-8%, pad h12/v4).
3. **Add Source ▸** (`AddCircleOutline`, id `sub_add_source`):
   Pick Video from Device (`FileOpen` → video picker) · Sample Video Clip (`Movie`) ·
   Pick Image from Device (`AddPhotoAlternate` → image picker) · Sample Image Sticker (`Image`) ·
   Add Front Camera (`CameraFront`) · Add Back Camera (`CameraAlt`) · Screen Record (`ScreenShare`) ·
   Text Overlay (`TextFields` → D6 dialog).
4. Remove Selected (`DeleteOutline`, danger, disabled if none). 5. Duplicate Selected (`ContentCopy`, disabled if none).

### §2 SOURCE CONTROLS — icon `Tune`, badge = selected name[:10] · `section_controls`
- Empty: centered 12sp muted `Select a source to adjust controls` (pad 16).
- **Real File Card** (video/image layers): `Surface` radius 8, cyan-8% bg, cyan-30% ring, pad h12/v6;
  inside pad 10: type icon 16dp cyan + Bold 12sp white `Real Media File`/`Sample Template Media`;
  10sp `#94A3B8` desc (2-line ellipsis); full-width cyan button (radius 6, pad h8/v4, 📂 14dp +
  Bold 11sp black): `Select Real File from Device` / `Choose Different File`.
- Hide/Show Layer (`VisibilityOff/Visibility`, active=visible) · Lock/Unlock Position (`Lock/LockOpen`,
  active=locked) · Pause/Resume Layer (`PauseCircleOutline/PlayCircleOutline`) ·
  **Torch ON/OFF (Front/Back)** (`FlashOn/FlashOff`, camera-only, active=on).
- **Fit Mode (FIT/FILL) ▸** (`CropFree`, `sub_fit_mode`): Fill (Cover/Crop) (`Fullscreen`) ·
  Fit (Letterbox) (`FitScreen`).
- **Transform & Sizing ▸** (`OpenWith`, `sub_transform`): Auto Fill 100% Canvas (`FitScreen`, highlighted) ·
  Fit Inside Frame 90% (`AspectRatio`) · Center on Canvas (`FilterCenterFocus`) · Stretch 100% Width
  (`Straighten`) · Stretch 100% Height (`Height`) · ─ · **Width Scale** row (11sp label + cyan Bold `n%`) +
  cyan slider 0.1–1.5 · **Height Scale** same · ─ · Corner: Top-Left (`NorthWest`) / Top-Right (`NorthEast`) /
  Bottom-Left (`SouthWest`) / Bottom-Right (`SouthEast`) · ─ · **Rotation panel** (pad h14/v4): 🔄 amber
  14dp + `Rotation Angle` 11sp + amber Bold 12sp `n°`; amber slider −180…180; 3 `OutlinedButton`s
  (weight-1, white-20% ring; Reset = amber-40% ring): `↺ −90°` / `↻ +90°` / `Reset 0°` (10sp).
- Set as Background (`Wallpaper`) · Advanced Properties… (`SettingsSuggest` → D5).

### §3 AUDIO — icon `VolumeUp` · `section_audio`
- Mixer Panel… (`Equalizer` → D2). **Mic Gain (n%) ▸** (`Mic`, `sub_mic_gain`): +10% Gain (`Add`) ·
  −10% Gain (`Remove`, label uses − U+2212) · Reset 100% (`RestartAlt`).
- **`<name[:12]> Audio ▸`** (`Headphones`, `sub_layer_audio`, only if selected): Volume +10% (`VolumeUp`) ·
  Volume −10% (`VolumeDown`) · Mute/Unmute Layer (`VolumeOff/VolumeUp`, active=muted) ·
  Solo/Un-solo Track (`Stars`, active=soloed).

### §4 RECORD & TRANSPORT — icon `FiberManualRecord`, badge `REC` while recording · `section_record`
- Start Recording / Stop & Save Recording (`FiberManualRecord`/`StopCircle`, danger+active) ·
  Play/Pause (`PlayArrow`/`Pause`) · Stop (`Stop`) · Add Live Camera Take (Front) (`CameraFront`) ·
  Screen Capture Source (`ScreenShare`) · Snapshot Frame (`CameraAlt`) · Restart Timeline (`Replay`) ·
  **Light (mode) ▸** (`Lightbulb`, `sub_light`): Turn Off Light (`FlashOff`) · Front Torch (`FlashOn`) ·
  Back Torch (`FlashOn`) · Both Torches (`Highlight`) · Screen Light (Softbox) (`WbSunny`).

### §5 CANVAS — icon `AspectRatio`, badge = ratio[:4] · `section_canvas`
- **Aspect Ratio (label) ▸** (`CropOriginal`, `sub_aspect`): 16:9 Landscape (YouTube) (`Laptop`) ·
  9:16 Portrait (TikTok/Shorts) (`Smartphone`) · 1:1 Square (Instagram) (`CropSquare`) ·
  Format & Auto-Rotate Dialog… (`ScreenRotation` → D1).
- **Background (label) ▸** (`Palette`, `sub_bg`): Dark `#0E1016` · Black · White · Orange `#E65100` ·
  Navy `#0D1B2A` · Green `#1B4332` · Purple `#3A0CA3` (each `Circle` icon tinted its color).
- Full Canvas toggle (`Fullscreen`, active) · Fit All Sources (`CenterFocusStrong`) ·
  Selection → Background (`Layers`, disabled if none).

### §6 EXPORT — icon `FileUpload` · `section_export`
Quick Export (720p30 MP4) (`Bolt`) · Export Settings… (`Settings` → D3).

### §7 PROJECT — icon `SnippetFolder` · `section_project`
Rename Project… (`Edit` → D7) · Save Now (`Save`) · Snapshot Frame (`PhotoCamera`) ·
Show/Hide Stats Overlay (`Speed`, active) · Undo (`AutoMirrored.Undo`) · Redo (`AutoMirrored.Redo`) ·
Diagnostics (`MedicalServices` → D8).

### §8 SETTINGS — icon `Settings` · `section_settings`
Export Quality Settings… (`HighQuality` → D3) · Export Folder: `<name>` (`FolderSpecial` → SAF picker) ·
About Ahmed Reaction Studio (`Info` → D8).

**Defaults:** sidebar OPEN; expanded = {sources, record}; all submenus collapsed.
**Add presets:** Front Cam PiP (0.78,0.28,0.35×0.38, amber) · Back Cam PiP (0.22,0.28, red) ·
Video 80% centered (cyan, `Video Clip #n`) · Sticker 25% (green) · Screen 90% (purple) ·
Text lower-third (red accent, white 22sp Bold+shadow, `WOW! Look at this part!`).
