# Ahmed Reaction Studio â€” UI/UX Consolidation & Fool-Proof Fix Plan

## Purpose

This document is the **master implementation checklist for the Android editor UI/UX cleanup**.

The application already has working/mostly-working camera, local video, rendering, recording, audio, source selection, and export functionality. **Do not unnecessarily rewrite working encoder/decoder/recording code.**

The main problem now is that the editor has accumulated too many overlapping controls, duplicated actions, scattered radial-wheel controls, confusing source/audio behavior, and an unclear separation between **Studio/Edit mode**, **Preview**, and **Recording**.

The goal is to turn the current interface into a **clean, professional, source-based mobile video editor** that feels relaxed and predictable.

---

# 0. NON-NEGOTIABLE RULES

## Rule 1 â€” One action must have one home

Do not provide the same action in:

- radial wheel
- quick toolbar
- bottom tab
- source chip
- floating button
- popup

unless there is a strong UX reason.

If an action already exists, **reuse it rather than creating another implementation**.

### Principle

> ONE FUNCTION = ONE PRIMARY CONTROL.

Related controls may be grouped together.

Example:

```text
Camera
 â”œâ”€â”€ Mic volume
 â”œâ”€â”€ Mic mute
 â”œâ”€â”€ Torch
 â”œâ”€â”€ Switch camera
 â””â”€â”€ Mirror
```

Do NOT create five different places where these can be controlled.

---

# 1. WORK ONE PROBLEM AT A TIME

The AI coding agent MUST NOT attempt the entire project in one pass.

For every task:

1. Inspect existing implementation.
2. Identify the root cause.
3. Make the smallest safe change.
4. Build.
5. Test.
6. Mark the task `[x]` in this file.
7. Record files changed and test result.
8. Only then continue to the next task.

If a task fails, mark:

```text
[!] BLOCKED
```

and explain why.

Never mark a task complete merely because code compiles.

---

# 2. CHANGE LOG / PROGRESS TRACKER

The agent must maintain this section.

## Foundation

- [ ] A01 â€” Audit all editor controls
- [ ] A02 â€” Map every action to exactly one primary control
- [ ] A03 â€” Identify and remove/consolidate duplicate radial-wheel actions
- [ ] A04 â€” Establish stable selectedSourceId/source-type routing
- [ ] A05 â€” Confirm existing canvas fitting remains intact

## Source UX

- [ ] B01 â€” Unified source selection behavior
- [ ] B02 â€” Source-specific contextual controls
- [ ] B03 â€” Clean source/layer strip
- [ ] B04 â€” Layer ordering/navigation
- [ ] B05 â€” Source hide/lock/delete controls
- [ ] B06 â€” Source-specific More/Advanced panel

## Audio UX

- [ ] C01 â€” Camera microphone mute control
- [ ] C02 â€” Camera microphone volume
- [ ] C03 â€” Local-video volume
- [ ] C04 â€” Local-video mute
- [ ] C05 â€” Master volume
- [ ] C06 â€” Verify camera volume changes camera only
- [ ] C07 â€” Verify local-video volume changes local video only
- [ ] C08 â€” Prevent one source's audio UI from controlling another source
- [ ] C09 â€” Do not modify working audio encoder/mixer internals unless a UI connection bug requires it

## Local Media Behavior

- [ ] D01 â€” Adding local video shows thumbnail/first frame
- [ ] D02 â€” Newly added local video starts PAUSED
- [ ] D03 â€” Adding media does not automatically start playback
- [ ] D04 â€” Preview Play explicitly starts playback
- [ ] D05 â€” Recording explicitly starts recording
- [ ] D06 â€” Preview playback and recording state are separate
- [ ] D07 â€” Local-video controls are source-specific

## Camera UX

- [ ] E01 â€” Camera source controls consolidated
- [ ] E02 â€” Mic volume/mute
- [ ] E03 â€” Hardware rear torch
- [ ] E04 â€” Camera switch
- [ ] E05 â€” Mirror/flip
- [ ] E06 â€” Fit/Fill
- [ ] E07 â€” Camera controls do not duplicate elsewhere

## Flash/Torch

