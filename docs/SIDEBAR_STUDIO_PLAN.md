# Sidebar Studio — Reference Audit & Migration Plan

**Date:** 2026-09-08
**Scope:** audit of `frontend ref/` + the practical plan for moving the editor chrome
from **radial rings → sidebar panel**, re-themed to **this app's palette** (charcoal +
studio orange), with a hard fit bar: **no cropped buttons, no overlapping panels, no
clipped canvas** (UI Plan2 Rule 8).

Companion artifact: **`frontend ref/mockup-side/`** — a clickable, re-themed mockup of the
proposed studio (open it in the live preview; original cyan mockup stays at
`frontend ref/mockup/` for side-by-side comparison).

---

## 1. What `frontend ref/` actually contains (audit)

| Item | Size | What it is | Verdict |
|---|---|---|---|
| `REPORT.md` | 24 K | Master report: 11 surfaces (S0–S11), 9 dialogs (D1–D9), 30 toasts, exact tokens, visibility matrix, test tags, file→UI map. Derived from ~6,900 lines of the *other* repo's source. | **Keep.** Best single description of the target IA. |
| `blueprints/00–05` | 44 K | Per-area pixel specs (shell, top strip, sidebar, stage, dialogs, tokens/flows). | **Keep.** The sidebar blueprint (02) is the spec we adopt. |
| `mockup/index.html + css + js` | 96 K (1,251 lines) | Clickable carbon copy of the reference app, incl. demo harness (4 views, 9 dialogs, sample layers, HUD, screen light, toasts). | **Adopt the pattern, re-theme, fix the fit bugs (§4).** |
| `assets/app-logo.jpg` | 660 K | The *other* app's neon "AMS" aperture logo. | **Discard.** This app has its own splash/branding. |

**Reference app identity (for the record):** `Reaction Studio`, Compose M3, dark-only,
cyan `#38BDF8` primary, single-activity, one studio screen + dialogs, sidebar 240 dp
(landscape) / 260 dp (portrait), 8 sections, 38+ actions.

### 1.1 Strengths to adopt

1. **The sidebar information architecture.** Eight collapsible sections; the
   *SOURCE CONTROLS* section re-renders for the selected source — this is the real win
   over the ring: the ring costs 2–4 taps for a transform tweak, the sidebar costs 1.
2. **Row grammar** (blueprint 02): 44 dp rows, 13 sp one-line ellipsis labels, 16 dp
   icons, badges, danger rows, *disabled-with-reason* rows. Matches this app's Rule 5.
3. **Top-strip model:** hamburger · title ✎ · aspect chip · undo/redo · save-state pill
   (dirty amber / clean green) · export · full-canvas · overflow.
4. **Mode matrix** (§10 of REPORT): Normal / sidebar-closed / Full Canvas / Immersive —
   an explicit per-surface visibility table we can code against and test.
5. **Dialog language** (D1–D8): aspect option-cards, 3-state export progress, text
   editor with colour dots, diagnostics groups.
6. **Test tags** for automation hooks.

### 1.2 What is NOT practical to copy (the problems)

