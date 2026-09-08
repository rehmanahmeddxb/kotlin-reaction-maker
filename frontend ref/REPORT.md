# REACTION STUDIO — Complete Frontend Blueprint Report

> **Carbon-copy frontend reference** for the `TEST-aistudio` app ("Reaction Studio").
> Studied file-by-file from source (no screen, section, button, dialog, toast, or state missed).
> Companion pack: `blueprints/` (per-area deep dives) + `mockup/` (clickable HTML carbon copy) + `assets/`.

- **App name:** Reaction Studio (`app_name` = "Reaction Studio", default project = "Ahmed Reaction Studio")
- **Brand mark:** AMS — "Create. React. Record." (splash) + neon aperture logo (`assets/app-logo.jpg`)
- **Package:** `com.example` · **Tech:** Single-Activity (`MainActivity`) · Jetpack Compose + Material3 · dark theme only
- **Source files studied:** `MainActivity.kt` (501), `TopStrip.kt` (354), `SidebarView.kt` (1282),
  `StageView.kt` (1820), `FloatingControls.kt` (173), `Modals.kt` (794), `AmsSplashScreen.kt` (167),
  `SaveExportExplainerDialog.kt` (196), `Color.kt`/`Theme.kt`/`Type.kt`, `StudioModels.kt`,
  `SidebarTree.kt`, `StudioViewModel.kt` (1261), `AndroidManifest.xml`, `res/values/*`
- **Total UI lines studied:** ~6,900

---

## 1. Screen inventory (every screen / overlay / dialog)

| # | Screen / Surface | Type | Source | When visible |
|---|---|---|---|---|
| S0 | AMS Splash | Full-screen overlay | `AmsSplashScreen.kt` | First ~1.2 s of launch, over the studio |
| S1 | Studio Shell | Root layout | `MainActivity.kt` | Always (under splash) |
| S2 | Top Strip | 48 dp bar | `TopStrip.kt` | Always, except hidden in immersive canvas workspace |
| S3 | Sidebar | 240/260 dp panel | `SidebarView.kt` | When `isSidebarOpen` AND chrome visible; slides in/out |
| S4 | Canvas Stage | Center area | `StageView.kt` | Always (fills leftover space; fullscreen in Full Canvas) |
| S5 | Quick Action Bar | Floating pill, top-center of stage | `StageView.kt` | When a layer is selected |
| S6 | Workspace Pill | Floating pill, top-right of stage | `StageView.kt` | Always (morphs in immersive mode) |
| S7 | REC Indicator | Small pill under workspace pill | `StageView.kt` | Immersive mode + recording |
| S8 | Timeline Bar | Floating pill, bottom-center of stage | `StageView.kt` | When chrome visible |
| S9 | Floating Transport | Floating pill stack, bottom-right of stage | `FloatingControls.kt` | When chrome visible (fade in/out) |
| S10 | Stats HUD | Card, top-left of stage | `StageView.kt` | When `showStatsOverlay` |
| S11 | Screen-Light Overlay | Full-screen white 85% | `MainActivity.kt` | When torch mode = Screen Light |
| D1 | Startup Aspect-Ratio Dialog | AlertDialog | `Modals.kt` | On launch (after splash), or via Canvas menu; default shown |
| D2 | Audio Mixer Dialog | AlertDialog | `Modals.kt` | Via Audio → Mixer Panel |
| D3 | Export Dialog | AlertDialog | `Modals.kt` | Via Export MP4 / Export Settings |
| D4 | Export Progress Dialog | AlertDialog (3 states) | `Modals.kt` | While exporting / on success / on error |
| D5 | Layer Properties Dialog | AlertDialog | `Modals.kt` | Via Source Controls → Advanced Properties… |
| D6 | Text Editor Dialog | AlertDialog | `Modals.kt` | Via Add Source → Text Overlay, or double-tap text layer |
| D7 | Rename Project Dialog | AlertDialog | `Modals.kt` | Via title click or Project → Rename |
| D8 | Diagnostics Dialog | AlertDialog | `Modals.kt` | Via overflow menu / Project / About |
| D9 | Save-vs-Export Explainer | Custom Dialog | `SaveExportExplainerDialog.kt` | Via overflow → "Why Save vs Export?" |
| SYS1 | Camera permission prompt | System | `MainActivity.kt` | On launch if not granted |
| SYS2 | Export folder picker | System (SAF tree) | `MainActivity.kt` | Via Export Folder / Destination chooser |
| SYS3 | Toasts | System toast | `StudioViewModel.kt` | After ~30 different actions (catalog §8) |