- [ ] F01 â€” Detect real hardware torch capability
- [ ] F02 â€” Rear hardware torch ON/OFF
- [ ] F03 â€” Front-camera behavior handled correctly
- [ ] F04 â€” Screen-light fallback only when appropriate
- [ ] F05 â€” Torch lifecycle cleanup
- [ ] F06 â€” Investigate simultaneous torch + camera flash capability
- [ ] F07 â€” Do not claim simultaneous operation if Android/device hardware prevents it
- [ ] F08 â€” Verify behavior on the user's physical device

## Studio Mode

- [ ] G01 â€” Clean Studio layout
- [ ] G02 â€” Canvas remains dominant
- [ ] G03 â€” Source/layer strip below canvas
- [ ] G04 â€” Context controls stay near selected source
- [ ] G05 â€” Bottom navigation has clear single-purpose tabs
- [ ] G06 â€” No duplicated functions

## Full-Screen Recording Mode

- [ ] H01 â€” Recording enters full-screen canvas mode
- [ ] H02 â€” Floating Pause/Resume button
- [ ] H03 â€” Floating Stop button
- [ ] H04 â€” Floating Back button
- [ ] H05 â€” Back returns to Studio without stopping recording
- [ ] H06 â€” Recording continues while Studio is hidden
- [ ] H07 â€” Returning to Studio shows recording state
- [ ] H08 â€” Small Studio full-screen/preview-expand button
- [ ] H09 â€” Full-screen mode handles portrait and landscape

## Visual Polish

- [ ] I01 â€” Consistent spacing
- [ ] I02 â€” Consistent iconography
- [ ] I03 â€” Consistent orange accent
- [ ] I04 â€” No accidental overlapping controls
- [ ] I05 â€” No clipped controls
- [ ] I06 â€” 16:9 layout verified
- [ ] I07 â€” 9:16 layout verified
- [ ] I08 â€” 1:1 layout verified
- [ ] I09 â€” Portrait verified
- [ ] I10 â€” Landscape verified

## Regression

- [ ] J01 â€” Camera preview still works
- [ ] J02 â€” Local video preview still works
- [ ] J03 â€” Audio still works
- [ ] J04 â€” Recording still works
- [ ] J05 â€” Export still works
- [ ] J06 â€” Source selection still works
- [ ] J07 â€” Layer order still works
- [ ] J08 â€” Torch still works
- [ ] J09 â€” No encoder/decoder regressions
- [ ] J10 â€” Final UI audit

---

# 3. TARGET UX

The target interaction is:

```text
OPEN PROJECT
     â†“
STUDIO MODE
     â†“
Canvas is the main focus
     â†“
Sources/layers are clearly visible below
     â†“
Tap a source
     â†“
That source becomes selected
     â†“
Orange selection frame appears
     â†“
Only that source's controls appear
     â†“
Change a control
     â†“
Only that source changes
```

The user should never have to guess:

> "Which of these three buttons controls my camera?"

---

# 4. TARGET STUDIO LAYOUT

The target design should follow the clean structure established during planning.

## Main structure

