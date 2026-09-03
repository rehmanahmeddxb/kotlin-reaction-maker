# Ahmed Reaction Studio â€” Native Android Master Plan

> **Document status:** Architecture-locked master specification  
> **Target:** Native Android application  
> **Primary language:** Kotlin  
> **UI:** Jetpack Compose + Material 3  
> **Media stack:** AndroidX Media3 / ExoPlayer / Transformer + CameraX + OpenGL ES + MediaCodec  
> **Persistence:** Room + DataStore + versioned project JSON  
> **Background work:** WorkManager  
> **Design principle:** Local-first, offline-first, hardware-aware, non-destructive, deterministic media pipeline

---

## 0. Executive Decision

The previous browser/FastAPI architecture is **replaced** by a fully native Android architecture.

The application must NOT depend on:

- Python
- FastAPI
- Uvicorn
- browser JavaScript
- WebView for the editor
- Node.js/npm
- React
- Flutter
- Electron
- cloud rendering
- cloud project storage
- FFmpeg as the foundation of the Android pipeline

Native Android APIs and AndroidX are the primary implementation.

### Core technology lock

| Area | Decision |
|---|---|
| Language | Kotlin |
| Java | Only for interoperability when technically necessary |
| UI | Jetpack Compose |
| Design system | Material 3 |
| Architecture | Clean Architecture + MVVM/UDF |
| State | StateFlow + immutable UI state |
| DI | Hilt |
| Async | Kotlin Coroutines |
| Navigation | Navigation Compose |
| Database | Room |
| Preferences | DataStore |
| Serialization | Kotlinx Serialization |
| Video playback | Media3 ExoPlayer |
| Media editing/export | Media3 Transformer |
| Camera | CameraX |
| Concurrent cameras | CameraX Concurrent Camera where supported |
| Preview compositor | OpenGL ES |
| Low-level codec control | MediaCodec |
| Background export | WorkManager |
| Storage | Android Storage Access Framework + app-private storage |
| Images | Coil |
| Logging | Android Log + structured application logger |
| Testing | JUnit + AndroidX Test + Compose UI tests + Media3 test utilities |
| Minimum SDK | API 26 unless a later requirement proves necessary |
| Target SDK | Current stable Android SDK at build time |
| Project format | Versioned Kotlinx-serialized JSON |
| Network | None required for normal editing |
| Cloud | Not required |

---

# 1. Product Definition

Ahmed Reaction Studio is a professional local Android video/reaction/compositing application.

The application is designed for:

- reaction videos
- commentary videos
- gaming reactions
- tutorials
- comparison videos
- educational videos
- social-media vertical videos
- picture-in-picture compositions
- multi-camera recording
- multi-layer video composition
- audio mixing
- local editing
- high-quality local export

The editor is a **real media application**, not a web page wrapped inside Android.

---

# 2. Product Goals

## 2.1 Primary goals

1. Extremely responsive editing UI.
2. Smooth multi-layer preview within actual device limits.
3. Correct audio/video synchronization.
4. Hardware-accelerated preview whenever possible.
5. Hardware codec acceleration whenever supported.
6. Accurate 16:9 / 9:16 / 1:1 project handling.
7. Automatic Android UI rotation based on project orientation.
8. Real front/rear camera operation.
9. Real hardware flashlight/torch control where available.
10. Up to two simultaneous cameras where Android/device support permits.
11. H.264 and H.265/HEVC export.
12. Safe codec capability detection.
13. Crash recovery.
14. Undo/redo.
15. Non-destructive editing.
16. Original media must never be modified.
17. Fully local operation.
18. Graceful degradation on weak phones.
19. High-quality export independent from preview quality.

## 2.2 Non-goals for v1

Do not make these dependencies:

- account system
- cloud project synchronization
- server-side rendering
- social network API integrations
- online asset marketplace
- AI cloud processing
- mandatory internet connection

Future versions may add optional features without changing the local editor core.

---

# 3. Critical Architecture Principle

There are **four separate coordinate/time domains** and they must never be confused.

### 3.1 Device orientation

Physical Android screen orientation.

### 3.2 Project orientation

The actual composition canvas:

- 16:9
- 9:16
- 1:1
- future custom ratios

### 3.3 Source orientation

Metadata/orientation of each imported video/image/camera source.

### 3.4 Layer transform

The position, scale, crop, rotation and transform of a source inside the project.

These are separate concepts.

Changing the device orientation must never corrupt project geometry.

---

# 4. Automatic Orientation System

## 4.1 Aspect presets

### Landscape

`16:9`

Default logical canvas:

`1920 Ã— 1080`

### Portrait

`9:16`

Default logical canvas:

`1080 Ã— 1920`

### Square

`1:1`

Default logical canvas:

`1080 Ã— 1080`

## 4.2 User interaction

When the user selects:

`16:9`

the application:

1. changes project aspect ratio
2. changes logical canvas
3. requests landscape UI orientation
4. recalculates preview viewport
5. preserves normalized layer positions
6. preserves source media
7. preserves timeline
8. preserves crop/transform values
9. saves the project

When the user selects:

`9:16`

the same process occurs for portrait.

When `1:1` is selected, the UI orientation may remain in the most suitable device orientation because square projects do not inherently require portrait or landscape.

## 4.3 Orientation rule

Never use screen pixels as permanent project coordinates.

Use normalized coordinates.

Example:

```kotlin
@Serializable
data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)
```

All values are validated to the legal range.

---

# 5. Project Canvas Model

```kotlin
@Serializable
data class CanvasSpec(
    val aspectRatio: AspectRatio,
    val width: Int,
    val height: Int,
    val fps: Int,
    val background: BackgroundSpec
)
```

Supported FPS:

- 24
- 25
- 30
- 50
- 60

The project FPS is independent from source FPS.

---

# 6. Layer Architecture

Every visible or audible source is represented as a layer.

## 6.1 Layer types

```text
VIDEO
CAMERA
IMAGE
TEXT
AUDIO
SHAPE
STICKER
```

Future-ready:

```text
SCREEN_CAPTURE
GIF
SUBTITLE
ADJUSTMENT
```

## 6.2 Layer properties

Each layer should support:

- unique ID
- name
- type
- source ID
- visibility
- lock state
- mute state
- volume
- z-index
- position
- width
- height
- rotation
- scale
- crop
- opacity
- blend mode
- playback state
- timeline range
- speed
- audio offset
- fit mode

---

# 7. PiP Editor

The PiP editor is one of the application's primary interaction systems.

## 7.1 Required interactions