**There is no multi-page navigation** — the app is ONE studio screen + dialogs. All "pages" are sidebar
sections and canvas modes. Back button behavior: immersive → restores chrome → exits Full Canvas →
closes sidebar → exits app.

---

## 2. Launch sequence (frame by frame)

1. `MainActivity` renders `StudioScreen` immediately (studio initializes underneath).
2. `AmsSplashScreen` overlays it: badge scales 85→100% + fades (550 ms) → "AMS" slides up + fades (360 ms)
   → slogan fades (300 ms) → hold 320 ms → whole overlay fades (260 ms) → removed. Total ≈ 1.2 s.
3. `StartupAspectRatioDialog` appears (it was deferred until splash finished).
4. Camera permission is requested (system prompt) if not granted.
5. Studio idle state: sidebar OPEN, sections **SOURCES** + **RECORD & TRANSPORT** expanded, empty canvas
   ("Your canvas is ready"), timeline `00:00 / 00:00` disabled, project "Ahmed Reaction Studio".

---

## 3. Design tokens (exact)

### 3.1 Colors
| Token | Hex | Usage |
|---|---|---|
| `StudioDark` | `#0E1016` | App bg, scaffold, splash, sidebar |
| `StudioSurface` | `#161922` | Cards, dialog alt |
| `StudioSurfaceElevated` | `#1F2432` | Dialogs, chips, expanded headers |
| `StudioSurfaceBorder` | `#2E3547` | Borders |
| `StudioCyan` | `#38BDF8` | Primary: play, selection, active states, Export MP4 button |
| `StudioBlue` | `#2563EB` | (theme secondary alt) |
| `StudioPurple` | `#818CF8` | Screen layers, volume sliders, submenu icons |
| `StudioRecordRed` | `#EF4444` | Record, delete, REC badges, danger rows |
| `StudioAmber` | `#F59E0B` | Dirty/save, mic gain, rotation, lock, front camera |
| `StudioGreen` | `#10B981` | Saved dot, success, image layers, live indicator |
| `StudioTextPrimary` | `#F1F5F9` | Headings, labels |
| `StudioTextSecondary` | `#94A3B8` | Secondary text, icons |
| `StudioTextMuted` | `#64748B` | Hints, disabled |
| Stage backdrop | `#07080B` | Area around canvas |
| Canvas default | `#0E1016` (DARK) | Changeable: Black `#000000`, White `#FFFFFF`, Orange `#E65100`, Navy `#0D1B2A`, Green `#1B4332`, Purple `#3A0CA3` |
| Layer accents | Cyan `#38BDF8` video, Amber `#F59E0B` front cam, Red `#EF4444` back cam/text, Green `#10B981` image, Purple `#818CF8` screen | Sidebar layer icons |
| Text palette | Yellow `#FFD600` (default), White, Cyan, Red, Green, Orange `#F59E0B`, Purple | Text editor dots |

### 3.2 Typography
Material3 defaults on `FontFamily.Default`; explicit sizes used: **9 sp** (badges, pills),
**10 sp** (HUD, layer badges), **11 sp** (buttons, sliders values, dialog body), **12 sp** (layer names,
section bodies), **13 sp** (rows, dialog text), **14 sp** (project title, slogan),
**15–17 sp** (dialog/empty-state titles), **34 sp** splash "AMS" (ExtraBold, 6 sp letter-spacing),
**40 sp** splash badge letters (Black). Text layers default 22–28 sp, editor range 16–42 sp.

### 3.3 Shape / elevation / motion
- Radii: pills 14–28 dp, cards/dialogs 8–18 dp, canvas 6 dp, splash badge 28 dp, layer rows 8 dp.
- Elevations: transport pill 8 dp, quick bar 8 dp, workspace pill 6 dp, canvas shadow 16 dp.
- Motion: sidebar slide 220 ms in / 180 ms out + fade; transport fade 150 ms; REC pulse 600 ms reverse;
  splash glow pulse 700 ms reverse; video wave ticker 4 s linear loop.