```text
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ â† Project       9:16    â†¶ â†·  â›¶ âš™ â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚                                    â”‚
â”‚                                    â”‚
â”‚              CANVAS                â”‚
â”‚                                    â”‚
â”‚        selected source             â”‚
â”‚        with orange frame           â”‚
â”‚                                    â”‚
â”‚                                    â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  Source contextual controls        â”‚
â”‚  Hide  Mute  Lock  Fit  More       â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚                                    â”‚
â”‚ [Camera] [Video] [Image] [Text] [+]â”‚
â”‚                                    â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ Layers   Add   Audio   Text Export â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚  -10      â–¶      +10    0:00 / 0:17â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚             â— RECORD               â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

This is a **structural target**, not a command to blindly recreate every pixel.

The actual implementation must adapt to the existing Android project.

---

# 5. CANVAS RULE

The canvas is the most important element.

It must remain fully visible in Studio mode.

Supported project ratios:

- 16:9
- 9:16
- 1:1

The canvas must use fit-to-available-space logic.

Conceptually:

```text
scale =
min(
    availableWidth / canvasWidth,
    availableHeight / canvasHeight
)
```

Do NOT confuse this with a source's FIT/FILL behavior.

### Canvas FIT

Means:

> Show the complete project canvas.

### Source FIT/FILL

Means:

> Determine how a selected source fits inside its own bounds.

These are different operations.

---

# 6. 16:9 AND 9:16

## 16:9 landscape

The interface should prioritize a large horizontal canvas.

Controls should compress into:

- compact source strip
- compact contextual toolbar
- bottom navigation

Do not allow source panels to push the canvas off-screen.

## 9:16 portrait

The canvas naturally becomes taller.

Controls should stack efficiently below it without destroying the preview.

The same source architecture must work in both orientations.

---

# 7. SOURCE/LAYER STRIP

The source strip is important and should remain.

Example:

```text
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”
â”‚ Camera   â”‚ Video    â”‚ VID_2026...  â”‚  +Add â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”˜
```

Each source should be selectable directly.

The selected source should have the orange active treatment.

## Layers tab

When the user opens Layers, show a proper vertical layer list:

```text
TOP
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
Text 1
Camera
Video 2
Video 1
Background
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
BOTTOM
```

Tapping a layer immediately selects that source and focuses it on the canvas.

This is useful because the user can jump directly to a source without hunting on the canvas.

---

# 8. SOURCE-SPECIFIC CONTROL SYSTEM

This is the most important architectural UI rule.

When a source is selected:

```text
selectedSourceId
+
selectedSourceType
```

must determine which controls are shown.

Do not scatter controls based on unrelated UI state.

---

# 9. CAMERA CONTROLS

When Camera is selected:

```text
Camera
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

Mic
ðŸ”Š â”€â”€â”€â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€ 100%

[ Mute ]

[ Torch ]

[ Switch camera ]

[ Mirror ]

[ Fit ]

[ Lock ]

[ More ]
```

Quick controls should contain only the most common actions.

Advanced controls can go under More.

---

# 10. LOCAL VIDEO CONTROLS

When a local video is selected:

```text
Video
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

â–¶ / Pause

Audio
ðŸ”Š â”€â”€â”€â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€ 100%

[ Mute ]

0:00 â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ 0:17

[ Fit ]

[ Lock ]

[ More ]
```

Potential advanced controls:

- speed
- loop
- trim
- crop
- position
- transform

Only expose features that actually exist and work.

---

# 11. IMAGE CONTROLS

```text
Image
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

Fit
Fill

Opacity
â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€â”€â”€

Rotate

Lock

More
```

No audio controls should appear for an image.

---

# 12. TEXT CONTROLS

```text
Text
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

Edit text

Font

Size

Color

Opacity

Alignment

Lock

More
```

---

# 13. SCREEN SOURCE CONTROLS

If screen capture exists:

```text
Screen
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

Mute

Volume

Fit

Lock

Stop capture

More
```

Only show controls supported by the implementation.

---

# 14. AUDIO ARCHITECTURE â€” UI SIDE

The UI must distinguish:

## Master volume

Controls the final mixed output level.

## Source volume

Controls one source only.

Example:

```text
MASTER
ðŸ”Š â”€â”€â”€â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€ 100%

CAMERA
ðŸŽ™ â”€â”€â”€â”€â”€â—â”€â”€â”€â”€â”€â”€â”€â”€ 50%

LOCAL VIDEO
ðŸ”Š â”€â”€â”€â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€ 100%
```

This allows:

```text
Camera microphone = 40%
Local video       = 100%
```

so the local media can be clearer.

Or:

```text
Camera microphone = 100%
Local video       = 30%
```

for a reaction-focused mix.

---

# 15. CAMERA VOLUME BUG

The current behavior where manual volume adjustment affects local media but not camera must be investigated.

Do NOT merely change the slider's visual value.

Trace:

```text
UI slider
   â†“
selectedSourceId
   â†“
source audio state
   â†“