- drag
- resize
- rotate
- crop
- zoom
- move
- duplicate
- delete
- lock
- unlock
- hide
- show
- reorder
- center
- snap
- align
- reset transform

## 7.2 Resize handles

Eight handles:

```text
â—â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€â”€â”€â—
â”‚             â”‚
â—             â—
â”‚             â”‚
â—â”€â”€â”€â”€â”€â”€â—â”€â”€â”€â”€â”€â”€â—
```

Handles:

- top-left
- top-center
- top-right
- center-left
- center-right
- bottom-left
- bottom-center
- bottom-right

## 7.3 Touch ergonomics

Touch targets must be substantially larger than their visual handles.

The visible handle may be small.

The hit area must be large.

## 7.4 Snap system

Snap to:

- canvas left
- canvas right
- canvas top
- canvas bottom
- horizontal center
- vertical center
- other layer edges
- other layer centers

Snap threshold must be configurable.

---

# 8. Layer Presets

Required presets:

- full screen
- top-left
- top-center
- top-right
- center-left
- center
- center-right
- bottom-left
- bottom-center
- bottom-right
- 50/50 horizontal
- 50/50 vertical
- 70/30 horizontal
- 70/30 vertical
- quarter
- custom

Presets must calculate from project aspect ratio.

A preset created for 16:9 must not produce distorted geometry when used on 9:16.

---

# 9. Media Import

Use Android's Storage Access Framework.

Supported import sources:

- video
- image
- audio

Use:

```text
ACTION_OPEN_DOCUMENT
ACTION_OPEN_DOCUMENT_TREE
```

Request persistent URI permission where appropriate.

Do not require broad filesystem access.

---

# 10. Media Library

Create a local media library inside the application.

Each asset has:

- asset ID
- URI
- display name
- MIME type
- size
- duration
- width
- height
- frame rate
- codec
- audio codec
- rotation
- HDR information
- color information
- import timestamp
- proxy status
- thumbnail URI
- source availability

Room stores metadata.

The actual media remains in user storage or application-managed storage.

Do not put video bytes into Room.

---

# 11. Media3 Playback Architecture

Use Media3 ExoPlayer as the playback engine.

Each independently controlled video layer gets its own player instance or an equivalent isolated playback controller.

Never make independent layers share one playback state.

Required operations:

- play
- pause
- seek
- stop
- repeat
- playback speed
- volume
- mute
- visibility
- timeline positioning

---

# 12. Master Timeline

The project uses a single master composition timeline.

Each layer has its own playback state relative to that master timeline.

Conceptually:

```text
MASTER
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

Main Video
â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆ

Camera 1
â”€â”€â”€â”€â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ”€â”€â”€â”€â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ”€â”€â”€â”€â”€â”€

Camera 2
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ”€â”€â”€â”€â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ”€â”€â”€â”€â”€â”€â”€â”€

Audio
â–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆâ–ˆ
```

## 12.1 Timeline events

Required event types:

```text
PLAY
PAUSE
SEEK
SHOW
HIDE
MUTE
UNMUTE
VOLUME
ADD_LAYER
REMOVE_LAYER
REORDER_LAYER
TRANSFORM
CROP
SOURCE_CHANGE
SPEED_CHANGE
```

Events must be serializable.

---

# 13. Synchronization Rules

Use a monotonic time source.

Never synchronize using:

- wall clock
- frame number
- system date/time

The master timeline must use a monotonic clock such as Android's monotonic elapsed time facilities.

Each event stores:

- project time
- layer ID
- event type
- source media time
- payload

---

# 14. Independent Pause Semantics

This is a critical requirement.

If Camera 1 pauses:

```text
Camera 1 = frozen
Main video = continues
Camera 2 = continues
Audio = continues according to its own state
```

Pausing one layer must never pause the composition.

If a layer is hidden:

```text
visible = false
```

its playback state remains independent.

Therefore:

```text
PLAYING + HIDDEN
```

is legal.

And:

```text
PAUSED + VISIBLE
```

is also legal.

---

# 15. Preview Rendering Architecture

Do NOT use Compose Canvas as the actual heavy video compositor.

Compose is the UI layer.

The high-performance media preview uses:

```text
Media3 decoder
      â†“
Surface / SurfaceTexture
      â†“
OpenGL ES texture
      â†“
GPU compositor
      â†“
Preview SurfaceView / TextureView
```

Compose surrounds and controls this rendering surface.

---

# 16. OpenGL ES Compositor

The compositor is responsible for:

- video textures
- image textures
- text overlays
- shapes
- opacity
- transforms
- cropping
- rotation
- scaling
- blending
- layer order
- background
- color conversion
- shader effects

The compositor must run independently from Compose recomposition.

Compose should only send state changes to the rendering engine.

---

# 17. GPU Rendering Rules

Never:

- decode full video frames to Bitmaps every frame
- copy large frame buffers through the UI thread
- perform video rendering inside Compose recomposition
- recreate textures unnecessarily
- allocate large objects every frame

Prefer:

- Surface
- SurfaceTexture
- GPU textures
- reusable buffers
- shader programs
- frame synchronization
- dirty-state rendering

---

# 18. Preview Quality System

Preview quality is adaptive.

Possible levels:

```text
ULTRA
HIGH
MEDIUM
LOW
SAFE
```

The system monitors:

- frame time
- dropped frames
- decoder load
- GPU load where available
- memory pressure
- thermal state
- battery state
- number of layers

If performance becomes poor:

```text
ULTRA
 â†“
HIGH
 â†“
MEDIUM
 â†“
LOW
 â†“
SAFE
```

Export quality is never changed by this preview system.

---

# 19. Proxy System

Heavy media should use proxies.

Decision factors:

- resolution
- codec
- bitrate
- frame rate
- HDR
- VFR
- duration
- device performance
- number of simultaneous layers

Proxy ladder:

```text
Original
 â†“
1080p
 â†“
720p
 â†“
540p
 â†“
480p
```

Proxy generation must happen in the background.

Original media remains untouched.

---

# 20. Camera Architecture

Use CameraX.

Required camera features:

- front camera
- back camera
- preview
- video recording
- autofocus
- tap-to-focus
- exposure control where supported
- zoom
- torch
- camera switching
- camera capability detection
- concurrent camera support

---

# 21. Two-Camera Architecture

The application supports a maximum of two simultaneous physical cameras in v1.

Typical configuration:

```text
Front Camera
+
Back Camera
```

CameraX Concurrent Camera should be used where the device supports it.

If concurrent camera is unsupported:

- show the limitation
- do not fake two cameras
- offer single-camera recording
- preserve the project

The application must detect actual device capability.

---

# 22. Real Hardware Flashlight / Torch

The flashlight feature must control the actual camera hardware torch.

Use CameraX camera control.

Before showing the torch button:

```text
hasFlashUnit()
```

must be checked.

States:

```text
AVAILABLE_OFF
ON
OFF
UNAVAILABLE
ERROR
```

Rear camera:

- real hardware torch if available.

Front camera:

- use real torch only if the device exposes one.

If the front camera has no physical flash:

```text
SCREEN LIGHT
```

may be offered as a software fallback.

Never label screen illumination as hardware flash.

---

# 23. Camera Recording

Camera recordings should be preserved separately when practical.

For a two-camera take:

```text
take/
 â”œâ”€â”€ composite.mp4
 â”œâ”€â”€ camera_front.mp4
 â”œâ”€â”€ camera_back.mp4
 â””â”€â”€ take.json
```

This allows later reconstruction without depending entirely on the live preview recording.

---

# 24. Camera Audio

Each camera audio source must be independently controllable.

Controls:

- microphone enabled
- microphone disabled
- volume
- mute
- input selection
- monitoring policy

Avoid recording the same microphone twice unless explicitly intended.

---

# 25. Audio Engine

The audio engine must be separate from the UI.

Concept:

```text
Video Audio A â”€â”€â”
Video Audio B â”€â”€â”¤
Camera Mic A â”€â”€â”€â”¤
Camera Mic B â”€â”€â”€â”¤
Music â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
Voiceover â”€â”€â”€â”€â”€â”€â”¤
                â†“
          Audio Mixer
                â†“
       Master Gain/Limiter
                â†“
             Encoder
```

Each source has:

- gain
- mute
- pan where supported
- start offset
- trim
- fade
- volume automation

---

# 26. Audio Synchronization

The audio clock must be monitored against the master timeline.

Long recordings require drift monitoring.

Target:

- normal operation: very small drift
- warning: configurable threshold
- critical: automatic recovery/re-anchor

Never silently stretch or drop large portions of audio.

---

# 27. Audio Enhancements

Future-ready effects:

- fade in
- fade out
- volume automation
- compressor
- limiter
- noise reduction
- EQ
- voice enhancement
- ducking
- side-chain ducking
- normalization

These must be modular.

---

# 28. Text System

Text is a first-class layer.

Required:

- font selection
- size
- weight
- alignment
- color
- opacity
- outline
- shadow
- background
- rotation
- position
- animation hooks

Text should render in the GPU compositor where practical.

---

# 29. Image System

Images support:

- PNG
- JPEG
- WebP
- Android-supported bitmap formats

Required:

- crop
- scale
- rotate
- opacity
- position
- rounded corners
- shadow
- border

Large images should be decoded efficiently and downsampled when appropriate.

---

# 30. Video Effects

Architecture must support:

- brightness
- contrast
- saturation
- exposure
- temperature
- tint
- blur
- sharpen
- grayscale
- vignette
- opacity
- crop
- rotate
- scale

Effects should use GPU shaders where possible.

---

# 31. Background System

Project backgrounds:

- solid color
- gradient
- image
- blurred source
- transparent where format permits

Background is a render layer below media.

---

# 32. Crop / Fit Modes

Every visual layer supports:

```text
FIT
FILL
STRETCH
CROP
CUSTOM
```

Default:

```text
FIT
```

No accidental stretching.

---

# 33. Rotation

Layer rotation must support:

```text
0Â°
90Â°
180Â°
270Â°
custom angle
```

Source metadata rotation must be normalized correctly.

A portrait phone video must never unexpectedly appear sideways.

---

# 34. Undo / Redo

Implement a command-based history system.

Commands include:

- add layer
- delete layer
- move
- resize
- rotate
- crop
- reorder
- rename
- visibility
- mute
- volume
- source replacement
- effect changes

Undo and redo must be deterministic.

Avoid storing massive media frames in history.

---

# 35. Project Persistence

Use two storage systems.

## Room

For:

- project index
- asset index
- export jobs
- proxy jobs
- recent projects
- recovery metadata

## Project JSON

For:

- exact editor state
- canvas
- layers
- timeline
- effects
- audio
- export configuration

Example:

```text
project/
 â”œâ”€â”€ project.json
 â”œâ”€â”€ snapshots/
 â”œâ”€â”€ thumbnails/
 â””â”€â”€ metadata/
```

---

# 36. Project Schema Versioning

Every project begins with:

```json
{
  "schemaVersion": 1
}
```

Never assume the schema will remain unchanged.

Migration pipeline:

```text
v1
 â†“
Migration
 â†“
v2
 â†“
Migration
 â†“
v3
```

A future application must be able to open older projects whenever migration is possible.

---

# 37. Autosave

Autosave must be:

- debounced
- atomic
- versioned
- crash-safe

Suggested behavior:

- save after important state changes
- debounce rapid drag operations
- save immediately after major commands
- save before leaving the editor
- save recovery snapshot periodically

---

# 38. Crash Recovery

Maintain:

```text
last_safe_project.json
```

and rotating snapshots.

On startup:

1. detect unfinished session
2. validate snapshot
3. recover newest valid state
4. report recovery to user
5. never delete originals

Recovery attempts must be bounded.

---

# 39. Export Architecture

There are two export classes.

## Class A â€” Media3 Transformer

Use for operations that Transformer handles efficiently:

- trimming
- scaling
- rotation
- effects
- audio processing
- transcoding
- supported compositions

## Class B â€” Native custom compositor

Use for complex multi-layer compositions requiring:

- many simultaneous layers
- custom GPU effects
- independent layer timelines
- complex PiP geometry
- custom transitions
- precise composition control

Pipeline:

```text
Original Media
     â†“
Media3 / MediaCodec Decoders
     â†“
GPU Compositor
     â†“
MediaCodec Encoder
     â†“
Muxer
     â†“
Final MP4
```

---

# 40. H.264 / AVC

H.264 is the default compatibility codec.

Reasons:

- broad Android support
- strong hardware decoder support
- strong hardware encoder support
- excellent compatibility
- generally lower complexity

Default export:

```text
H.264 + AAC
```

---

# 41. H.265 / HEVC

H.265 must be supported as an optional export codec.

It is valuable because it can provide:

- smaller files at similar visual quality
- efficient high-resolution storage
- good 4K use cases

But H.265 is NOT automatically smoother.

Preview smoothness depends more on:

- hardware decoder
- GPU compositor
- memory bandwidth
- frame scheduling
- thermal state
- layer count
- resolution

H.265 can require more computational resources.

Therefore:

```text
H.264 = Compatibility / Performance default
H.265 = Smaller-file / High-efficiency option
```

---

# 42. Smart Codec Mode

Add:

```text
Codec

â— Smart
â—‹ H.264 / AVC
â—‹ H.265 / HEVC
```

Smart mode evaluates:

- encoder availability
- decoder availability
- resolution
- FPS
- device hardware
- thermal condition
- memory
- requested quality
- export target

If HEVC is unavailable or unreliable:

```text
HEVC â†’ H.264 fallback
```

The fallback must be reported to the user.

Never silently claim that HEVC was used when it was not.

---

# 43. Codec Capability Detection

Before enabling H.265 export, verify actual MediaCodec capability.

Check:

- encoder existence
- supported MIME type
- supported resolutions
- supported frame rates
- supported bitrate range
- supported profiles/levels where needed
- hardware/software implementation
- color format

Do not assume that an Android device supports every advertised codec.

---

# 44. Export Quality Presets

Provide:

```text
Fast
Balanced
High Quality
Maximum Quality
Custom
```

For H.264 and HEVC, quality parameters must be codec-specific.

Never reuse H.264 bitrate assumptions blindly for HEVC.

---

# 45. Resolution Presets

Landscape:

- 854Ã—480
- 1280Ã—720
- 1920Ã—1080
- 2560Ã—1440
- 3840Ã—2160

Portrait:

- 480Ã—854
- 720Ã—1280
- 1080Ã—1920
- 1440Ã—2560
- 2160Ã—3840

Square:

- 1080Ã—1080
- 1440Ã—1440
- 2160Ã—2160

Custom output is allowed when encoder limits permit it.

---

# 46. Export Preflight

Before export:

1. validate project
2. validate all source URIs
3. verify source permissions
4. verify codecs
5. verify encoder
6. verify disk space
7. estimate temporary space
8. validate output directory
9. validate resolution
10. validate FPS
11. validate audio configuration
12. validate HDR mode
13. validate all layer references

Do not begin an impossible export.

---

# 47. Export Job State Machine

```text
QUEUED
  â†“
PREPARING
  â†“
ENCODING
  â†“
MUXING
  â†“
VALIDATING
  â†“
COMPLETED
```

Failure:

```text
ENCODING
 â†“
RECOVERING
 â†“
RETRY
 â†“
FALLBACK
 â†“
FAILED
```

Cancellation:

```text
RUNNING
 â†“
CANCELLING
 â†“
CANCELLED
```

No infinite retries.

---

# 48. WorkManager

Long-running exports should use WorkManager.

Work must:

- survive activity destruction
- survive process recreation where possible
- report progress
- support cancellation
- update notification
- persist job state
- clean temporary files

The UI observes job state from persistent storage.

---

# 49. Export Progress

Show:

- percentage
- current stage
- elapsed time
- estimated remaining time
- encoding FPS
- speed
- output size
- current codec
- current resolution

Progress must never move backward.

---

# 50. Cancellation

Cancellation flow:

```text
Request cancel
 â†“
stop encoder gracefully
 â†“
wait bounded time
 â†“
force stop if necessary
 â†“
delete temporary files
 â†“
preserve project
 â†“
preserve originals
 â†“
mark CANCELLED
```

Never leave half-finished files presented as successful exports.

---

# 51. Output Validation

After export, validate with Android media APIs.

Check:

- file exists
- file size > 0
- video stream exists
- audio stream when expected
- duration
- width
- height
- frame rate
- codec
- container
- decodability

Only after validation should the result become:

```text
COMPLETED
```

---

# 52. Storage Safety

Original files are immutable.

The app must never:

- overwrite originals
- rename originals
- move originals
- delete originals
- edit original bytes

All generated data belongs to application-managed locations.

---

# 53. Memory Management

Android devices may have limited RAM.

Never load an entire video into memory.

Use:

- streaming decoders
- Surface-based video rendering
- bounded queues
- bitmap downsampling
- cache limits
- proxy media
- lifecycle-aware resource release

Monitor:

- heap usage
- available memory
- allocation pressure

---

# 54. Thermal Management

Monitor Android thermal state where available.

If thermal pressure rises:

```text
preview FPS â†“
preview resolution â†“
proxy quality â†“
GPU workload â†“
```

Do not lower final export quality automatically.

---

# 55. Battery Policy

Long export operations may consume significant power.

Show:

- estimated export workload
- battery state
- charging state

For very long exports, recommend charging.

Do not force the user to charge unless required by a platform restriction.

---

# 56. Background Export

If Android allows the job to continue:

- WorkManager continues the job
- notification displays progress
- editor may be closed
- project remains safe

If the OS stops the process, the job state must recover safely.

---

# 57. UI Architecture

Use:

```text
Composable
   â†“
ViewModel
   â†“
Use Case
   â†“
Repository
   â†“
Data Source
```

UI events flow upward.

State flows downward.

Example:

```text
User taps mute
   â†“
EditorScreen
   â†“
EditorViewModel
   â†“
SetLayerMuteUseCase
   â†“
ProjectRepository
   â†“
Room / Project JSON
   â†“
StateFlow
   â†“
Compose UI
```

---

# 58. Compose Rules

Compose must not:

- decode video
- run MediaCodec
- perform heavy file I/O
- render every video frame
- perform export logic
- own long-lived media resources directly

Compose owns:

- controls
- menus
- panels
- buttons
- sliders
- dialogs
- timeline UI
- editor state presentation

---

# 59. Editor Screen Layout

Landscape:

```text
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ TOP TOOLBAR                                  â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ MEDIA/LAYERS â”‚     PREVIEW       â”‚ PROPERTIESâ”‚
â”‚              â”‚                   â”‚           â”‚
â”‚              â”‚                   â”‚           â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ TIMELINE                                      â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ STATUS / PERFORMANCE                          â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

Portrait:

```text
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ TOP BAR             â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚                     â”‚
â”‚ PREVIEW             â”‚
â”‚                     â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ QUICK TOOLS         â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ LAYERS              â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ TIMELINE            â”‚
â”œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ STATUS              â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

---

# 60. Mobile Editing UX

Important actions must be reachable with one hand where possible.

Use:

- bottom sheets
- contextual toolbars
- gesture controls
- large touch targets
- pinch zoom
- two-finger canvas navigation
- long press menus

Avoid putting every setting on screen simultaneously.

---