---

## 4. Studio shell layout (S1)

```
┌───────────────────────────────────────────────────────────────┐
│ TOP STRIP — 48dp, #0E1016 @90%                                 │  S2
├──────────┬────────────────────────────────────────────────────┤
│          │  ┌─ Quick Action Bar (top-center, on selection) ─┐  │
│ SIDEBAR  │  │ [Auto Fill][Fit][Center][↔][↕][0°][🗑]        │  │ S5
│ 240dp    │  │                          [Full Canvas|👁] pill │  │ S6
│ landscape│  │  ┌──────────────────────────────────────┐      │  │
│ 260dp    │  │  │                                      │ [HUD]│  │ S10
│ portrait │  │  │            CANVAS                    │      │  │
│ scroll,  │  │  │   (aspect-fitted, shadow 16dp)       │      │  │
│ slide    │  │  │                                      │      │  │
│ anim     │  │  └──────────────────────────────────────┘      │  │
│          │  │  [▶ 00:00/00:00 ━━━━●━━━━] timeline pill  [▶⏺]│  │ S8+S9
└──────────┴────────────────────────────────────────────────────┘
 + Screen-Light overlay (white 85%) when enabled                 │  S11
 + Dialogs D1–D9 centered above everything                       │
```

---

## 5. Top Strip (S2) — left → right, every control

| # | Control | Visual | States | Action |
|---|---|---|---|---|
| 1 | Hamburger | 40 dp, rounded 8 | Cyan bg tint when sidebar open | Toggle sidebar |
| 2 | Project title | 14 sp SemiBold + 12 dp ✎ icon | Ellipsis 1 line | Opens Rename dialog |
| 3 | Aspect chip | Elevated pill, ⬒ icon + `16:9`/`9:16`/`1:1` cyan Bold 11 sp | Label follows ratio | Cycles ratio (also auto-rotates device) |
| 4 | Undo | 36 dp, 18 dp icon | Disabled = muted 40% | Undo |
| 5 | Redo | 36 dp, 18 dp icon | Disabled = muted 40% | Redo |
| 6 | Save Draft | Pill: 7 dp dot + 💾 + text | Dirty: amber "Save Draft"; clean: grey "Saved" + green dot | Save project |
| 7 | Export MP4 | 30 dp cyan filled button, 🎬 icon, black ExtraBold 11 sp | Always enabled | Quick export 720p30 |
| 8 | Full Canvas | 36 dp ⛶/⛶-exit 20 dp | Cyan when active | Toggle Full Canvas |
| 9 | Overflow ⋮ | 36 dp | — | Opens menu: Full Screen Canvas toggle · **Why Save vs Export?** (amber) · Show/Hide Stats Overlay · Studio Settings & Diagnostics |

---

## 6. Sidebar (S3) — all 8 sections, every row (38+ actions)

Row anatomy — **Section header** (44 dp, 18 dp icon, 13 sp Bold caps label, optional cyan badge,
chevron; expanded = elevated bg + cyan icon) · **Submenu header** (42 dp, 16 dp icon purple when open) ·
**Action row** (44 dp; states: default / active-cyan + `#244878` 40% bg / danger-red / disabled-muted-40%) ·
**Layer row** (46 dp: type icon in layer accent color, name, 👁, 🔊, ↑, ↓).

### 6.1 § SOURCES (badge = layer count)
Layer list (newest first) → divider → **Add Source ▸**: Pick Video from Device · Sample Video Clip ·
Pick Image from Device · Sample Image Sticker · Add Front Camera · Add Back Camera · Screen Record ·
Text Overlay → **Remove Selected** (red, disabled if none) · **Duplicate Selected** (disabled if none).

### 6.2 § SOURCE CONTROLS (badge = selected name, 10 chars)
- No selection: centered hint *"Select a source to adjust controls"*.
- **Real File Card** (video/image only): cyan-tinted card — "Real Media File" vs "Sample Template Media",
  description, cyan button "Select Real File from Device" / "Choose Different File".