**A. Wrong palette.** Cyan `#38BDF8` primary vs this app's `UI` tokens — see the
re-theme map in §3. Every cyan must become orange; selection washes become
`argb(55, 255, 90, 44)` (the current app's own selection colour).

**B. Verb sprawl — the exact disease this repo already cured.** The reference sidebar
re-introduces duplicated verbs that UI Plan2 §4 explicitly forbids:

| Duplicated verb in reference | Occurrences | Ruling for this app |
|---|---|---|
| Snapshot Frame | RECORD **and** PROJECT | RECORD only |
| Screen Capture / Add Front Camera | RECORD **and** Add submenu | Add submenu only |
| Undo / Redo | top strip **and** PROJECT | top strip only |
| Export Quality Settings… | EXPORT **and** SETTINGS | EXPORT only (merge SETTINGS away) |
| Show/Hide Stats Overlay | overflow **and** PROJECT | overflow only |
| "Selection → Background" vs "Set as Background" | CANVAS **and** SOURCE | one name, SOURCE section |
| Undo/Redo/Save in PROJECT section | — | save-state lives in the top strip |

**C. Feature set mismatch.** The reference ships sample media (Sample Video Clip /
Sample Image Sticker), aspect-change **auto-rotate**, 4K export chip, master-volume +
mic-gain mixer rows, and a D9 "Save vs Export" explainer. This app has none of those
today (device-filtered export, picker-based aspect per T-08, masterGain is
recording-only with no UI per T-14). Copying them = dead buttons (Rule 5 violation).

**D. Fit bugs in the reference mockup itself** (verified in `mockup/css/studio.css`):

1. **Top strip overflows < ~560 px wide.** Fixed-width cluster (hamburger 40 + chip
   ~64 + undo/redo 72 + save ~100 + export ~92 + ⛶ 36 + ⋮ 36 ≈ 440 px) + title
   `max-width:32%` with no wrap/scroll → the overflow menu gets clipped on a narrow
   phone. *Fix: title is the only shrinking element (min-width 0), save pill drops to
   icon+dot below 640 px, export drops to icon below 560 px (JS `narrow` class).*
2. **Quick bar collides with the workspace pill on narrow stages.** qbar is
   `left:50%` with ~330 px of content (Auto Fill + Fit + Center + 3 icons); wpill is
   `right:10px` ~110 px wide. At stage width < ~480 px they overlap. *Fix: qbar is
   icon-only (6 × 30 px ≈ 226 px max) + wpill compacts to mini at < 520 px stage.*
3. **`.sidebar.hidden{margin-left:-260px}`** is hard-coded while the width becomes
   220 px at `max-width:900px` → the slide-out animation ends 40 px off. *Fix: use
   `margin-left:calc(-100% - 8px)` / transform on the element's own width.*
4. **Bottom-right transport vs timeline** on a 9:16 canvas in a landscape stage:
   the 54 dp record button can sit over the canvas edge. *Fix: adaptive inline mode —
   when the canvas leaves < 120 px of backdrop on the right, stop/record icons move
   *into* the timeline pill (one bottom surface, no overlap, no clipping).*
5. **Raw `<Button>`s in the current app's top/transport bars** (Android 48 dp
   min-height) are the known "cropped buttons" cause (documented in
   `StudioLayoutInjector.pillBtn`). *Fix: keep the pill-TextView pattern — explicit
   height, zero min-height, one shared `pillBtn` helper.*

**E. Naming drift.** "Sources" (list) vs this app's dictionary word **Layers**;
"Save Draft" vs **Save**; "Export MP4" vs **Export**; "AMS" splash vs
**Ahmed Reaction Studio**. The mockup below uses this app's names.

---

## 2. The target studio (what we build)

```
┌────────────────────────────────────────────────────────────────────┐
│ ☰ │ Ahmed Reaction Studio ✎ │ 16:9 ▾ │  ↷ │ ● Saved │ Export │ ⛶ │ ⋮ │  top strip 48 dp
├──────────┬─────────────────────────────────────────────────────────┤
│ LAYERS   │        ┌─ quick bar (on selection) ─        ⛶       │
│  ▸ rows  │        │  👁  🔇  ⏯    ⤢        │                  │
│ SOURCE   │  ┌─────┴────────────────────────────┴─────┐            │
│ AUDIO    │  │                                          │  [HUD]    │
│ RECORD   │  │              CANVAS (contain-fit)        │            │
│ CANVAS   │  │                                          │            │
│ EXPORT   │  └──────────────────────────────────────────┘            │
│ PROJECT  │        [▶ 0:00 / 1:36 ━━━━━●━━━━━]   [⏹] [⏺]            │
└──────────┴─────────────────────────────────────────────────────────┘
 sidebar 240 dp (L) / overlay 250 dp (P)
```

### 2.1 Top strip (48 dp, `BG2` @ 92 %, 1 px bottom hairline)

| # | Control | Size | Notes |
|---|---|---|---|
| 1 | Hamburger ☰ | 40 dp | toggles sidebar; active = `ACCENT` 18 % bg + accent icon |
| 2 | Project title + ✎ | flex, **min-width 0**, max 30 %, 1-line ellipsis | only shrinking element; tap → Rename dialog |
| 3 | Aspect chip `16:9 ▾` | pill 30 dp, `BG3` | tap → **picker** (never blind cycle, T-08), change is undoable |
| 4 | Undo / Redo | 36 dp | disabled = `MUTED` @ 40 % |
| 5 | Save-state pill | 30 dp | dirty: `ACCENT2` border/tint + amber dot "Save" · clean: green dot "Saved" |
| 6 | Export | 30 dp filled `ACCENT`, black 11 sp ExtraBold | quick-export with persisted settings (T-04); icon-only below 560 px |
| 7 | Full Canvas ⛶ | 36 dp | accent when active |
| 8 | Overflow ⋮ | 36 dp | Full Screen Canvas · Show/Hide Stats Overlay · Diagnostics |