# 61. Timeline UX

Timeline must support:

- horizontal scroll
- pinch zoom
- playhead
- layer lanes
- clips
- visibility ranges
- audio waveforms
- markers
- events
- snapping
- seek
- trim
- split

Future-ready:

- transitions
- keyframes
- beat markers

---

# 62. Timeline Precision

Internally store time in microseconds or another high-precision integer timebase.

Do not use floating-point seconds as the authoritative storage format.

Convert to/from UI seconds only at boundaries.

---

# 63. Clip Editing

Required:

- trim start
- trim end
- split
- duplicate
- delete
- move
- ripple option
- freeze frame
- speed

All operations must be undoable.

---

# 64. Speed Control

Support:

```text
0.25Ã—
0.5Ã—
0.75Ã—
1Ã—
1.25Ã—
1.5Ã—
2Ã—
4Ã—
```

Architecture should allow custom speeds later.

Audio behavior:

- preserve pitch when supported
- or explicitly provide pitch-shift behavior

---

# 65. Freeze Frame

A freeze-frame operation creates a stable visual segment.

It must not pause the entire composition.

Example:

```text
Layer A:
video â†’ freeze â†’ video

Layer B:
continuous video
```

---

# 66. Transitions

Future transition engine:

- fade
- crossfade
- slide
- zoom
- wipe
- custom shader

Transitions must operate on timeline segments rather than altering source files.

---

# 67. Project Templates

Add future template support.

Templates can define:

- aspect ratio
- layer layout
- default text
- background
- camera positions
- audio buses

Templates must not contain private user media references unless explicitly copied.

---

# 68. Thumbnail System

Generate thumbnails for:

- projects
- videos
- images
- timeline clips

Use caching.

Do not regenerate thumbnails every screen recomposition.

---

# 69. Media Metadata Cache

Cache probe information.

Key by:

- URI
- size where available
- modification metadata where available
- content identity when feasible

Invalidate when source identity changes.

---

# 70. Permissions

Required runtime permissions depend on implemented features.

Likely:

- camera
- microphone
- notifications on Android versions where required for foreground/background job UX

Avoid unnecessary permissions.

Do not request storage permissions when Storage Access Framework is sufficient.

---

# 71. Privacy

The app is local-first.

Do not transmit:

- videos
- audio
- photos
- project files
- camera frames
- microphone data

to remote servers.

No analytics should be required for normal operation.

---

# 72. Security

Use content URIs rather than arbitrary filesystem paths.

Validate:

- URI permissions
- MIME types
- media metadata
- project schema
- serialized data

Never execute imported files as code.

---

# 73. Error Taxonomy

Create a common error model.

Examples:

```text
MEDIA_NOT_FOUND
MEDIA_PERMISSION_LOST
UNSUPPORTED_CODEC
DECODER_UNAVAILABLE
ENCODER_UNAVAILABLE
CAMERA_UNAVAILABLE
CAMERA_PERMISSION_DENIED
TORCH_UNAVAILABLE
CONCURRENT_CAMERA_UNSUPPORTED
INSUFFICIENT_STORAGE
EXPORT_FAILED
EXPORT_CANCELLED
PROJECT_CORRUPT
PROJECT_MIGRATION_FAILED
OUT_OF_MEMORY
THERMAL_LIMIT
```

Every error should have:

- technical ID
- user message
- recovery suggestion
- log metadata

---

# 74. Self-Healing Strategy

Examples:

### Camera resolution fails

```text
4K
 â†“
1080p
 â†“
720p
 â†“
480p
```

### HEVC encoder unavailable

```text
HEVC
 â†“
H.264
```

only when Smart mode allows fallback.

### Preview too heavy

```text
Original
 â†“
Proxy
 â†“
Lower preview quality
```

### Export fails because of hardware encoder

```text
Hardware encoder
 â†“
software encoder
```

if supported and safe.

All fallbacks must be bounded and visible.

---

# 75. Diagnostics Screen

Include a developer/user diagnostics page.

Show:

- Android version
- device model
- CPU ABI
- RAM
- available storage
- thermal state
- CameraX version
- Media3 version
- decoder capabilities
- encoder capabilities
- H.264 support
- HEVC support
- supported resolutions
- supported FPS
- concurrent camera support
- torch support
- OpenGL ES version
- project status
- last export error

Provide:

```text
COPY DIAGNOSTICS
```

---

# 76. Performance Dashboard

Optional advanced mode:

```text
Preview FPS
Dropped Frames
GPU Time
CPU Time
Decoder Queue
Audio Drift
Memory
Thermal
Active Layers
Proxy State
```

Normal users see simplified status.

Developers can enable detailed diagnostics.

---

# 77. Logging

Use structured logging.

Each log entry includes:

- timestamp
- severity
- subsystem
- operation
- project ID
- job ID where applicable
- error code
- message

Subsystems:

```text
EDITOR
MEDIA
PLAYER
CAMERA
AUDIO
GPU
EXPORT
STORAGE
DATABASE
RECOVERY
WORKER
```

---

# 78. Dependency Rules

Keep dependencies minimal.

Every new dependency must answer:

1. Why is it needed?
2. Is AndroidX already capable of this?
3. Does it increase APK size?
4. Does it introduce native binaries?
5. Does it introduce licensing concerns?
6. Is it maintained?
7. Can it affect performance?

Do not add libraries merely for convenience.

---

# 79. Suggested Gradle Module Structure

Start with:

```text
:app
```

Then split only when boundaries become useful:

```text
:core:model
:core:common
:core:ui
:core:media
:core:graphics
:core:storage

:data:database
:data:repository

:domain:project
:domain:media
:domain:timeline
:domain:camera
:domain:audio
:domain:export

:feature:home
:feature:editor
:feature:media
:feature:camera
:feature:export
:feature:settings

:mediaengine:playback
:mediaengine:compositor
:mediaengine:encoder
:mediaengine:audio
```

Do not create dozens of Gradle modules on day one.

Begin with a clean package structure and split modules when build boundaries provide measurable benefit.

---

# 80. Package Structure

