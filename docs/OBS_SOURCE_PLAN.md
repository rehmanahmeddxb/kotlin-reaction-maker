# Ahmed Reaction Studio — OBS-style Source Architecture (V1 plan, locked)

> Status: **implemented V1** (this document ships together with the code).
> This is the re-plan of the whole app around one idea:
> **sources are first-class citizens, controlled instantly, never through a settings maze.**

---

## 0. Why the re-plan

The camera "cuts out" on the canvas because the old compositor had exactly one
fit rule — COVER (crop-to-fill). A camera take whose aspect differs from the
canvas (a portrait take on a 16:9 canvas, or a 16:9 take on 9:16) was zoomed
until it filled the box, throwing away up to ~70 % of the picture.

The deeper problem was structural: visibility / mute / pause / lock lived in
panels you had to open, and the UI had no single "this is a source, here are
its controls" surface. So the app is re-planned around an OBS-like model.

---

## 1. The locked design principle

> Every source is an independent object with its own visual, audio, playback
> and interaction state. The user controls it instantly, without interrupting
> the composition or the export. The Compositor is the single source of truth
> for what you see and what gets exported.

State flow (every action, no exceptions):

```
User gesture / button
      ↓
SourceController command (one function per verb)
      ↓
undo snapshot pushed
      ↓
Project state mutated (Layer fields)
      ↓
Composition Engine (Compositor + PreviewEngine) reads the SAME state
      ↓
Preview == Export. Always.
```

There is never a "preview state" and a "recording state". `visible = false`
means absent from both, `muted = true` means silent in both.

---

## 2. Source model (upgraded)

Every source (still class `Layer` on disk for compatibility) carries:

```
Source
├── id, type (VIDEO | CAMERA | SCREEN | IMAGE | TEXT), name
├── visible          ← Hide / Show (never deletes)
├── locked           ← gestures disabled, still rendered
├── muted            ← per-source audio switch
├── solo             ← audio-solo (see §5)
├── loop             ← video: wrap at end, or hold last frame
├── fit              ← "fill" (cover-crop) | "fit" (whole frame, letterboxed)   ★ camera-cut fix
├── volume, opacity, speed
├── playing, pausedMediaMs   ← source pause (independent of everything)
├── transform (cx, cy, wN, hN, rotDeg — normalized)
└── media ref (relPath, durMs, srcW/H, rotation)
```

Not every type uses every field — the UI shows only what a source supports.

### Hide vs Pause vs Lock vs Delete vs Mute (binding table)

| Action | In preview | In export | Source survives | Audio |
|---|---|---|---|---|
| Hide     | gone | gone | ✅ in dock | **still audible** (OBS rule) |
| Mute     | visible | visible, silent | ✅ | silent |
| Pause    | last frame held | last frame held | ✅ | silent while paused |
| Lock     | visible | visible | ✅ | unchanged |
| Solo     | visible | visible | ✅ | only soloed sources audible |
| Delete   | gone | gone | ❌ | gone |

Two deliberate enhancements over the original idea:

1. **Hidden sources keep their audio.** Visibility is a *visual* property,
   exactly like OBS. Hiding a music layer would otherwise surprise users.
2. **Non-loop video auto-pauses on its last frame** (instead of silently
   restarting). Pressing play on an ended source restarts it from 0:00.

---

## 3. Fit mode — the camera-cut fix, made a first-class control

```
fit = "fill"   →  COVER:  frame fills the box, edges cropped
fit = "fit"    →  CONTAIN: whole frame visible, letterboxed inside the box
```

Defaults on add:

| Source | as main canvas | as PiP / overlay |
|---|---|---|
| Camera take | **fit** (never cut) | source-aspect box (never cut) |
| Video       | fill (full bleed) | source-aspect box |
| Image       | fill | source-aspect box |
| Screen rec  | fill | source-aspect box |

Old projects: camera layers without a stored `fit` load as `"fit"`, so the
existing complaint disappears retroactively. Every other layer keeps `"fill"`
(previous behavior). Fit is a one-tap control (quick bar + radial wheel + long
press) with icons `crop_free` (fit) / `fullscreen` (fill).

---

## 4. Interaction surfaces

### 4.1 Quick Control Bar (floating, appears on selection)

```
[ 🎥 Camera take ]  [👁] [🔇] [⏯] [🔒] [🔁] [⭐]  [◉] [⋮]
```

- Contextual: image/text sources don't get play/mute/loop.
- Every button is a modern vector icon with press-pulse animation and haptic
  tick; engaged states are tinted (muted = red, locked = amber…).
- `◉` opens the **radial wheel**, `⋮` opens the **advanced sheet**.

### 4.2 Radial wheel (contextual, animated, premium)

Tap ◉: a scrim fades in, the hub (source icon + name) springs in, and 6–8
petals fly out with a staggered overshoot. Petal set depends on source type:

- **Video / Camera / Screen:** Play·Pause, Mute, Loop, Hide, Lock, Fit/Fill,
  Duplicate, Delete