### 2.2 Sidebar sections (240 dp landscape · 250 dp portrait overlay)

Eight → **seven sections** (SETTINGS folded into EXPORT/PROJECT). The radial's 7 root
petals map 1:1 — a clean retirement story:

| Radial petal (today) | Sidebar section (new) | Contents |
|---|---|---|
| Sources | **LAYERS** (badge = count) | layer rows (46 dp: type icon in layer accent, name, 👁, 🔊, ↑, ↓) · **Add ▸** (Video… / Image… / Front Camera / Back Camera / Screen Record / Text Overlay) · Remove Selected (danger) · Duplicate Selected |
| Add | (folded into LAYERS ▸ Add) | — |
| (advanced sheet) | **SOURCE** (badge = selected name) | Show/Hide · Lock Position · Pause/Resume · **Fit Mode ▸** (Fill / Fit, active state) · **Transform ▸** (Auto Fill 100 % · Fit Inside 90 % · Center · Stretch W/H · Width/Height sliders 10–150 % · 4 corners · Rotation slider −180…180 + ↺90 / ↻90 / Reset) · Opacity slider · Set as Background · Advanced Properties… (name + speed) · Text… (text layers) |
| Audio | **AUDIO** | Mixer… (dialog) · when selected: Volume slider (one, V07) + % · Mute · Solo · Loop |
| Light | (folded into RECORD ▸ Light) | Off · Front Torch · Back Torch · Both · Screen Light — **capability-aware, disabled-with-reason** (T-20) |
| Canvas | **CANVAS** (badge = ratio) | Aspect ▸ (16:9 / 9:16 / 1:1, active state, no auto-rotate) · Background ▸ (7 dots) · Full Canvas toggle · Fit All Sources |
| Export | **EXPORT** | Quick Export (persisted settings) · Export Settings… · Repeat Last Export |
| Project | **PROJECT** | Rename Project… · Save Now · Diagnostics |