audio mixer gain
```

Camera and local media must not accidentally share the same audio control key.

Verify:

```text
Camera volume  â‰  Local video volume
Camera mute    â‰  Local video mute
```

Changing camera volume must not change local media.

Changing local-video volume must not change camera.

---

# 16. DO NOT REWRITE THE AUDIO ENGINE

The user can already record efficiently.

Therefore:

> DO NOT rewrite encoder/decoder/mixer architecture merely to improve the UI.

Only modify audio internals if investigation proves that the UI is connected to the wrong source or if a previously known functional bug actually remains.

UI work must not introduce a new audio pipeline.

---

# 17. LOCAL MEDIA MUST NOT AUTOPLAY ON ADD

This behavior must be corrected.

Current undesirable behavior:

```text
Add local video
      â†“
Thumbnail appears
      â†“
Video automatically starts playing
```

Target behavior:

```text
Add local video
      â†“
Create source
      â†“
Show thumbnail / first frame
      â†“
Position at 0:00
      â†“
PAUSED
```

Nothing should start playing merely because the source was added.

---

# 18. PREVIEW VS RECORDING

These are different states.

## Studio/Edit mode

The user arranges sources.

Camera can be live.

Local video is paused unless the user presses Play.

No recording is happening.

## Preview mode

User presses:

```text
â–¶ PLAY
```

The project preview plays.

Still no recording.

## Recording mode

User presses:

```text
â— RECORD
```

Only now should recording start.

---

# 19. RECORDING FULL-SCREEN MODE

When recording begins, switch to a clean full-screen view.

The canvas should occupy essentially the entire display.

Remove the normal Studio controls.

Show only minimal floating recording controls.

Concept:

```text
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                                    â”‚
â”‚                                    â”‚
â”‚              CANVAS                â”‚
â”‚                                    â”‚
â”‚                                    â”‚
â”‚                                    â”‚
â”‚                         â—‰ Pause    â”‚
â”‚                         â–  Stop     â”‚
â”‚                         â† Back     â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

Controls should be floating and unobtrusive.

Do not permanently cover important canvas content.

---

# 20. RECORDING FULL-SCREEN CONTROLS

At minimum:

### Pause / Resume

```text
â–¶ Resume
â¸ Pause
```

The same floating control should toggle state.

### Stop

Stops recording normally and returns to the appropriate post-recording state.

### Back

IMPORTANT:

Pressing Back during recording must **NOT stop recording**.

Instead:

```text
FULLSCREEN RECORDING
        â†“
Back
        â†“
STUDIO MODE
        â†“
Recording continues in background
```

The Studio should clearly indicate that recording is still active.

---

# 21. RETURNING FROM FULLSCREEN RECORDING

When the user returns to Studio mode while recording:

Show a compact recording indicator.

Example:

```text
â— REC 00:23
```

The user can continue editing/monitoring as supported by the architecture.

Do not accidentally create a second recording session.

---

# 22. STUDIO â†’ FULLSCREEN PREVIEW BUTTON

Studio mode should have a small icon that expands the canvas into a full-screen viewing mode.

Example:

```text
â›¶
```

This is NOT the same as starting recording.

It simply means:

```text
Studio
 â†“
Full-screen preview
```

The user can return with Back.

---

# 23. RECORDING STATE MACHINE

Use clear states.

Conceptually:

```text
STUDIO
  â”‚
  â”œâ”€â”€ Play â†’ PREVIEW
  â”‚
  â””â”€â”€ Record â†’ RECORDING
                  â”‚
                  â”œâ”€â”€ Pause
                  â”‚
                  â”œâ”€â”€ Resume
                  â”‚
                  â”œâ”€â”€ Back â†’ STUDIO + RECORDING ACTIVE
                  â”‚
                  â””â”€â”€ Stop â†’ FINISHED
```

Do not allow random UI controls to create conflicting states.

---

# 24. TORCH / FLASHLIGHT

The phone's physical rear flashlight has been confirmed by the user to work independently through the phone's native flashlight feature.

Therefore the app must investigate why its torch controls are not behaving correctly.

## Requirements

Detect:

- rear camera ID
- front camera ID
- `FLASH_INFO_AVAILABLE`
- torch support
- camera lifecycle

Use the proper Android hardware torch mechanism where supported.

Do not fake a rear hardware torch with a white screen.