- **Image:** Hide, Lock, Fit/Fill, Center, Duplicate, Delete
- **Text:** Edit, Color, Hide, Lock, Center, Delete

Petal tap → flash + haptic → action → wheel closes. Tap outside or on the hub
to dismiss. This replaces 20 permanent buttons with 0.

### 4.3 Source Dock = mini mixer

```
┌────────────────────────────────────────┐
│ 👁 🔊  🎥 Camera take        SOLO  ⠿  │
│ 👁 🔇  🎬 My Video.mp4   PAUSED    ⠿  │
│ 🚫 🔊  🖼 Logo.png        HIDDEN   ⠿  │
└────────────────────────────────────────┘
```

- Each row: eye toggle, mute toggle, type icon, name + live status
  (HIDDEN / MUTED / PAUSED / LOCKED / SOLO / LOOP chips), drag handle.
- Rows are **drag-reordered by the handle** → Z-order changes live, one undo
  step per drag.
- Tap a row → selects the source on canvas + shows the quick bar.
- Long press a row → advanced sheet.

### 4.4 Long press / ⋮ = Advanced sheet (the only "deep" UI)

Rename · Opacity slider · Volume slider · Fit toggle · Loop toggle ·
Solo toggle · Z-order (front / up / down / back) · 6-corner anchor grid ·
Set as canvas background · Duplicate · Delete.

### 4.5 Canvas gestures

| Gesture | Meaning |
|---|---|
| Tap | select source |
| **Double tap** | **Hide / Show that source** (new) |
| Drag | move (snap guides, clamped on canvas) |
| 8 handles | resize (media keeps aspect) |
| Knob / two-finger twist | rotate |
| Pinch | scale + rotate around fingers |
| Tap empty | deselect |

### 4.6 Transport / structure

```
top bar      ‹  project name · aspect chip  ↶ ↷ ⚙
canvas       full-bleed stage, letterboxed to the project aspect
quick bar    floats above the dock when a source is selected
dock         Sources / Add + / Canvas / Export tabs + transport row
```

Aspect chip cycles 16:9 → 9:16 → 1:1 and rotates the screen; normalized
geometry means nothing moves.

---

## 5. Solo — enhancement over "store previous state"

The original idea stored `previousVisibility / previousMuteState` and
restored them. That invites restore bugs (crash between solo and restore,
sources added while soloed…).

**Enhancement: compute, don't store.** `solo` is just a flag:

```
effectiveMuted(source) = source.muted  OR  (anySourceIsSolo AND NOT source.solo)
```

Toggling solo off *cannot* lose anything because nothing was overwritten.
V1 solo is **audio solo** (OBS semantics). Visual solo ("everything else
hidden") is V2 and will use the same computed-state pattern.

---

## 6. Command system & Undo/Redo

Every mutation goes through `SourceController` verbs:

```
toggleVisible · toggleMuted · toggleLocked · toggleSolo · toggleLoop
togglePlay · setFit · setOpacity · setVolume · rename
moveZ(front|back|up|down|index) · anchor · setAsCanvasBackground
duplicate · delete · addVideo/addImage/addCamera/addText
```

Each verb: push undo snapshot → mutate → notify UI + autosave.
Undo/redo restores full layer-list snapshots (tiny JSON, never media bytes).

---

## 7. Recording modes (honest scope)

- **Today** the app's "record" is the deterministic export pipeline
  (same Compositor code path → MediaCodec H.264/H.265/VP8/VP9). During an
  export the UI is locked: no deletes, no aspect changes, no destructive ops.
- **Live composition recording** (real-time encoder while you watch) is V2.
  The CPU compositor cannot feed 1080p30 in real time, so V2 requires the GPU
  (OpenGL ES) compositor from the master plan; the source model and command
  system in this plan were designed so that swap touches no UI code.

---

## 8. Roadmap

- **V1 — DONE in this change:** source model upgrade (fit/loop/solo),
  SourceController command layer, quick control bar, contextual radial wheel
  with spring animations, mini-mixer source dock with drag Z-order, advanced
  sheet, double-tap hide, modern vector icon set, per-source-type defaults,
  hidden-keeps-audio, non-loop end-hold, camera-cut fix.
- **V1.5:** ±10 s nudge & trim-in/out, source thumbnails in the dock,
  configurable double-tap action, edge-swipe panels, snap-guide overlays.
- **V2:** GPU compositor + live composition recording (the RECORD button),
  visual solo, freeze-frame as a clip operation, audio meters & per-source
  volume automation, microphone source, camera zoom/flash from the editor.
- **V3:** scenes & transitions, text/sticker library, chroma key, LUTs,
  streaming (RTMP), templates.

---

## 9. What was intentionally NOT done

- No Compose/Media3/Gradle rewrite: this repo builds offline with kotlinc →
  d8 → aapt2 and that pipeline is kept (the R class is now generated in it).
  The master-plan Compose/Media3 target stays the north star for V2+.
- No live camera *inside* the canvas yet (camera takes are recorded clips);
  the radial wheel for camera sources is therefore playback-oriented. Live
  camera-as-source ships with the V2 GPU pipeline.