- Hide/Show Layer · Lock/Unlock Position · Pause/Resume Layer · **Torch ON/OFF (Front/Back)** (camera only).
- **Fit Mode ▸**: Fill (Cover/Crop) · Fit (Letterbox).
- **Transform & Sizing ▸**: Auto Fill (100% Canvas, highlighted) · Fit Inside Frame (90%) · Center on Canvas ·
  Stretch 100% Width · Stretch 100% Height · divider · **Width Scale slider** (10–150%, cyan) ·
  **Height Scale slider** (10–150%, cyan) · divider · Corner Top-Left/Top-Right/Bottom-Left/Bottom-Right ·
  divider · **Rotation panel**: amber slider −180°…+180° + `↺ −90°` / `↻ +90°` / `Reset 0°` buttons.
- Set as Background · Advanced Properties…

### 6.3 § AUDIO
Mixer Panel… · **Mic Gain (n%) ▸**: +10% Gain · −10% Gain · Reset 100% ·
**`<name> Audio ▸`** (if selected): Volume +10% · Volume −10% · Mute/Unmute Layer · Solo/Un-solo Track.

### 6.4 § RECORD & TRANSPORT (badge = REC while recording)
Start/Stop Recording (red) · Play/Pause · Stop · Add Live Camera Take (Front) · Screen Capture Source ·
Snapshot Frame · Restart Timeline · **Light (mode) ▸**: Turn Off Light · Front Torch · Back Torch ·
Both Torches · Screen Light (Softbox).

### 6.5 § CANVAS (badge = `16:9` etc.)
**Aspect Ratio ▸**: 16:9 Landscape (YouTube) · 9:16 Portrait (TikTok/Shorts) · 1:1 Square (Instagram) ·
Format & Auto-Rotate Dialog… · **Background ▸**: Dark/Black/White/Orange/Navy/Green/Purple (dot icons) ·
Full Canvas Mode toggle · Fit All Sources · Selection → Background (disabled if none).

### 6.6 § EXPORT — Quick Export (720p30 MP4) · Export Settings…
### 6.7 § PROJECT — Rename Project… · Save Now · Snapshot Frame · Show/Hide Stats Overlay ·
Undo · Redo · Diagnostics
### 6.8 § SETTINGS — Export Quality Settings… · Export Folder: `<name>` · About Ahmed Reaction Studio

Default layer presets on add: Front Cam PiP top-right amber; Back Cam PiP top-left red;
Video 80% centered cyan; Sticker 25% green; Screen 90% purple; Text lower-third red accent.

---

## 7. Canvas Stage (S4) — every element

- **Backdrop** `#07080B` (tap = deselect) · **Canvas**: aspect-fitted, 16 dp shadow, 1 dp white-15% border,
  6 dp radius, bg = chosen canvas color; immersive mode removes the 8/48 dp insets.
- **Empty state**: frosted icon tile (🎬 cyan) + *"Your canvas is ready"* Bold 15 sp +
  *"Add a camera, video, image or text to begin."* 12 sp.
- **Layer visuals** (bottom→top): VIDEO = real player w/ bottom-left ▶/⏸ + name badge, else neon-grid +
  animated waveform demo · CAMERA = live preview, 2 dp facing-color border (amber front / red back),
  FRONT/BACK pill (green dot when bound), yellow torch dot, silhouette + green audio bars fallback ·
  IMAGE = photo or green "★ REACTION" placeholder · SCREEN = purple "🖥 Display Screen Stream" ·
  TEXT = styled text w/ black offset shadow.
- **Snap guides**: cyan dashed center hairlines when a dragged layer is within 8 px of center.
- **Selection chrome** (always on top): 2 dp border (cyan, amber if locked) · label pill
  `TYPE • name` (+🔒) · center 44 dp move crosshair · 4× cyan corner dots (15 dp, black ring) ·
  4× white edge pills (6×18 / 18×6) · amber rotation knob above top-center w/ `n°` badge ·
  locked = amber 🔒 chip instead · quick pill (👁 + ▶/⏸ for video/camera/screen) floats above
  top-right, or docks inside when the layer touches the canvas top.
