# Blueprint 01 — Top Strip (S2)

`TopStrip.kt` · `Row(fillMaxWidth, 48dp, bg #0E1016 @90% = #E60E1016, padding-h 8dp, centered)`

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ☰ │ Ahmed Reaction Studio ✎ │ ⬒16:9 │ ↶ ↷ │ ●💾Save Draft │ 🎬Export MP4 │ ⛶ │ ⋮ │
└──────────────────────────────────────────────────────────────────────────┘
```

## Controls left → right (exact specs)

| # | Element | Spec | Test tag |
|---|---|---|---|
| 1 | Hamburger | `IconButton` 40dp, radius 8; open→cyan-20% bg + cyan icon, else transparent + `#F1F5F9` | `hamburger_button` |
| 2 | Title | `weight(1f, fill=false)`, clickable, radius 6, pad h6/v4; text 14sp SemiBold `#F1F5F9` 1-line ellipsis + ✎ 12dp `#64748B`; gap 6dp before, 8dp after | — |
| 3 | Aspect chip | `Surface` radius 12, `#1F2432`, 1dp `#2E3547`; pad h8/v3; ⬒ 12dp cyan + gap 4 + label 11sp Bold cyan (`16:9`/`9:16`/`1:1`); **tap = cycle ratio** | `aspect_ratio_chip` |
| 4 | Undo | 36dp, 18dp `AutoMirrored.Undo`; enabled `#F1F5F9` / disabled `#64748B`@40% | `undo_button` |
| 5 | Redo | same as Undo with Redo icon | `redo_button` |
| 6 | Save Draft | pill radius 8: dirty→amber-15% bg + amber-50% ring + 7dp amber dot + 💾 13dp amber + `Save Draft` 11sp SemiBold amber; clean→white-7% bg + white-12% ring + green dot + grey `Saved`; pad h8/v5 | `save_project_button` |
| 7 | Export MP4 | `FilledTonalButton` h30, radius 8, cyan bg, black content; 🎬 14dp + `Export MP4` 11sp ExtraBold; pad-h 10 | `export_mp4_button` |
| 8 | Full Canvas | 36dp, 20dp ⛶/exit icon; active cyan else `#F1F5F9` | `full_canvas_button` |
| 9 | Overflow ⋮ | 36dp, 18dp `MoreVert` `#F1F5F9` → `DropdownMenu` bg `#1F2432` | `overflow_menu_button` |

## Overflow menu items (each = icon + label row)
1. `Full Screen Canvas` / `Exit Full Screen Canvas` — ⛶ cyan icon, `#F1F5F9` text.
2. `Why Save vs Export?` — `HelpOutline` icon, **amber text+icon** → opens D9 explainer.
3. `Show/Hide Stats Overlay` — `Info` cyan icon → toggles HUD.
4. `Studio Settings & Diagnostics` — `Settings` `#94A3B8` icon → opens D8 diagnostics.

Visibility: shown when `!isFullCanvasMode || showStudioChrome` (hidden only in immersive workspace).