```text
com.rehman.ahmedreactionstudio
â”‚
â”œâ”€â”€ app
â”‚   â”œâ”€â”€ MainActivity.kt
â”‚   â”œâ”€â”€ ReactionStudioApplication.kt
â”‚   â””â”€â”€ navigation
â”‚
â”œâ”€â”€ core
â”‚   â”œâ”€â”€ common
â”‚   â”œâ”€â”€ model
â”‚   â”œâ”€â”€ ui
â”‚   â”œâ”€â”€ graphics
â”‚   â”œâ”€â”€ media
â”‚   â”œâ”€â”€ storage
â”‚   â”œâ”€â”€ permissions
â”‚   â”œâ”€â”€ diagnostics
â”‚   â””â”€â”€ logging
â”‚
â”œâ”€â”€ data
â”‚   â”œâ”€â”€ database
â”‚   â”œâ”€â”€ repository
â”‚   â”œâ”€â”€ datasource
â”‚   â””â”€â”€ serialization
â”‚
â”œâ”€â”€ domain
â”‚   â”œâ”€â”€ project
â”‚   â”œâ”€â”€ timeline
â”‚   â”œâ”€â”€ media
â”‚   â”œâ”€â”€ camera
â”‚   â”œâ”€â”€ audio
â”‚   â””â”€â”€ export
â”‚
â”œâ”€â”€ feature
â”‚   â”œâ”€â”€ home
â”‚   â”œâ”€â”€ project
â”‚   â”œâ”€â”€ editor
â”‚   â”œâ”€â”€ media
â”‚   â”œâ”€â”€ camera
â”‚   â”œâ”€â”€ timeline
â”‚   â”œâ”€â”€ audio
â”‚   â”œâ”€â”€ export
â”‚   â””â”€â”€ settings
â”‚
â”œâ”€â”€ mediaengine
â”‚   â”œâ”€â”€ playback
â”‚   â”œâ”€â”€ compositor
â”‚   â”œâ”€â”€ decoder
â”‚   â”œâ”€â”€ encoder
â”‚   â”œâ”€â”€ effects
â”‚   â”œâ”€â”€ audio
â”‚   â””â”€â”€ transformer
â”‚
â””â”€â”€ worker
    â”œâ”€â”€ ExportWorker.kt
    â”œâ”€â”€ ProxyWorker.kt
    â”œâ”€â”€ ThumbnailWorker.kt
    â””â”€â”€ CleanupWorker.kt
```

---

# 81. Data Model

Core entities:

```text
Project
Asset
Layer
Timeline
TimelineEvent
AudioTrack
CameraSource
ExportJob
ProxyJob
Snapshot
Template
```

Use immutable domain models.

Database entities should be separate from domain models.

---

# 82. Example Project Model

```kotlin
@Serializable
data class ProjectDocument(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val canvas: CanvasSpec,
    val layers: List<Layer>,
    val timeline: TimelineDocument,
    val audio: AudioDocument,
    val export: ExportSettings
)
```

---

# 83. Export Settings

```kotlin
@Serializable
data class ExportSettings(
    val container: ContainerFormat = ContainerFormat.MP4,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val quality: QualityPreset = QualityPreset.BALANCED,
    val hdrMode: HdrMode = HdrMode.AUTO
)
```

---

# 84. Codec Enum

```kotlin
enum class VideoCodec {
    H264,
    HEVC
}
```

Future:

```text
AV1
```

but only after capability/performance testing.

---

# 85. Media Capability Service

Create:

```text
MediaCapabilityRepository
```

It reports:

```text
decoder capabilities
encoder capabilities
supported MIME types
hardware acceleration
maximum resolution
supported FPS
HDR support
HEVC support
```

The UI must consume capability data rather than guessing.

---

# 86. Camera Capability Service

Reports:

```text
front camera
back camera
camera count
concurrent support
torch
zoom
AF
AE
AWB
supported resolutions
supported FPS
```

---

# 87. Real Hardware Testing

The application cannot be considered camera-complete from emulator testing alone.

Required physical-device testing:

- front camera
- rear camera
- torch
- microphone
- camera switching
- concurrent cameras
- recording
- rotation
- thermal behavior
- low battery
- low storage

---

# 88. Emulator Policy

Emulator is useful for:

- UI
- navigation
- project persistence
- timeline
- export state
- unit tests

Physical hardware is required for:

- CameraX behavior
- torch
- encoder performance
- decoder performance
- thermal performance
- real camera concurrency
- hardware HEVC

---

# 89. Minimum Device Strategy

The app must remain usable on weaker Android devices.

Adaptive strategy:

```text
High-end device
â†’ full preview

Mid-range device
â†’ optimized preview

Low-end device
â†’ proxy + lower preview FPS/resolution

Very low memory
â†’ safe preview + sequential processing
```

Never promise identical performance across devices.

---

# 90. Testing Pyramid

## Unit tests

Test:

- geometry
- aspect conversion
- timeline
- event reconstruction
- undo/redo
- project migration
- codec selection
- export validation
- camera capability policy

## Integration tests

Test:

- Room
- project persistence
- Media3
- WorkManager
- export pipeline
- proxy pipeline

## UI tests

Test:

- aspect switching
- orientation
- layer controls
- PiP gestures
- timeline controls
- export screen

## Physical tests

Test:

- cameras
- torch
- audio
- H.264
- HEVC
- long recordings
- long exports
- thermal behavior

---

# 91. Critical Acceptance Tests

### AC-01

Selecting 16:9 automatically enters landscape editor mode where supported.

### AC-02

Selecting 9:16 automatically enters portrait editor mode where supported.

### AC-03

Switching aspect ratio preserves valid layer geometry.

### AC-04

One layer can pause while every other layer continues.

### AC-05

One layer can hide while continuing to play.

### AC-06

Eight PiP handles work with touch.

### AC-07

At least five layers can be previewed within device capability.

### AC-08

Front camera works.

### AC-09

Rear camera works.

### AC-10

Rear hardware torch works when available.

### AC-11

Front hardware torch is used only when physically available.

### AC-12

Front screen-light fallback never claims to be hardware flash.

### AC-13

Two simultaneous cameras work on supported devices.

### AC-14

Unsupported concurrent cameras are reported honestly.

### AC-15

H.264 export works on supported devices.

### AC-16

HEVC export works when the hardware encoder supports it.

### AC-17

Smart codec mode falls back safely.

### AC-18

Preview quality degradation never changes export quality.

### AC-19

Export cancellation leaves originals untouched.

### AC-20

App restart recovers the last safe project state.

### AC-21

Export output is validated before success is reported.

### AC-22

No original media is modified.

---

# 92. Phase 0 â€” Architecture and Repository

Tasks:

- [ ] Create Android Studio project.
- [ ] Configure Kotlin.
- [ ] Configure Compose.
- [ ] Configure Material 3.
- [ ] Configure Hilt.
- [ ] Configure Room.
- [ ] Configure DataStore.
- [ ] Configure Kotlinx Serialization.
- [ ] Configure Media3.
- [ ] Configure CameraX.
- [ ] Configure WorkManager.
- [ ] Establish package structure.
- [ ] Establish version catalog.
- [ ] Establish lint/format rules.
- [ ] Create CI-ready test structure.