- **Quick Action Bar S5** (on selection): cyan **Auto Fill** + Fit + Center + stretch-W + stretch-H +
  amber 0° + red 🗑.
- **Workspace Pill S6**: normal = `⛶ Full Canvas | 👁‍🗨`; Full Canvas w/ chrome = cyan `Exit Full | 👁‍🗨`;
  immersive = eye-restore + exit glyphs only. Eye toggles STUDIO chrome (never sources).
- **REC pill S7** (immersive + recording): red-ringed `● REC mm:ss`.
- **Timeline Bar S8**: 44 dp pill, 85% width: ▶/⏸ (cyan when playing) + `mm:ss / mm:ss` 11 sp +
  cyan slider (disabled + `00:00 / 00:00` when project empty) + red dot while recording.
- **Stats HUD S10**: black-85% card, cyan border: `STUDIO ENGINE HUD` · `FPS: 60 · Res: 1920x1080` ·
  `FrameTime: 16.4ms · Decoder: HW MediaCodec + OES` · `Layers: n active · Latency: 12ms`.
- **Floating Transport S9** (bottom-right, above timeline): REC `mm:ss` pill (red ring, while recording) +
  dark pill: 48 dp cyan ▶/⏸, 48 dp white ⏹, 54 dp red ⏺ (idle = red-20% w/ red ring; recording = solid
  red, white ring, 26 dp ⏹ icon, pulsing 1→1.15×).
- Gestures: tap select · tap backdrop deselect · drag move · corner/edge resize · knob rotate ·
  double-tap text = edit · pinch listed (transform detector present).

---

## 8. Dialogs D1–D9 (every field & button)

- **D1 Startup Format**: `Select Project Canvas Format` + *"Screen will automatically rotate to match"*;
  3 cards (40 dp icon tile + Bold title + 10 sp subtitle + res badge): 16:9 Widescreen (Landscape) /
  9:16 Vertical (Portrait) / 1:1 Square Format — selected = cyan wash + 2 dp cyan border; **Start Project**.
- **D2 Audio Mixer**: `Studio Audio Mixer`; Master Volume slider (cyan) · Microphone Gain slider (amber, 0–200%);
  divider; `Track Channels` list per layer: name (12 ch) + purple volume slider + mute + solo(★);
  **Close**.
- **D3 Export Video**: Resolution chips 720p(HD)/1080p(FHD)/4K(UHD) · Framerate 24/30/60 fps · Codec
  H.264/H.265 (selected chip = cyan, black text) · Destination row (amber 📁 + label + **Choose Folder…**);
  **Start Export** (cyan) / Cancel.
- **D4 Progress**: Rendering = cyan bar + `Encoding frames: n%` + `Compositing layers → H.264 → MP4…`
  (no dismiss); Completed = green ✓ + `Video successfully generated!` + path + **Done**;
  Failed = red ⚠ + message + `No file was created…` + red **Close**.
- **D5 Layer Properties**: Layer Name field · Opacity slider (cyan %) · Volume slider (purple %) ·
  Speed chips 0.5×/1.0×/1.5×/2.0×; **Save** / Cancel.
- **D6 Text Editor**: Text Content field (default `😱 UNBELIEVABLE TWIST!`) · Font Size slider 16–42 sp ·
  7 color dots (28 dp, white ring when picked; default yellow) · Drop Shadow switch; **Apply Text** / Cancel.
- **D7 Rename**: Project Title field; **Rename** / Cancel.
- **D8 Diagnostics**: `Diagnostics & Codecs` — Hardware Video Decoders (3 bullets) · Camera Subsystem
  (3 bullets) · Compositor & Rendering (3 bullets); **Close**.
- **D9 Save vs Export**: custom card (cyan border): header `Save vs. Export` + subtitle + ✕;
  amber card `1. SAVE DRAFT (Project)` (4 bullets) + cyan card `2. EXPORT MP4 (Final Video)` (4 bullets);
  **Save Draft** (amber outline) + **Export MP4** (cyan).

All AlertDialogs: `#1F2432` container, white Bold titles, cyan confirms, muted cancels.

---

## 9. Toast catalog (all 30)

