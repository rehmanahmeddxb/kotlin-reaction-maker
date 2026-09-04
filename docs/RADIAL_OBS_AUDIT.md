# Audit → Changes → Re-audit: radial OBS interface + camera on canvas

Requested by the user, in their words:

> "Sources, Add and other tabs are still buttons. The radial panel system only
>  deletes and hides the source. I want it to work like I tell you: when I click
>  the radial wheel it shows petals — Sources, Controls, Dock, Mixing etc, all
>  the OBS-like interface but in radial style; and if a petal is clicked it shows
>  sub-petals, and clicking those does the action. Also the camera: when
>  selecting it, it shows a strange interface, while we want the camera added in
>  the canvas — the canvas must be available there."

---

## PART 1 — AUDIT OF THE CODE AS IT WAS (commit b418029)

### 1.1 The bottom bar was still a button/tab UI

`EditorActivity.buildSheet()` built a literal 4-button tab row:

```kotlin
val defs = listOf(
    "sources" to ("Sources" to R.drawable.ic_layers),
    "add"     to ("Add"     to R.drawable.ic_add),
    "canvas"  to ("Canvas"  to R.drawable.ic_aspect),
    "export"  to ("Export"  to R.drawable.ic_export))
for ((key, ln) in defs) { … t.setOnClickListener { onTab(key) } … }
```

and each panel behind those tabs was **another** grid of rectangular text
buttons (`panelButtonRow(...)` → `"🎬 Video file"`, `"🖼 Image file"`,
`"16:9"`, `"Dark"`, `"Navy"`, `"⬆ To front"`, …). So the app's whole
navigation was buttons-in-panels — exactly what the user says is still there.
The radial wheel was a decoration bolted on the side, not the interface.

### 1.2 The radial wheel was single-level and source-scoped only

`RadialWheelView` (246 lines) could show **one** ring of petals and nothing
else:

* `show(hubIconRes, name, petals, ax, ay)` — one flat list, no hierarchy.
* Every petal tap ended with `pt.action(); dismiss(true)` — the wheel always
  closes, so a petal could never *open* anything.
* `fun show(...) { if (open) return … }` — re-entrant calls are rejected, and
  `dismiss()` finishes asynchronously (`postDelayed(…, 320)`). Even if a petal
  action had called `show()` again, the second ring would have been dropped.
  **Sub-petals were architecturally impossible.**
* The only entry point was `wheelBtn` inside the Quick Control Bar
  (`refreshQuickBar()`), which only exists **when a source is already
  selected**. With nothing selected there was no wheel at all.
* Petal sets were per-source verbs only: Play/Pause, Mute, Loop, Hide, Lock,
  Fit, Duplicate, Delete (+ Edit/Color for text). Hence the user's "it only
  deletes and hides the source" — Sources, Add, Dock, Mixer, Canvas, Transport,
  Export and Project were **never** reachable from the wheel.

### 1.3 Camera = a separate fullscreen activity, canvas gone

`openCamera()` did:

```kotlin
val i = Intent(this, CameraActivity::class.java)
startActivityForResult(i, REQ_CAMERA)
```

`CameraActivity` (650 lines) is a portrait-locked, fullscreen Camera2 screen
with its own chips (`Camera`, `Flash`, zoom slider, `●` record, `00:00`
timer) — the "strange interface". The editor canvas is *not* on screen, so you
frame your reaction blind, and only after the take is finished does a clip come
back through `onActivityResult` → `consumeMediaFile(...)`.

The plan document even conceded this (`docs/OBS_SOURCE_PLAN.md` §9):

> "No live camera *inside* the canvas yet (camera takes are recorded clips)."

There was **no live camera layer type at all**: `PreviewEngine` only decodes
frames from `relPath` files (`requestFrames()` skips
`l.relPath.isNullOrBlank()`), so nothing could ever put a live feed on the
stage.

### 1.4 Smaller findings