Exit criteria:

- app builds
- app launches
- no unnecessary dependencies
- architecture skeleton compiles

---

# 93. Phase 1 â€” Home / Project System

Tasks:

- [ ] Home screen.
- [ ] Create project.
- [ ] Rename project.
- [ ] Open project.
- [ ] Delete project.
- [ ] Duplicate project.
- [ ] Recent projects.
- [ ] Project thumbnails.
- [ ] Room project index.
- [ ] Project JSON.
- [ ] Autosave.
- [ ] Snapshots.
- [ ] Recovery.

Exit criteria:

A project survives app restart without corruption.

---

# 94. Phase 2 â€” Canvas / Orientation

Tasks:

- [ ] 16:9.
- [ ] 9:16.
- [ ] 1:1.
- [ ] Automatic orientation.
- [ ] Normalized coordinates.
- [ ] Responsive preview.
- [ ] Background.
- [ ] Safe-area/inset handling.
- [ ] Orientation transition animation.
- [ ] Geometry preservation tests.

Exit criteria:

Switching between all supported aspect ratios never corrupts layers.

---

# 95. Phase 3 â€” Media Import

Tasks:

- [ ] Video picker.
- [ ] Image picker.
- [ ] Audio picker.
- [ ] URI permission persistence.
- [ ] Metadata extraction.
- [ ] Thumbnail generation.
- [ ] Media library.
- [ ] Asset database.
- [ ] Missing-media detection.
- [ ] Relink flow.

Exit criteria:

Imported assets survive app restart and can be relinked.

---

# 96. Phase 4 â€” Playback

Tasks:

- [ ] Media3 integration.
- [ ] Player lifecycle.
- [ ] Per-layer playback.
- [ ] Pause.
- [ ] Seek.
- [ ] Volume.
- [ ] Mute.
- [ ] Speed.
- [ ] Repeat.
- [ ] Player cleanup.

Exit criteria:

Multiple independent video layers play without shared-state bugs.

---

# 97. Phase 5 â€” GPU Compositor

Tasks:

- [ ] OpenGL ES context.
- [ ] Preview Surface.
- [ ] Video texture.
- [ ] Image texture.
- [ ] Layer renderer.
- [ ] Transform matrix.
- [ ] Crop.
- [ ] Opacity.
- [ ] Z-order.
- [ ] Background.
- [ ] Shader framework.
- [ ] Render loop.
- [ ] Performance instrumentation.

Exit criteria:

Five-layer test composition renders correctly on a supported test device.

---

# 98. Phase 6 â€” PiP Editor

Tasks:

- [ ] Selection.
- [ ] Drag.
- [ ] Eight handles.
- [ ] Resize.
- [ ] Rotate.
- [ ] Crop.
- [ ] Snap.
- [ ] Center.
- [ ] Lock.
- [ ] Duplicate.
- [ ] Delete.
- [ ] Presets.
- [ ] Undo/redo.

Exit criteria:

Touch-first editing is precise and stable.

---

# 99. Phase 7 â€” Timeline

Tasks:

- [ ] Master clock.
- [ ] Timeline model.
- [ ] Event model.
- [ ] Playhead.
- [ ] Scrubbing.
- [ ] Clip ranges.
- [ ] Independent layer state.
- [ ] Hide/show.
- [ ] Pause/resume.
- [ ] Seek events.
- [ ] Timeline persistence.
- [ ] Timeline reconstruction tests.

Exit criteria:

A recorded sequence can be reconstructed exactly from project state.

---

# 100. Phase 8 â€” Camera

Tasks:

- [ ] Camera permission.
- [ ] Camera enumeration.
- [ ] Front camera.
- [ ] Rear camera.
- [ ] Camera switching.
- [ ] Camera preview.
- [ ] Focus.
- [ ] Exposure.
- [ ] Zoom.
- [ ] Torch.
- [ ] Torch state.
- [ ] Camera recording.
- [ ] Concurrent camera detection.
- [ ] Two-camera support.
- [ ] Device-lost recovery.

Exit criteria:

Real physical-device camera test passes.

---

# 101. Phase 9 â€” Audio

Tasks:

- [ ] Audio tracks.
- [ ] Mixer.
- [ ] Gain.
- [ ] Mute.
- [ ] Volume automation.
- [ ] Audio offsets.
- [ ] Camera microphones.
- [ ] Waveform generation.
- [ ] Master meter.
- [ ] Clipping detection.
- [ ] Drift measurement.

Exit criteria:

30-minute test remains synchronized within defined tolerance.

---

# 102. Phase 10 â€” Proxy System

Tasks:

- [ ] Media complexity analysis.
- [ ] Device capability analysis.
- [ ] Proxy decision engine.
- [ ] 1080p proxy.
- [ ] 720p proxy.
- [ ] 540p/480p proxy.
- [ ] Background proxy worker.
- [ ] Proxy cache.
- [ ] Proxy replacement/removal.
- [ ] Original integrity checks.

Exit criteria:

Heavy media becomes playable without modifying originals.

---

# 103. Phase 11 â€” Export Engine

Tasks:

- [ ] Media3 Transformer integration.
- [ ] Custom composition engine.
- [ ] MediaCodec encoder.
- [ ] H.264.
- [ ] HEVC.
- [ ] AAC.
- [ ] resolution presets.
- [ ] FPS presets.
- [ ] quality presets.
- [ ] export preflight.
- [ ] validation.
- [ ] cancellation.

Exit criteria:

H.264 and supported-device HEVC exports pass validation.

---

# 104. Phase 12 â€” WorkManager

Tasks:

- [ ] ExportWorker.
- [ ] ProxyWorker.
- [ ] ThumbnailWorker.
- [ ] CleanupWorker.
- [ ] Persistent job state.
- [ ] Progress.
- [ ] Notifications.
- [ ] Cancellation.
- [ ] Recovery.

Exit criteria:

Closing the editor does not corrupt an active export.

---

# 105. Phase 13 â€” Effects

Tasks:

- [ ] brightness
- [ ] contrast
- [ ] saturation
- [ ] exposure
- [ ] blur
- [ ] sharpen
- [ ] grayscale
- [ ] vignette
- [ ] opacity
- [ ] crop
- [ ] rotation
- [ ] text
- [ ] shapes
- [ ] stickers