---

# 25. TORCH AND CAMERA FLASH

There are potentially two different concepts:

### Torch

Continuous LED light.

### Camera flash

Short burst associated with taking a still image / supported camera operation.

Do not label these as if they are automatically identical.

If the app wants both capabilities, expose them clearly.

Example:

```text
Torch
[ OFF / ON ]

Capture Flash
[ OFF / ON / AUTO ]
```

However, **do not promise simultaneous operation if the device/Camera2 HAL does not support it**.

The agent must test the actual device capability.

If the device supports simultaneous torch + required camera operation, implement it correctly.

If Android/device restrictions prevent it, report the limitation instead of inventing a fake solution.

---

# 26. TORCH LIFECYCLE

Torch must not remain accidentally enabled after:

- leaving camera
- closing CameraActivity
- stopping recording
- switching camera
- app background
- camera failure

Clean up safely.

Do not crash if no torch exists.

---

# 27. RADIAL WHEEL CLEANUP

The screenshots show duplicated/scattered controls around the radial wheel.

The agent must first inventory them.

For every radial-wheel action ask:

```text
Does this action already exist somewhere else?
```

If yes:

- remove the duplicate UI action, OR
- make the radial wheel the one canonical location.

Recommended approach:

### Keep radial wheel as optional quick actions

but do not duplicate the entire editor.

For example:

```text
More
 â†“
Quick actions
```

or:

```text
Selected source
      â†“
More
```

The normal UI should remain usable without opening the radial wheel.

---

# 28. BOTTOM NAVIGATION

Keep the bottom navigation if it is useful:

```text
Layers | Add | Audio | Text | Export
```

But each tab must have a clear purpose.

## Layers

Source/layer management.

## Add

Add new source.

## Audio

Master + source audio controls.

## Text

Text tools.

## Export

Export configuration/export action.

Do not duplicate camera mute, local volume, lock, fit, etc. across every tab.

---

# 29. ADD TAB

The Add interface should be simple.

```text
+ Add

Camera
Video
Image
Screen
Text
```

After selecting a source, close the Add UI and return to the editor.

Do not leave multiple add menus open.

---

# 30. SOURCE CREATION BEHAVIOR

When adding local media:

1. Create stable source ID.
2. Read media metadata.
3. Show thumbnail/first frame.
4. Set initial transform.
5. Set audio defaults.
6. Set playback position to zero.
7. Set playback state to PAUSED.
8. Add to layer list.
9. Select the new source.
10. Show source-specific controls.

---

# 31. SOURCE SELECTION

The selected source must have:

- orange/red frame
- correct handles
- source label if useful
- contextual controls

When another source is selected:

- previous frame disappears
- new frame appears
- contextual controls update

Do not create multiple active selection systems.

---

# 32. SOURCE LAYER LIST

A vertical layer list is encouraged because it gives a very fast way to navigate complex projects.

Example:

```text
LAYERS

â˜· Text
â˜· Camera
â˜· Reaction Video
â˜· Main Video
â˜· Background
```

Tapping any row selects that source.

Optional future functionality:

- drag to reorder
- visibility icon
- lock icon
- mute icon

But do not add unnecessary buttons if they already exist elsewhere.

---

# 33. CONTROL VISIBILITY RULE

Controls should be contextual.

Do not show:

```text
Camera controls
Video controls
Image controls
Text controls
```

all at once.

Instead:

```text
Selected source = Camera
â†’ Camera controls

Selected source = Video
â†’ Video controls

Selected source = Image
â†’ Image controls
```

This is the single biggest UI simplification.

---

# 34. MORE / ADVANCED

The More button should contain less-common controls.

Example:

```text
More

Transform
Position
Size
Rotation
Opacity
Crop
Blend
Advanced audio
Metadata
```

Only include features actually implemented.

Do not create dead buttons.

---

# 35. VISUAL DESIGN

Use a restrained design.

Existing orange accent can remain the primary action/selection color.

Recommended hierarchy:

### Primary

Orange filled buttons.

### Selected

Orange outline/fill.

### Secondary

Dark gray buttons.

### Neutral

Dark background + white/light gray text.