| # | Finding | Impact |
|---|---|---|
| a | `RadialWheelView` reused single `hubBg/hubIcon/hubName` view instances across `show()` calls while `removeAllViews()` ran — fine for one level, unusable for a stack. | blocks nesting |
| b | No paging: `radius = max(118dp, (petal+12dp)*n / 2π)`. A 12-source ring would grow off-screen. | breaks with many sources |
| c | `PreviewEngine.setFrame()` exists (an "external frame" hook) but `publish()`, `evict()` and `recycleFrames()` recycle **every** bitmap in `frames`, so an externally-pushed live frame would be recycled under the producer. | crash risk for live camera |
| d | `PreviewEngine.anyPlaying()` counts any `isVideoLike() && playing` layer; a live camera layer would spin the master clock and the seek bar forever. | wrong transport state |
| e | Export: a source with no `relPath` silently renders nothing — a live camera would export as a hole with no warning. | silent bad export |
| f | `Layer` had no notion of `live`, camera facing or mirroring. | no model support |

### 1.5 Verdict

The V1 "OBS plan" delivered the *state model* (fit/loop/solo, SourceController,
undo) correctly, but of the *interaction* plan it delivered only a single
per-source ring. The navigation is still tabs-and-buttons and the camera is
still a modal screen. Both user complaints are accurate.

---

## PART 2 — WHAT WAS CHANGED

### 2.1 `RadialMenuView` — a real nested radial menu (rewrite of `RadialWheel.kt`)

```kotlin
class Item(icon, label, active, danger, badge,
           submenu: (() -> Level)?,     // folder petal → sub-petals
           action: (() -> Unit)?,       // leaf petal → does the thing
           keepOpen: Boolean)           // toggles keep the ring open + refresh

class Level(icon, title, subtitle, items: () -> List<Item>)
```

* **Unlimited depth.** A folder petal pushes a `Level` on an internal stack,
  animates the old ring into the hub and blooms the new one. The hub turns into
  a back button (`‹ Back to …`), the scrim closes everything, hardware Back
  pops one level.
* **Live state.** `items` is a *lambda*, evaluated every time a ring is drawn,
  so a toggle petal (`keepOpen = true`) re-renders the ring with the new state
  instead of closing (mute → the petal instantly shows "Unmute" in red).
* **Paging.** Rings hold 8 petals; overflow becomes a `More…` petal that pages
  through and wraps. 30 sources are navigable without the ring leaving the
  screen.
* **Badges** (e.g. `HIDDEN`, `MUTED`, `LIVE`) render on the petal.
* Same premium feel as before: staggered overshoot bloom, hub spring, haptic +
  flash on tap, scrim fade.

### 2.2 `RadialMenus.kt` — the whole OBS interface expressed as rings

`RadialMenus.root(host)` builds the tree the user described:

```
◉ STUDIO
├── Sources     → one petal per source (badges: HIDDEN/MUTED/LOCKED/LIVE)
│                  └── per-source ring: Select, Hide/Show, Mute, Play/Pause,
│                        Lock, Fit/Fill, Loop, Solo, Center, Duplicate,
│                        To front / To back, Advanced…, Delete
│                        (camera-live sources also get: Switch cam, Mirror,
│                         Record take / Stop take)
├── Add         → Video file, Image, Camera (live on canvas), Screen record, Text
├── Controls    → Play/Pause all, Restart, −10 s, +10 s, Snapshot frame,
│                  Undo, Redo
├── Dock        → Open source dock, Advanced sheet for selection, Select
│                  next/previous, Z-order shuffle
├── Mixing      → Master mute, per-source Mute/Solo petals, Volume −/+,
│                  Clear all solos, Open mixer panel
├── Canvas      → 16:9 / 9:16 / 1:1, background colours, Fit all sources,
│                  Set selection as background
├── Export      → Quick 720p30 export, Export settings…, Codec picker
└── Project     → Rename, Save now, Diagnostics, Close project
```

Every leaf calls a `SourceController` verb or an editor callback, so undo/redo
and preview==export still hold by construction (plan §1/§6 unchanged).

### 2.3 The editor is radial-first

