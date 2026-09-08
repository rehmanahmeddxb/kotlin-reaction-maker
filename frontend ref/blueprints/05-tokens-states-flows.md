# Blueprint 05 — Tokens, States, Flows & Rapid Rebuild Cheatsheet

## Tokens (copy-paste)
```
--dark:#0E1016; --surface:#161922; --elevated:#1F2432; --border:#2E3547;
--cyan:#38BDF8; --blue:#2563EB; --purple:#818CF8; --red:#EF4444;
--amber:#F59E0B; --green:#10B981;
--t1:#F1F5F9; --t2:#94A3B8; --muted:#64748B;
--stage:#07080B; --selbg:#244878; --card:#1B202E; --d9bg:#141822;
```
Font: system default (Roboto). Sizes: 9 badge · 10 hud · 11 btn · 12 row · 13 label · 14 title ·
15/17 heads · 34 splash · 40 mark. Radii: 4/6/8/10/12/14/16/18/20/28 + circles.
M3 scheme: primary cyan/on-black, secondary purple, tertiary red, bg dark, surface/surfaceVariant,
outline border; **dynamicColor on Android 12+**; light scheme = dark (forced dark studio).

## Canvas backgrounds / aspects / torch / export / stats / audio defaults
- Bg: DARK `#0E1016` · BLACK · WHITE · ORANGE `#E65100` · NAVY `#0D1B2A` · GREEN `#1B4332` · PURPLE `#3A0CA3`.
- Aspect: 16:9 1920×1080 (→landscape) · 9:16 1080×1920 (→portrait) · 1:1 1080×1080 (→free).
- Torch: Off · Front Torch · Back Torch · Both Torches · Screen Light (white-85% overlay).
- Export: `720p (HD)` · 30fps · `H.264 / AVC` · 8Mbps · dest `Movies (default)`/SAF label.
- Stats: 60fps · 16.4ms · `HW MediaCodec + OES` · 1920x1080 · 12ms.
- Audio: master 100% · mic 100% (0–200%) · solo none. Playback speed 0.25–3 (chips 0.5–2).
- Text: default `Reaction Title`/28sp/Bold/shadow when missing; editor default yellow/26sp/shadow-on.

## UI state defaults (`StudioUiState`)
Playing F · pos 0 · recording F · sidebar OPEN · FullCanvas F · chrome ON · HUD OFF ·
expanded sections {sources, record} · submenus none · undo/redo F · startup dialog ON.

## Visibility rules (single source of truth)
- TopStrip/Sidebar/Timeline/Transport ⟺ `!FullCanvas || chrome`.
- Sidebar panel additionally ⟺ `isSidebarOpen`. Immersive = FC + !chrome (max canvas, eye pill, REC pill).
- Quick bar ⟺ selection ≠ null (any mode). Quick pill above/docked by 44dp room rule.
- Empty canvas ⟺ no visible layers. Timeline enabled ⟺ duration > 0. Play/Export blocked with toast
  when no video (`Add a video to the timeline before playing/exporting`).
-Progress dialog ⟺ exporting/success/error. Screen-light ⟺ torch==SCREEN_LIGHT.

## Toasts (trigger → text) — all 30
undo `Undone` · redo `Redone` · play-empty `Add a video…before playing` · rec on/off `Recording
started`/`Take saved: {n}s reaction clip` · snapshot `Snapshot saved to Gallery at mm:ss` ·
del/dup `Layer deleted/duplicated` · add `Added {name}` · torch `{name} torch ON/OFF` (+unsupported
variant) · auto/fit/center/stretch×2 `Auto-filled…/Fitted…/Centered…/Stretched…` · bg `Set as
background layer` · mic `Mic Gain: n%/100%` · solo `Solo active/disabled` · light/aspect/bg
`Lighting:/Aspect:/Background: {label}` · fit-all `Aligned all sources` · rename `Renamed to…` ·
save `Project saved to local store` · rot-reset `Rotation reset to 0°` · media attach msgs ·
export-guard `Add a video…before exporting` · `Export complete! Video saved.` / error text.

## Flows (user → UI)
- Launch: splash 1.2s → D1 format → (permission) → studio (sidebar open, SOURCES+RECORD expanded).
- Add source: SOURCES → Add Source ▸ → pick/add → toast `Added…` → layer auto-selected → quick bar
  appears → chrome on canvas.
- Edit transform: sidebar sliders/corners/rotation OR canvas drag/corner/edge/knob OR quick bar.
- Text: Add→D6 (😱 default) or double-tap layer→D6 (current text) → Apply → lower-third layer.
- Audio: AUDIO section or D2 mixer (master/mic/tracks/mute/solo).
- Record: RECORD section or red ⏺ → REC pills + timer → stop → toast `Take saved…`.
- Export: Export MP4 (quick) or D3 settings → D4 progress → Done/path or Close/error → toast.
- Save-vs-export doubt: ⋮ → `Why Save vs Export?` → D9 → Save Draft / Export MP4.
- Canvas setup: aspect chip cycles (rotates device) or Canvas ▸ or D1; background Canvas ▸ dots.
- Fullscreen work: ⛶ (strip/pill/sidebar/menu) → FC; 👁 hides chrome → immersive (eye restores,
  Back restores too); HUD via ⋮/Project for diagnostics.

## Gestures & test tags
Tap select · backdrop-tap deselect · drag move (locked=no-op) · corner resize (anchor opposite,
min 8%, clamp) · edge 1-D stretch · knob rotate (X×0.9°) · dbl-tap text edit.
Tags: `sidebar_container hamburger_button aspect_ratio_chip undo_button redo_button
save_project_button export_mp4_button full_canvas_button overflow_menu_button section_* stage_canvas
canvas_quick_action_bar auto_fill_button fit_frame_button center_button stage_full_canvas_toggle
studio_chrome_eye_button workspace_rec_indicator timeline_bar canvas_visibility_button
canvas_play_pause_button floating_play_pause_button floating_stop_button floating_record_button
save_export_explainer_dialog`.