Exit criteria:

Effects preview and export consistently.

---

# 106. Phase 14 â€” Hardening

Tasks:

- [ ] low-RAM tests
- [ ] thermal tests
- [ ] low-storage tests
- [ ] permission revocation
- [ ] source removal
- [ ] camera loss
- [ ] encoder failure
- [ ] decoder failure
- [ ] app process death
- [ ] project corruption
- [ ] export cancellation
- [ ] long timeline tests
- [ ] 2-hour soak test

Exit criteria:

No known critical data-loss path remains.

---

# 107. Phase 15 â€” Release

Tasks:

- [ ] release build
- [ ] ProGuard/R8 verification
- [ ] APK size analysis
- [ ] startup benchmark
- [ ] memory benchmark
- [ ] preview benchmark
- [ ] export benchmark
- [ ] H.264 matrix
- [ ] HEVC matrix
- [ ] camera matrix
- [ ] permissions review
- [ ] privacy review
- [ ] crash review
- [ ] final documentation

---

# 108. Performance Budget

Targets are device-class dependent.

The app should aim for:

```text
UI input latency: low
Preview: 30/60 FPS when hardware permits
Audio drift: minimal
Seek: responsive
Memory: bounded
Export: hardware accelerated when available
```

Do not promise fixed FPS on every phone.

Report actual capability.

---

# 109. Quality Principle

The application follows:

```text
Correctness
>
A/V synchronization
>
Responsiveness
>
Preview quality
>
Extra visual effects
```

A lower-quality preview is acceptable.

A corrupted export is not.

A desynchronized recording is not.

Data loss is never acceptable.

---

# 110. Non-Negotiable Rules

1. Native Android only.
2. Kotlin first.
3. Java only when required.
4. No Python.
5. No FastAPI.
6. No WebView editor.
7. No Node/npm.
8. No cloud requirement.
9. Original media is immutable.
10. Project state is versioned.
11. Layer playback is independent.
12. Hide/show is separate from pause/play.
13. Use monotonic timing.
14. Preview and export are separate systems.
15. Preview quality may degrade.
16. Export quality must not silently degrade.
17. Detect hardware capabilities.
18. Never assume HEVC support.
19. Never assume concurrent camera support.
20. Never pretend software screen light is hardware flash.
21. Hardware torch is used only when physically available.
22. Never block the main thread with media processing.
23. Never decode full video frames into Bitmaps every frame.
24. Never put video bytes into Room.
25. No infinite retry loops.
26. All destructive operations are explicit.
27. All exports are validated.
28. All failures are user-visible.
29. All long operations are lifecycle-safe.
30. Physical devices are required for camera/codec sign-off.

---

# 111. Recommended Initial MVP

Do NOT attempt every feature simultaneously.

The first usable MVP should contain:

```text
Project creation
+
16:9 / 9:16 / 1:1
+
automatic orientation
+
video import
+
Media3 playback
+
GPU preview
+
multiple layers
+
PiP drag/resize
+
layer visibility
+
layer mute/volume
+
timeline
+
CameraX front/rear camera
+
real torch
+
single-camera recording
+
H.264 export
+
project autosave
+
crash recovery
```

Then add:

```text
two-camera
+
audio mixer
+
proxy system
+
HEVC
+
advanced effects
+
advanced timeline
+
long-export background jobs
```

This order minimizes architecture risk.

---

# 112. Definition of Done

A feature is not complete merely because it compiles.

It is complete only when:

- implementation exists
- unit tests exist where appropriate
- failure paths are handled
- state survives recreation where required
- performance is acceptable
- physical-device behavior is verified when hardware is involved
- original media remains untouched
- documentation is updated
- acceptance criteria pass

---

# 113. Final Architecture

```text
                    ANDROID APP
                         â”‚
              â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
              â”‚                     â”‚
          COMPOSE UI          MEDIA SURFACE
              â”‚                     â”‚
        ViewModel/UDF          GPU Renderer
              â”‚                     â”‚
          Use Cases             OpenGL ES
              â”‚                     â”‚
        Repositories          Media3 / MediaCodec
          â”‚        â”‚                 â”‚
        Room   Project JSON          â”‚
          â”‚        â”‚                 â”‚
          â””â”€â”€â”€â”€â”¬â”€â”€â”€â”˜                 â”‚
               â”‚                     â”‚
          Persistent State       Export Engine
                                     â”‚
                         â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”´â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
                         â”‚                       â”‚
                  Media3 Transformer       Custom Composer
                         â”‚                       â”‚
                         â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                     â”‚
                              H.264 / HEVC
                                     â”‚
                                    MP4
```

---

# 114. Final Technology Verdict

### Kotlin

**LOCKED.**

Best primary language for this project.

### Java

**Secondary only.**

Use when Android or a library exposes Java-only APIs or when interoperability is beneficial.

### Jetpack Compose

**LOCKED for UI.**

### Media3

**LOCKED for playback and supported editing/export operations.**

### CameraX

**LOCKED for camera hardware.**

### OpenGL ES

**LOCKED for the high-performance multi-layer compositor.**

### MediaCodec

**LOCKED for low-level hardware encoder/decoder control when required.**

### Room

**LOCKED for structured persistent metadata.**

### DataStore

**LOCKED for application preferences/settings.**

### WorkManager

**LOCKED for durable background jobs.**

### H.264

**DEFAULT EXPORT CODEC.**

### H.265/HEVC

**SUPPORTED OPTIONAL EXPORT CODEC.**

### FFmpeg

**NOT the foundation.**

Only consider an FFmpeg integration later if a specific Android-native feature cannot be implemented reliably with Media3/MediaCodec/OpenGL. It must remain optional rather than defining the entire architecture.

---

# 115. Final Product Philosophy

Ahmed Reaction Studio should behave like a serious native video editor:

```text
FAST UI
+
GPU PREVIEW
+
HARDWARE DECODING
+
HARDWARE ENCODING
+
REAL CAMERA HARDWARE
+
REAL TORCH
+
MULTI-LAYER COMPOSITING
+
INDEPENDENT TIMELINES
+
CORRECT AUDIO
+
H.264
+
HEVC
+
AUTOMATIC ORIENTATION
+
CRASH RECOVERY
+
NON-DESTRUCTIVE EDITING
+
LOCAL-FIRST PRIVACY
```

The application must prefer **measurable capability detection over assumptions** and **correctness over flashy features**.

The architecture is intentionally designed so additional effects, transitions, codecs, templates, camera features, and advanced timeline functionality can be added without replacing the core editor.