* The 4 tab buttons are **gone**. The bottom bar is now: transport (play, time,
  scrubber, duration) + one **`◉ Studio`** radial launcher.
* The launcher is always available (no selection needed) — that was the biggest
  discoverability hole.
* Long-press on empty canvas also opens the root ring at the finger.
* Sheets survive only where a ring is the wrong tool (things with sliders and
  pickers): the source dock, the mixer, export settings and the per-source
  advanced sheet. They are opened *from* petals.
* The Quick Control Bar's `◉` now opens that source's ring directly (depth 1),
  and `⋮` still opens the advanced sheet.

### 2.4 Camera lives on the canvas

New `editor/LiveCamera.kt` — a Camera2 `ImageReader` (YUV_420_888) feed:

* frames are converted to ARGB **with rotation and front-camera mirroring baked
  into the index math** (no per-frame `Matrix` allocation), throttled to ~24 fps,
  double-buffered into two reusable bitmaps;
* the bitmap is pushed into `PreviewEngine.setFrame(layer, bmp)` and therefore
  **composited by the same `Compositor` as every other source** — z-order,
  opacity, fit/fill, drag, resize, rotate, snap guides all work on it;
* `Layer` gained `live`, `camFacing`, `mirror` (persisted), so a live camera is
  an ordinary source in the dock, the mixer and the radial menus.

Flow now: **Add → Camera (live on canvas)** drops a live PiP straight onto the
stage; the canvas never disappears. Framing happens *in the composition*.
`Record take` (radial petal, or the REC chip) records that camera to
`media/cam_*.mp4` via a second `MediaRecorder` target and, when you stop, swaps
the live layer into a normal clip layer **in place** — same geometry, same
z-order, same name — so it is instantly exportable.

Fallbacks: if a device rejects the 2-target session, recording falls back to a
recorder-only session and the canvas holds the last live frame; if Camera2
cannot open at all, the old fullscreen `CameraActivity` is offered as
`Add → Camera → Fullscreen take (fallback)`.

### 2.5 Supporting fixes (from §1.4)

| # | Fix |
|---|---|
| c | `PreviewEngine` now tracks `externalIds`; `publish`/`evict`/`recycleFrames` never recycle a frame owned by the live camera. |
| d | `anyPlaying()` and `endOfMediaCheck()` skip live layers, so the transport and the seek bar stay honest. |
| e | The export panel refuses to silently drop a live camera: it warns and offers "Record a take first". |
| f | `Layer.live / camFacing / mirror` added to the model, JSON and `clone()`. |
| b | Ring paging (§2.1) removes the off-screen-radius failure mode. |

---

## PART 3 — RE-AUDIT (after the change)

Method: rebuild the APK from scratch with the offline toolchain, then re-check
every claim of Part 1 against the code that is actually in the tree (a
36-assertion script, not eyeballing). Build result:

```
[1/7] aapt2 resources + generated R class
[3/7] compile Kotlin        → no errors
[4/7] dex with d8
[6/7] sign (v1+v2)
[7/7] verify                → Signer #1 CN=Ahmed Reaction Studio
artifacts/AhmedReactionStudio-1.0.0.apk   1 057 914 bytes
BUILD OK
```

### 3.1 Each Part-1 finding, re-checked