Avoid:

- excessive glowing effects
- too many outlined circles
- random floating buttons
- overlapping panels
- duplicate icons
- unnecessary animations

The interface should feel **solid, calm, and professional**.

---

# 36. RESPONSIVE LAYOUT

Never use fixed screen coordinates for major UI layout.

Use:

- measured available width/height
- constraints/layout containers
- dynamic canvas fitting
- scrollable/horizontal source strips where appropriate

The app must work on different Android screen sizes.

---

# 37. PORTRAIT / LANDSCAPE TEST MATRIX

Every UI change must be tested against:

| Device orientation | Canvas |
|---|---|
| Portrait | 16:9 |
| Portrait | 9:16 |
| Portrait | 1:1 |
| Landscape | 16:9 |
| Landscape | 9:16 |
| Landscape | 1:1 |

Minimum requirement:

- no clipped canvas
- no clipped controls
- no overlapping panels
- no inaccessible buttons
- no source strip pushing canvas away

---

# 38. PERFORMANCE RULE

Do not sacrifice existing recording performance for UI effects.

Avoid:

- rebuilding the entire editor for every slider movement
- creating/destroying source Views unnecessarily
- decoding thumbnails continuously
- heavy blur
- unnecessary animation
- unnecessary bitmap copies

UI must remain responsive while recording.

---

# 39. CODE ARCHITECTURE RULE

Before modifying code, locate the existing implementations of:

- EditorActivity
- StageView
- SourceDock
- RadialWheel
- source model
- SourceController
- PreviewEngine
- camera code
- audio UI
- recording state
- project store

Reuse existing architecture.

Do not create:

```text
NewSourceManager2
NewAudioMixerUI
NewCameraController2
NewRadialWheel2
```

just because an existing component needs cleanup.

---

# 40. SAFE IMPLEMENTATION ORDER

The AI agent should follow this exact order.

## Phase 1 â€” AUDIT ONLY

Do not make large changes.

Inventory:

- every button
- every radial action
- every bottom tab
- every source control
- every audio slider
- every camera control
- every recording control

Create a map:

```text
ACTION â†’ CURRENT UI LOCATIONS â†’ DESIRED PRIMARY LOCATION
```

Then remove/consolidate duplicates.

---

## Phase 2 â€” SOURCE CONTEXT

Create/verify a single source selection model.

Everything should know:

```text
selectedSourceId
selectedSourceType
```

---

## Phase 3 â€” SOURCE CONTROLS

Implement the contextual source control panel.

---

## Phase 4 â€” AUDIO UI

Implement:

- master volume
- camera mic volume
- local-video volume
- mute controls

Verify source isolation.

---

## Phase 5 â€” LOCAL MEDIA PLAYBACK STATE

Fix:

```text
ADD VIDEO â†’ PAUSED
```

No autoplay.

---

## Phase 6 â€” LAYERS

Create the clean layer list and source navigation.

---

## Phase 7 â€” CAMERA/TORCH

Consolidate camera controls and fix real hardware torch behavior.

---

## Phase 8 â€” RECORDING FULLSCREEN

Implement:

```text
Studio
 â†“
Record
 â†“
Fullscreen recording
 â†“
Back
 â†“
Studio while recording continues
```

with floating Pause/Resume/Stop.

---

## Phase 9 â€” FULLSCREEN PREVIEW

Add the small Studio expand/fullscreen icon.

---

## Phase 10 â€” FINAL VISUAL POLISH

Only after all functional behavior is correct:

- spacing
- sizes
- icons
- animations
- typography
- panels
- portrait/landscape refinement

---

# 41. DO NOT START PHASE 2 UNTIL PHASE 1 IS VERIFIED

This rule is important.

The agent should not make assumptions.

After each phase, report:

```text
PHASE: 1
STATUS: COMPLETE

Root cause:
...

Files changed:
...

Controls removed:
...

Controls consolidated:
...

Build:
PASS/FAIL

Manual test:
PASS/FAIL

Next phase:
...
```

Then continue.

---

# 42. FINAL ACCEPTANCE TEST

The project is considered complete only when the following workflow works.

