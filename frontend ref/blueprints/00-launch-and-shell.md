# Blueprint 00 — Launch, Splash & Studio Shell

## 00-A · AMS Splash (S0) — `AmsSplashScreen.kt`

Full-screen overlay `#0E1016`, centered column. Renders **over** the already-initializing studio.

```
┌─────────────────────────────┐
│                             │
│        ┌───────────┐        │
│        │ A M S     │ 108dp badge, radius 28dp
│        │ cyan/wht/ │ gradient #123048 → #0E1016
│        │ amber 40sp│ 1.5dp cyan-55% ring + 1dp pulsing ring (700ms)
│        └───────────┘        │  scale 0.85→1 (550ms) + fade (450ms)
│                             │
│            AMS              │  34sp ExtraBold, white, letter-spacing 6sp
│                             │  fade+slide-up 18dp→0 (360ms, starts +260ms)
│     Create. React. Record.  │  14sp Medium, #94A3B8, spacing 1.5sp
│                             │  fade (300ms, starts +440ms)
└─────────────────────────────┘
hold 320ms → overlay fades 260ms → studio revealed. TOTAL ≈ 1.2s
```

Badge letters: `A` cyan `#38BDF8`, `M` white (offset −3dp), `S` amber `#F59E0B` (offset −6dp), all 40sp Black.

## 00-B · Studio shell (S1) — `MainActivity.kt`

- `Scaffold(fillMaxSize, containerColor=#0E1016, insets=safeDrawing)`, edge-to-edge.
- `Column`: **TopStrip** (48dp) + `Row(weight=1f)`: **Sidebar** (slide/fade animated) + stage `Box`.
- Stage `Box`: **StageView** + **FloatingControls** aligned `BottomEnd`, margin end 16dp / bottom 64dp.
- **Screen-Light overlay**: full-size white @85% when `torchMode == SCREEN_LIGHT` (above content).
- **Toasts**: system `Toast.LENGTH_SHORT` on `uiState.toastMessage`, then cleared.
- **Camera permission**: checked on launch; requests `CAMERA`; on grant → `CameraManager.initialize`.
- **Export folder picker**: `OpenDocumentTree` SAF; persists URI permission; resolves display name via
  `DocumentsContract`; falls back to "Movies (default)".
- **Orientation**: 16:9→sensor-landscape, 9:16→sensor-portrait, 1:1→unspecified. Sidebar width 240dp
  landscape / 260dp portrait. `configChanges=orientation|screenSize|screenLayout|keyboardHidden`.
- **Back handlers** (priority): immersive→restore chrome; else FullCanvas→exit FC; else sidebar-open→close;
  else exit app.
- **Startup dialog gate**: `showStartupAspectRatioDialog` (default true) renders only after splash ends.
- Dialog hosts (all centered, above shell): AudioMixer, Export, ExportProgress, LayerProperties,
  TextEditor, Rename, Diagnostics, SaveExportExplainer, StartupAspectRatio.
- Unused: `Greeting()` ("Ahmed Reaction Studio (name)") — exists only for screenshot test.