| Finding (Part 1) | State now | Evidence |
|---|---|---|
| §1.1 tabs + button panels | **fixed** | `tabBtns`, `onTab`, `highlightTab`, `buildAddPanel`, `buildCanvasPanel` no longer exist anywhere in `app/src`. The bottom bar is transport + one `◉ Studio` launcher. |
| §1.2 wheel single-level | **fixed** | `Item.submenu`, `push()`, `pop()`, a level stack, hub-as-Back, and `keepOpen` toggles that re-render instead of closing. |
| §1.2 wheel needed a selection | **fixed** | `openRootWheel()` reads no selection; it is reachable from the always-visible launcher, from the empty state, and from a canvas long-press. |
| §1.2 only hide/delete reachable | **fixed** | 8 root petals (Sources, Add, Controls, Dock, Mixing, Canvas, Export, Project), 13 level builders, 90 petal definitions. |
| §1.3 camera = modal screen, canvas gone | **fixed** | `addLiveCamera()` creates a live CAMERA layer; `LiveCamera` pushes frames to `PreviewEngine.setFrame` and the shared `Compositor` draws them on the stage. The fullscreen activity remains only as an explicit fallback petal. |
| §1.4a hub views reused | fixed | the hub is rebuilt per ring inside a dedicated `ring` container. |
| §1.4b radius grows off-screen | fixed | 8-per-page rings with a wrapping `More n/m` petal. |
| §1.4c external frames recycled | fixed | `externalIds` guards `publish`, `evict` and `recycleFrames`. |
| §1.4d live layer drove the clock | fixed | `PreviewEngine` now keys on `isClip()`; `isVideoLike` no longer appears in it. |
| §1.4e silent hole in exports | fixed | `warnLiveBeforeExport()` on both the quick and the settings export path. |
| §1.4f no model support | fixed | `live` / `camFacing` / `mirror` in the ctor, `toJson`, `fromJson` and `clone()`. |

### 3.2 New issues found *during* the re-audit (and fixed)

Re-auditing was not a formality — it surfaced three real defects that the
change itself had introduced or left open:

1. **Orphaned camera hardware.** Deleting the live layer through the Delete
   petal, the advanced sheet or **undo/redo** removed the layer but left
   `LiveCamera` holding the camera open — it would stay locked for every other
   app until the editor was destroyed. Conversely, *undo* could resurrect a
   live layer with no feed behind it: a dead frozen rectangle.
   → One `reconcileLiveCamera()` reconciler, called from `onSourceChanged()`,
   `afterStructureChange()` and `onResume()`, now keeps hardware and layer list
   in sync regardless of which path mutated the project. No call site has to
   remember.
2. **`View.cancelLongPress()` collision.** The new stage long-press helper was
   named `cancelLongPress`, silently weakening the access of an existing
   `View` method — a compile error, and a subtle override bug had it compiled.
   → Renamed `cancelRadialLongPress()`.
3. **`Layer.clone()` drift.** `clone()` passes all 32 constructor arguments
   positionally, so inserting `live/camFacing/mirror` in the wrong slot would
   silently copy the camera flag into `text`. Verified by script: 32 ctor
   params vs 32 clone args, zero positional mismatches.

Also fixed while re-reading: the nullable-bitmap path in the YUV converter, and
`it`-shadowing inside the petal click listener (which had bound the *View*
rather than the *Item* — every petal would have fired the wrong handler).

### 3.3 Assertion run

```
A. Tabs/buttons replaced by radial ................ 4/4 PASS
B. Nested petals → sub-petals → action ............ 8/8 PASS
C. Menu covers the OBS interface .................. 10/10 PASS
D. Camera lives on the canvas ..................... 7/7 PASS
E. Correctness fixes from the first audit ......... 7/7 PASS
                                                    36 passed, 0 failed
```

### 3.4 Honest remaining limits

* **A live camera cannot be exported.** There are no recorded frames to encode,
  so the exporter would write an empty box. The app now *says* so and offers to
  record a take rather than producing a silently broken file. Real-time
  composition recording still needs the GPU pipeline (plan §7, V2).
* **One live camera at a time.** Two simultaneous Camera2 feeds are not
  supported on most devices; adding a second live camera selects the existing
  one instead.
* **The YUV→ARGB conversion is CPU-side** and throttled to ~24 fps at ≤960×540.
  On low-end devices the live PiP will be smooth but is not a 60 fps viewfinder;
  the recorded take is unaffected (it comes from the hardware encoder at 30 fps
  and full resolution).
* **Sheets still exist** for the dock list, the mixer faders, export settings
  and the advanced panel. That is deliberate: a radial ring is a bad place for
  a continuous slider. Every one of them is *opened from a petal*, so the
  radial menu remains the single entry point.
