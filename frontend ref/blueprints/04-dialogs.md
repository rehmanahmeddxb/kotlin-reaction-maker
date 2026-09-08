# Blueprint 04 — All Dialogs (D1–D9)

Shared `AlertDialog` language (D1–D8): container `#1F2432` · title white Bold (+cyan icon where shown) ·
cyan primary buttons (black text) · muted `TextButton` cancels · cyan/purple/amber sliders.

## D1 · StartupAspectRatioDialog (`showStartupAspectRatioDialog`, default ON)
Title row: ⬒ cyan + col[`Select Project Canvas Format` 16sp Bold white / `Screen will automatically
rotate to match` 11sp `#94A3B8`]. Body: 3 option cards (gap 10, full-width, clickable `Surface`
radius 10; selected: cyan-15% bg + 2dp cyan ring + cyan title, else white-5% + white-12% ring):
40dp icon tile radius 8 (selected cyan w/ black icon, else white-10% w/ `#F1F5F9` 24dp icon) + gap 12 +
col[title 13sp Bold / subtitle 10sp `#94A3B8`] + res badge (black-40% radius 4, white-10% ring,
9sp SemiBold `#64748B`, pad h6/v2).
- `16:9 Widescreen (Landscape)` / `Auto-rotates to Landscape 🔄 • YouTube, Gaming, PC Streams` /
  `1920 × 1080` / `StayCurrentLandscape`
- `9:16 Vertical (Portrait)` / `Auto-rotates to Portrait 📱 • Shorts, Reels, TikTok` / `1080 × 1920` /
  `StayCurrentPortrait`
- `1:1 Square Format` / `Standard square • Instagram feed, Posts` / `1080 × 1080` / `CropSquare`
Confirm: cyan **Start Project** Bold radius 8 (applies + dismisses). Shown after splash; also via
Canvas → Format & Auto-Rotate Dialog….

## D2 · AudioMixerDialog — `Studio Audio Mixer` (🎚 cyan + Bold white)
- `Master Volume: n%` 12sp SemiBold `#F1F5F9` + cyan slider (0–1).
- Gap 8. `Microphone Gain: n%` + amber slider **0–2.0**.
- Divider white-10% pad-v8. `Track Channels` 11sp Bold `#94A3B8`.
- `LazyColumn` per layer row (pad-v4): name[:12] 11sp `#F1F5F9` w80 + purple volume slider weight-1 +
  mute 32dp (muted amber `VolumeOff` else `#94A3B8`) + solo 32dp (`Stars`; soloed cyan else muted).
- Confirm: `Close` cyan TextButton. (Max body height 400dp.)

## D3 · ExportDialog — `Export Video` (📤 cyan)
Rows gap 12, group labels 12sp `#94A3B8`: Resolution chips `720p (HD)`/`1080p (FHD)`/`4K (UHD)` ·
Framerate chips `24 fps`/`30 fps`/`60 fps` · Codec chips `H.264 / AVC`/`H.265 / HEVC`
(`FilterChip`, 11sp; selected = cyan bg + black text). Destination row: 📁 16dp amber + label
12sp Medium `#F1F5F9` weight-1 + `OutlinedButton` h30 `Choose Folder…` 11sp → SAF picker.
Confirm cyan **Start Export** / dismiss muted `Cancel`.

## D4 · ExportProgressDialog — 3 states (centered column)
- Rendering `Rendering Video…`: cyan `LinearProgressIndicator` h8 radius 4 (track white-15%) +
  `Encoding frames: n%` 13sp `#F1F5F9` + `Compositing layers → H.264 → MP4…` 11sp muted. **No dismiss.**
- Completed `Export Completed!`: ✓-circle 48dp green + `Video successfully generated!` Bold `#F1F5F9` +
  path 11sp muted centered + green **Done** (black text).
- Failed `Export Failed`: ⚠ 44dp red + message 13sp centered `#F1F5F9` +
  `No file was created. Nothing was reported as exported.` 11sp muted + red **Close**.
Outside-tap dismiss allowed only in completed/failed states.

## D5 · LayerPropertiesDialog — `Layer Properties`
`Layer Name` outlined field (white text, cyan focus ring) · `Opacity: n%` 12sp + cyan slider ·
`Volume: n%` + purple slider · `Playback Speed: n.nx` + chips `0.5x/1.0x/1.5x/2.0x`
(selected cyan/black); cyan **Save** / muted Cancel.

## D6 · TextEditorDialog — `Text Overlay Editor`
`Text Content` field (initial: selected text or `😱 UNBELIEVABLE TWIST!`) · `Font Size: nsp` + cyan
slider **16–42** · `Text Color` 12sp `#94A3B8` + 7 dots 28dp gap 8 (Yellow `#FFD600` default-white-ring
2.5dp / White / Cyan / Red / Green / Orange `#F59E0B` / Purple) · row `Drop Shadow` 13sp weight-1 +
`Switch` (thumb cyan when on, default ON); cyan **Apply Text** / muted Cancel.

## D7 · RenameProjectDialog — `Rename Project`
`Project Title` outlined field (current name) · cyan **Rename** / muted Cancel.

## D8 · DiagnosticsDialog — `Diagnostics & Codecs` (🩺 cyan)
Groups gap 8, headers 12sp Bold cyan + 11sp `#94A3B8` bullets:
- Hardware Video Decoders: `c2.android.avc.decoder (HW H.264)` · `OMX.qcom.video.decoder.avc` ·
  `c2.android.hevc.decoder (HW H.265)`
- Camera Subsystem: `Camera2 API Level: FULL` · `Lens Facing: FRONT & BACK available` ·
  `Torch Controller: Hardware LED + Screen Light fallback`
- Compositor & Rendering: `Pipeline: EGL14 + GLES 2.0/3.0 PBO offscreen` ·
  `Canvas Compositor: Deterministic Preview == Export` · `Audio Clock: HAL Monotonic Sample PTS`
Confirm: `Close` cyan TextButton.

## D9 · SaveExportExplainerDialog (custom `Dialog`, tag `save_export_explainer_dialog`)
Card: full-width, margin 16, pad 20, radius 18, bg `#141822`, cyan-40% 1dp ring.
Header row: cyan-15% radius-8 tile (❓ 22dp cyan, pad 8) + col[`Save vs. Export` 17sp Bold white /
`Why Studio has two different options` 11sp `#94A3B8`] + ✕ 32dp `#94A3B8`.
Card 1 (amber-35% ring): 💾 18dp amber + `1. SAVE DRAFT (Project)` 13sp Bold amber + 4 bullets 11sp
`#94A3B8` lh16 (session/timeline/PiP/text/levels · instant in-app DB · resume later · not playable).
Card 2 (cyan-45% ring): 🎬 + `2. EXPORT MP4 (Final Video)` + 4 bullets (real MP4 on storage ·
Movies/Gallery · YouTube/TikTok/IG/WhatsApp-ready · not re-editable).
Cards: bg `#1B202E` radius 12, pad 14. Actions gap 8: **Save Draft** amber-outline weight-1 +
**Export MP4** cyan weight-1 (both 11sp Bold + 14dp icons; each fires action + dismisses).