`Undone` · `Redone` · `Add a video to the timeline before playing` · `Recording started` ·
`Take saved: {n}s reaction clip` · `Snapshot saved to Gallery at mm:ss` · `Layer deleted` ·
`Layer duplicated` · `Added {name}` · `{name} torch ON/OFF` · `{name}: Torch not supported on this device` ·
`Auto-filled entire canvas (100%)` · `Auto-filled entire frame (100% canvas)` · `Fitted within canvas frame` ·
`Centered in frame` · `Stretched to canvas width` · `Stretched to canvas height` · `Set as background layer` ·
`Mic Gain: n%` · `Mic Gain: 100%` · `Solo active for track` / `Solo disabled` · `Lighting: {mode}` ·
`Aspect: {label}` · `Background: {label}` · `Aligned all sources` · `Renamed to {name}` ·
`Project saved to local store` · `Rotation reset to 0°` · media attach/validation messages ·
`Add a video to the timeline before exporting` · `Export complete! Video saved.` · export errors.

## 10. Modes & visibility matrix

| UI | Normal | Sidebar closed | Full Canvas + chrome | Immersive (no chrome) |
|---|---|---|---|---|
| Top strip | ✅ | ✅ | ✅ | ❌ |
| Sidebar | ✅ (open) | ❌ | ✅ (open) | ❌ |
| Timeline bar | ✅ | ✅ | ✅ | ❌ |
| Floating transport | ✅ | ✅ | ✅ | ❌ (REC pill instead) |
| Canvas insets | 8/48 dp | 8/48 dp | 8/48 dp | none (max area) |
| Workspace pill | Full Canvas+eye | Full Canvas+eye | Exit+eye (cyan) | eye-restore + exit |
| Back button | closes sidebar | exits app | exits FC / restores chrome chain | restores chrome first |

Extra: portrait sidebar 260 dp / landscape 240 dp · aspect change auto-rotates device
(16:9→landscape, 9:16→portrait, 1:1→free) · `configChanges` handled, edge-to-edge w/ safe insets.

## 11. Test tags (automation hooks)

`sidebar_container` · `hamburger_button` · `aspect_ratio_chip` · `undo_button` · `redo_button` ·
`save_project_button` · `export_mp4_button` · `full_canvas_button` · `overflow_menu_button` ·
`section_{sources,controls,audio,record,canvas,export,project,settings}` · `stage_canvas` ·
`canvas_quick_action_bar` · `auto_fill_button` · `fit_frame_button` · `center_button` ·
`stage_full_canvas_toggle` · `studio_chrome_eye_button` (×2 states) · `workspace_rec_indicator` ·
`timeline_bar` · `canvas_visibility_button` · `canvas_play_pause_button` ·
`floating_play_pause_button` · `floating_stop_button` · `floating_record_button` ·
`save_export_explainer_dialog`.

## 12. File → UI map

| File | Renders |
|---|---|
| `MainActivity.kt` | Shell, scaffold, splash host, all dialog hosts, screen-light overlay, permission/SAF/toast wiring |
| `TopStrip.kt` | S2 + overflow menu |
| `SidebarView.kt` | S3: 8 sections, submenus, layer rows, sliders, rotation panel, file card |
| `StageView.kt` | S4–S8, S10: canvas, layers, chrome/handles, quick bar, workspace pill, timeline, HUD |
| `FloatingControls.kt` | S9 transport |
| `Modals.kt` | D1–D8 |
| `SaveExportExplainerDialog.kt` | D9 |
| `AmsSplashScreen.kt` | S0 |
| `Color.kt` / `Theme.kt` / `Type.kt` | Tokens, M3 scheme (dynamic color on Android 12+), typography |
| `StudioModels.kt` / `SidebarTree.kt` | Layer/project/aspect/torch/audio/export/stats models |
| `StudioViewModel.kt` | All state, defaults (sidebar open; sources+record expanded; startup dialog on), toasts |
| Manifest / res | Permissions (camera, mic, storage ≤28), `Reaction Studio` label, launcher icons, `ic_reaction_studio_logo.jpg` |

---

*End of master report. See `blueprints/` for per-area wireframes + pixel specs, and `mockup/` for the
clickable carbon copy (open `mockup/index.html`).*