**Default expanded:** LAYERS + RECORD (mirrors the reference's startup state and this
app's two hottest verbs).

### 2.3 Stage & floating chrome

- Backdrop `#040507` (tap = deselect) · canvas contain-fit, 6 dp radius, 1 dp white-15 %
  border, 16 dp shadow, bg = canvas colour. Insets: 10 dp sides / 10 dp top / **64 dp
  bottom** (room for the timeline) — **0 in immersive**.
- **Quick bar** (on selection, top-center): icon-only pills — 👁 hide · 🔇 mute ·
  ⏯ pause/resume · 🔒 lock · ⤢ fit/fill · 🗑 delete. Camera layers append ⏺ take + 🔄
  facing (the T-21 camera toolbar). Max content ≈ 260 px → fits any phone stage.
- **Workspace pill** (top-right): `⛶ Full Canvas | 👁` → compacts to icons below
  520 px stage; in Full Canvas = `Exit | 👁`; immersive = eye + exit glyphs only.
- **Timeline pill** (bottom-center, 44 dp): play/pause + `m:ss / m:ss` + seek + REC dot.
- **Transport** (bottom-right, above timeline): ⏹ 44 dp · ⏺ 54 dp (idle = red 20 % +
  ring; recording = solid, white ring, pulse). **Inline fallback:** when the canvas
  leaves < 120 px of backdrop on the right, these two icons move into the timeline
  pill's right end (one surface, no overlap).
- **REC pill** (below workspace pill) · **HUD** (top-left, toggle) · **hidden pill**
  ("N hidden · show", existing recovery affordance) · **screen-light** white 85 %
  overlay · empty-state (frosted tile + "Your canvas is ready" + CTA → Add).

### 2.4 Mode matrix (the visibility contract)

| Surface | Studio | Sidebar closed | Full Canvas | Immersive |
|---|---|---|---|---|
| Top strip | ✅ | ✅ | ✅ | ❌ |
| Sidebar | ✅ (open) | ❌ | ✅ | ❌ |
| Timeline / transport | ✅ | ✅ | ✅ | ❌ (REC pill only) |
| Canvas insets | 10/10/64 | 10/10/64 | 10/10/64 | none |
| Quick bar | ⟺ selection | ⟺ selection | ⟺ selection | ⟺ selection |
| Back button | closes sidebar | exits app | exits FC (recording survives) | restores chrome first |

**Recording rule (unchanged, Rule 5 of Plan2):** Back never stops a recording;
`● REC mm:ss` shows in the timeline + REC pill; Full Canvas and immersive keep it alive.

### 2.5 Dialogs (kept from reference, de-sprawled)

| D | Dialog | Entry | Notes for this app |
|---|---|---|---|
| D1 | Canvas format (3 option cards) | aspect chip / CANVAS ▸ | no auto-rotate; undoable |
| D2 | Audio mixer | AUDIO ▸ Mixer | per-layer slider + mute + solo + **VU bars** (OBS-style, per the attached reference images); **no** master/mic-gain rows |
| D3 | Export settings | EXPORT | 720p/1080p (+4K if device), 24/30/60, H.264/H.265/VP8/VP9 device-filtered, destination |
| D4 | Export progress | D3 / quick | rendering (no dismiss) / completed / failed — 3 states |
| D5 | Advanced properties | SOURCE ▸ | name + speed chips (opacity & volume live one level up in the sidebar) |
| D6 | Text editor | Add ▸ Text / SOURCE ▸ Text / double-tap | content, size 16–42, 7 colour dots (re-themed), shadow switch |
| D7 | Rename project | title ✎ / PROJECT | — |
| D8 | Diagnostics & codecs | overflow / PROJECT | — |
| ~~D9~~ | Save vs Export explainer | — | **not built** — feature doesn't exist here; no dead buttons |

---

## 3. Re-theme map (reference token → this app's token)

Reference cyan theme → this app's `UI` object (`util/Util.kt`), **no new colours**:

| Reference | Hex | → This app | Hex | Used for |
|---|---|---|---|---|
| `--dark` app bg | `#0E1016` | `UI.BG` | `#101218` | app, sidebar, top strip base |
| `--surface` | `#161922` | `UI.BG2` | `#1B1E26` | top strip, row hovers |
| `--elevated` | `#1F2432` | `UI.BG3` | `#262A34` | chips, dialogs, expanded headers |
| `--border` | `#2E3547` | `argb(70,255,255,255)` | — | hairlines |
| `--cyan` primary | `#38BDF8` | `UI.ACCENT` | `#FF5A2C` | primary buttons, selection, active rows, qbar |
| `--amber` | `#F59E0B` | `UI.ACCENT2` | `#FFA02C` | dirty/save, rotation, lock, front cam, sliders-alt |
| `--red` | `#EF4444` | `UI.DANGER` | `#EB5A5A` | record, delete, REC, back cam |
| `--green` | `#10B981` | `UI.OK` | `#46D282` | saved dot, image layers, live, VU low |
| `--purple` (screen) | `#818CF8` | `#7C93C4` (slate) | — | screen layers only (semantic, not brand) |
| `--t1/--t2/--muted` | `#F1F5F9/…` | `UI.FG / FG2 / #64748B` | — | text |
| stage backdrop | `#07080B` | `#040507` | — | canvas container (current app value) |
| selection wash | `#244878` | `argb(55,255,90,44)` | — | selected layer row / chrome (current app value) |

Layer type accents (sidebar rows + canvas badges): front cam `#FFA02C` · back cam
`#EB5A5A` · video `#FF5A2C` · image `#46D282` · screen `#7C93C4` · text `#FFD166`.

---

## 4. Fit rules — the "no crop, no overlap" bar (enforced in code + tested)

1. **One shrinking element per row.** Every bar row has fixed-size controls; exactly
   one flex child with `min-width:0` + 1-line ellipsis (top-strip title; section labels
   already ellipsize). No raw `Button` widgets in 30–48 dp bars (Android's 48 dp
   min-height is the historical crop bug) — only the `pillBtn` TextView pattern with
   explicit height.
2. **Fixed row heights:** section header 44 dp · sub-menu header 42 dp · action row
   44 dp · layer row 46 dp · sliders inside rows get 44 dp touch targets (thumb 24 dp
   min per T-09). Touch targets ≥ 44 dp everywhere (T-33), 48 dp for primary.
3. **Width budgets:** top strip fixed cluster ≤ 440 dp; sidebar 240/250 dp; qbar
   ≤ 260 dp content; timeline `min(85 %, stage − 120 dp)`; transport needs 120 dp of
   backdrop else **inline mode** (§2.3).
4. **Z-order:** canvas < selection chrome < pills (qbar/timeline/transport/wpill) <
   HUD < dialogs < screen-light < toasts. Pills are `position: floating` over the
   *backdrop*, never stretched across the canvas edge they'd cover.
5. **Canvas reserve:** chrome (top strip + bottom pills) may never push the contain-fit
   canvas below its aspect-correct size (Rule 8). Full Canvas/immersive = 0 insets.
6. **Degradation ladder (narrow → wider):** save label → icon · export label → icon ·
   wpill → mini · transport → inline. Nothing is ever clipped; if a control can't fit
   its minimum, it moves, it doesn't crop.
7. **6-cell matrix** (Plan2 §9): every change verified in {portrait, landscape} ×
   {16:9, 9:16, 1:1}: no clipped canvas, no clipped controls, no overlapping panels,
   no unreachable button.

---

## 5. What stays untouched (Rule 2 — pipeline-safe)

- `StageView` canvas gestures (drag / corner+edge resize / rotation knob / snap
  guides / selection chrome) — the mockup intentionally omits gestures; they're
  already built and correct.
- `PreviewEngine` clocks, `Compositor`, `MediaKit`, `AudioMixer`, export/recording
  pipelines, `TorchController` capability model.
- `SourceController` verb surface — the sidebar calls the **same verbs** the ring did;
  no new mutation paths.
- Quick bar + hidden pill + transport/timeline + full-canvas state machine (restyled,
  not rebuilt).

## 6. Implementation phases (Kotlin, View-based; one task per commit, Rule 1+6)

- **P1 — Shell.** `StudioLayoutInjector`: landscape = top strip + **left sidebar
  (240 dp)** + stage (retire the right tab panel); portrait = canvas-first + sidebar as
  overlay (hamburger). Back behaviour per §2.4. Radial + dock keep working during the
  phase (no regression).
- **P2 — LAYERS + SOURCE sections.** Retire in the same commits: sources sheet,
  advanced sheet, dock long-press ring. Layer rows reuse `SourceDock` row rendering.
- **P3 — AUDIO / RECORD / CANVAS / EXPORT / PROJECT sections.** Each retires one or
  more ring petals in the same commit (Audio→mixer sheet, Light→flash ring, Canvas→
  aspect ring, Export→ring, Project→ring). Mixer dialog with VU bars.
- **P4 — Kill the radial.** `RadialWheel`/`RadialMenus` deleted (or `◉` kept as a
  shortcut to the sidebar per "the ring is a shortcut, not the navigation"); dock
  strip deleted (LAYERS section is its home); coach marks updated (T-28 wording).
- **P5 — Polish pass.** 44/48 dp targets, focus order, reduced-motion, the §4 fit
  rules codified in one `fitRules` check, 6-cell matrix screenshots into `artifacts/`.

**Definition of done** = Plan2 §7 (duplicate *deleted*, canonical ≤ 2 taps,
preview == export, build + device test, report appended).

---

## 7. Mockup notes (`frontend ref/mockup-side/`)

- Interactive study of §2, **this app's palette**, this app's names (Layers / Save /
  Export / Ahmed Reaction Studio), sample layers pre-loaded (front cam PiP + video +
  reaction text).
- Harness: Landscape/Portrait · Studio/Full Canvas/Immersive · all 8 dialogs ·
  sample-layers/HUD/screen-light toggles · toast button.
- Implements the §4 degradation ladder (resize the window narrow to watch the save pill
  lose its label, the workspace pill mini, and the transport go inline).
- Deliberately **not** in the mockup: canvas drag/resize gestures (existing
  `StageView`), real camera/decode (Rule 2), D9, sample media, auto-rotate.

---

## 8. Implementation report — 2026-09-08 (applied, all phases P1–P5 in one change)

User override applied: **canvas = 100% of the screen, adaptive; every control
floats OVER it** so the full composition stays visible while recording.

### What shipped

| Area | Change |
|---|---|
| `StudioLayoutInjector.kt` | Fully rewritten: full-bleed `stage` (MATCH_PARENT, contain-fit of the whole safe area) + floating top strip (48dp: panel · project title/meta · aspect chip · undo/redo · Save state pill · Export · Full canvas · more) + floating **sidebar** (left overlay, 244dp landscape / 256dp portrait) with the 7 sections of §2 + floating quick bar (scrollable, never crops) + single-row timeline pill (play · time · seek · stop · **RECORD**) + chip row (hidden pill / rec chip / HUD) + empty-state card + snack bar + progress overlay. |
| Top-strip fit ladder (§4) | 3 tiers: labelled → icon-only Save/Export → aspect chip moves into the overflow menu. Title box is the only flex element and ellipsises. Recomputed on every layout pass. |
| Timeline | Transport and RECORD live in ONE pill (86% width, max 760dp, landscape; 94% portrait), so nothing can collide with it; seek is its flex element with a 36dp floor; the record verb is WRAP with a 104dp floor so "■ STOP & SAVE" never clips. |
| Sidebar sections | **Layers** (the `SourceDock` rows with drag-to-reorder + Add list + Duplicate/Delete), **Source** (full per-source controls: appearance/opacity, playback/mute/solo/volume, camera take/switch/mirror + Light rows, text edit/colour/size/shadow, arrange/anchors/background), **Audio** (per-clip M/S + volume), **Record** (play/stop/snapshot/restart + Light + setup reason), **Canvas** (aspect, 7 backgrounds, full canvas, fit all), **Export** (export-with-saved-settings + settings dialog), **Project** (rename/save/diagnostics/close). Sections collapse; state survives rotation/re-inject. |
| `EditorActivity.kt` | `RadialMenus.Host` impl removed (interface gone); `openAdvancedSheet`/`setSheet`/tabs/transport bar/side rail/source strip deleted; `onLongPressCanvas` → select + open SOURCE section (empty canvas → LAYERS); back = Full Canvas → close sidebar → exit; `applyViewportInsets` now passes **only system insets** (chrome never shrinks the canvas); new `refreshAll` funnel; quick bar + empty state + hidden pill ("N hidden", tap restores all, single undo) implemented (previously dead stubs); snack bar + progress overlay are now actually built (previously dead); export settings dialog (resolution → fps → codec, sticky prefs) implemented; quick export reuses the saved settings (T-04); `deleteSourceSafely` re-added (rollback follow-up #2) and all delete surfaces route through it. |
| Retired (same commit, Rule 1) | `RadialWheel.kt`, `RadialMenus.kt`, `SourcesPanel.kt`, `MixerPanel.kt`, `ControlsPanel.kt`, `PropertiesPanel.kt`, `EffectsPanel.kt`. `SourceDock.kt` kept — it is the LAYERS section's row engine. |
| Engine / record / export | **Zero changes** (Rule 2). |

### Deliberate deferrals (documented, not silent)

- **VU level bars** in the AUDIO section (mockup D2): `PreviewEngine` exposes no
  per-clip level signal; adding one is an engine change and would need its own
  review. The section carries the full mixer contract (M/S/volume per clip).
- **Coach-mark wording** (T-28): there is no coach-mark code in the current tree
  to update.
- **6-cell matrix screenshots** into `artifacts/`: need a device; see below.

### Validation

| Check | Result |
|---|---|
| `bash tools/ci-toolchain.sh` | toolchain ready (JDK17 + kotlinc 1.9.24 + android jars + aapt2 + d8 + apksigner) |
| baseline `bash build-apk.sh` (before edits) | BUILD OK |
| `bash build-apk.sh` (after all edits) | **BUILD OK** — `artifacts/AhmedReactionStudio-1.0.0.apk` |

### Still requires a phone (Rule 7)

Portrait + landscape; no sources / one source / many; selected vs unselected;
recording with the sidebar open and in Full Canvas; a 360dp-wide phone for the
tier-2 top strip; drag a volume slider (must not reset mid-drag); undo/redo a
layer delete while the camera is live.