## Workflow A â€” Camera

```text
Create project
â†’ Add Camera
â†’ Camera selected
â†’ Orange border
â†’ Camera controls shown
â†’ Change mic volume
â†’ Only camera audio changes
â†’ Mute camera
â†’ Camera audio silent
â†’ Unmute
â†’ Camera audio returns
â†’ Torch works
â†’ Switch camera
â†’ Controls remain correct
```

---

## Workflow B â€” Local video

```text
Add video
â†’ Thumbnail appears
â†’ Video remains PAUSED
â†’ No autoplay
â†’ Select video
â†’ Video controls appear
â†’ Change volume
â†’ Only video audio changes
â†’ Play
â†’ Pause
â†’ Resume
```

---

## Workflow C â€” Multiple sources

```text
Camera
Video
Image
Text

Tap Camera
â†’ Camera controls

Tap Video
â†’ Video controls

Tap Image
â†’ Image controls

Tap Text
â†’ Text controls
```

No unrelated controls appear.

---

## Workflow D â€” Layers

```text
Open Layers
â†’ see sources vertically
â†’ tap source
â†’ source selected
â†’ orange frame appears
â†’ reorder
â†’ rendering order changes
```

---

## Workflow E â€” Recording

```text
Studio
â†’ press Record
â†’ fullscreen canvas
â†’ floating controls

Pause
â†’ recording pauses

Resume
â†’ recording continues

Back
â†’ Studio appears
â†’ recording continues

Open fullscreen
â†’ return to fullscreen recording

Stop
â†’ recording ends normally
```

---

# 43. REGRESSION PROTECTION

After UI work, verify that the agent has NOT broken:

- camera preview
- local video decoding
- preview rendering
- audio
- audio mixing
- recording
- export
- source transforms
- layer order
- flashlight
- project saving
- project loading

If a UI change causes a functional regression, revert that specific change and fix it safely.

---

# 44. DO NOT REWRITE WORKING MEDIA PIPELINES

Unless a test proves otherwise, do NOT rewrite:

- MediaCodec video decoder
- MediaCodec audio encoder
- MediaExtractor
- MediaMuxer
- camera capture pipeline
- audio mixer
- compositor

This project already has substantial working media functionality.

The purpose of this plan is primarily to **organize and connect the UI correctly**, not to replace working media technology.

---

# 45. AGENT RULE FOR EVERY TASK

For every checkbox:

### BEFORE

```text
Inspect
â†’ understand
â†’ reproduce
â†’ identify root cause
```

### DURING

```text
Smallest safe change
â†’ reuse existing code
â†’ no unrelated refactor
```

### AFTER

```text
Build
â†’ test
â†’ verify
â†’ mark checkbox
â†’ document result
```

---

# 46. COMPLETION FORMAT

At the end of each task, append:

```text
### TASK X â€” COMPLETE

Root cause:
...

Implementation:
...

Files changed:
- ...
- ...

Behavior before:
...

Behavior after:
...

Manual test:
PASS / FAIL

Build:
PASS / FAIL

Regression check:
PASS / FAIL

Notes:
...
```

Then mark the corresponding checkbox `[x]`.

---

# 47. FINAL UX PRINCIPLE

The finished editor should feel like this:

> **Tap a source â†’ see exactly that source's controls â†’ change something â†’ only that source changes.**

And:

> **Add media â†’ see it paused â†’ press Play to preview â†’ press Record to record.**

And:

> **Record â†’ canvas becomes fullscreen â†’ floating controls remain available â†’ Back returns to Studio without stopping recording.**

And:

> **One function has one clear control. Related functions are grouped. Nothing is duplicated just because it can be.**

The result should be **simple to understand, fast to operate, and powerful when needed**, rather than a screen filled with scattered buttons.

---

# 48. CURRENT STATUS

This file is the master checklist.

The AI agent MUST update this file as work progresses.

Do not delete completed tasks.

Do not mark tasks complete without testing.

When a task is fixed, change:

```text
- [ ] TASK
```

to:

```text
- [x] TASK
```

When blocked:

```text
- [!] TASK
```

and add the reason below it.

---

## End of Master Plan
