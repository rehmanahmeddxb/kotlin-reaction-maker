# Fix plan — smooth playback, correct colour, real saving, flashlight, OBS-grade compression

Reported after the last PR:

1. Local video plays **smooth (no stutter)** but only **~17 fps / 25 ms per frame**.
2. **Colours are inverted / wrong** (red and blue swapped).
3. **Export is not saved** anywhere findable on the phone (double confirmed).
4. Want **flashlight on/off, front + back**, in the radial wheel petals.
5. Want **best quality at very small file size**, like OBS / Bandicam.

Rule taken from the request: *do not touch what already works* — smoothness must
not regress. So every change below is either a pure correctness fix or a change
that strictly removes per-frame work.

---

## 1. Colour inversion — root cause found (exact, not a guess)

`GlUtil.OesToBitmap` fragment shader ends with:

```glsl
gl_FragColor = texture2D(uTex, vTex).bgra;   // ← the bug
```

and the result is read with `glReadPixels(..., GL_RGBA, GL_UNSIGNED_BYTE, buf)`
then pushed with `Bitmap.copyPixelsFromBuffer()` into an `ARGB_8888` bitmap.

Android's `ARGB_8888` is **RGBA in memory byte order** (the name is historical).
`glReadPixels` with `GL_RGBA` already returns exactly R,G,B,A bytes — i.e. the
buffer is *already* in the layout `copyPixelsFromBuffer` wants.

The extra `.bgra` swizzle therefore swaps R and B on every pixel → blue faces,
orange skies. The previous PR added the swizzle to make the *slow* Java fallback
loop cheap and accidentally corrupted the fast path.

**Fix**
* shader → `gl_FragColor = texture2D(uTex, vTex);` (no swizzle at all — also one
  less ALU op per pixel, so it is very slightly *faster*, never slower)
* the Java fallback loop reads `r,g,b,a` in that order to match.

Zero effect on timing/pacing → smoothness untouched.

## 2. 17 fps / 25 ms per frame — the cost is the synchronous read-back

Per preview frame today: decode (GPU, cheap) → blit to FBO (GPU, cheap) →
**`glReadPixels`** → `copyPixelsFromBuffer` → Canvas draw.

`glReadPixels` on GLES2 is a *pipeline stall*: the CPU blocks until the GPU has
finished everything queued, then the pixels are DMA'd back. On a mid-range phone
that is 15–25 ms for a 720p surface — which is exactly the reported 25 ms, and
1000/25 ≈ 40 fps ceiling shared across layers → the observed ~17 fps.

**Fix: asynchronous read-back with GLES3 pixel-buffer objects (PBO), ping-pong.**

* `EglCore` asks for an **OpenGL ES 3** context first, falls back to ES 2.
* When ES3 is available `OesToBitmap` uses two PBOs:
  * frame *n*: `glReadPixels` into PBO A (returns **immediately** — it is a GPU→GPU
    copy, no stall),
  * frame *n*: `glMapBufferRange` PBO B (the one filled last frame, already done)
    and memcpy into the bitmap.
  * swap A/B.
* Net effect: the CPU never waits on the GPU. Read-back cost drops from ~20 ms to
  ~1–3 ms. The only price is **one frame of latency**, which is invisible in a
  reaction editor and cannot introduce stutter (the pacing logic is untouched).
* If anything about ES3/PBO fails at runtime, it falls back to today's exact
  synchronous path — so the worst case is current behaviour.

Second, smaller win, also pure work-removal:
* the RGB swizzle removal above,
* keep everything else (pacing, adaptive scale, per-layer decode size) **exactly**
  as-is, because that is what made it stutter-free.

## 3. Export "not saving anywhere" — rewrite the save path

Current behaviour:
* `runExport` writes to `getExternalFilesDir(Movies)/AhmedStudio/…` →
  `/Android/data/<pkg>/files/Movies/AhmedStudio/`. Most file managers and the
  Gallery **cannot see that folder at all** on Android 11+.
* `UI.publishToGallery` then tries one `MediaStore` insert, and if it throws or
  returns null it silently calls `onDone(null)` — the dialog still says
  "Saved to Gallery" while nothing public exists. On API < 29 it does nothing.
* WebM exports are inserted as `video/mp4` metadata in some paths.

**Fix: one shared, verified saver — `MediaSave.publishVideo()`**

* Encode into `cacheDir` (private, always writable, never leaks a half file).
* Publish:
  * **API ≥ 29** → `MediaStore.Video` insert with
    `RELATIVE_PATH = Movies/AhmedReactionStudio`, `IS_PENDING=1`, stream the
    bytes, `flush`, `IS_PENDING=0`; **verify** the written size by re-opening the
    URI, and delete the row + fall through if it is 0 bytes or throws.
  * **fallback / API < 29** → real public
    `Environment.getExternalStoragePublicDirectory(Movies)/AhmedReactionStudio/`,
    then `MediaScannerConnection.scanFile` so it appears in Gallery immediately.
  * **last resort** → app external Movies dir, and the dialog then says exactly
    that path instead of claiming the Gallery.
* Correct MIME per container (`video/webm` for VP8/VP9).
* The result carries the **real** location + real byte size, and the completion
  dialog shows it. Never again "saved" when nothing was written.
* The same saver is used by the **recorder** (`stopCompositeRecording`) so both
  paths behave identically.
* Request `WRITE_EXTERNAL_STORAGE` on API ≤ 28 before the legacy write.

## 4. Flashlight in the radial wheel (front + back)

* `LiveCamera` gains `hasFlash()` / `torch` state, applied with
  `CaptureRequest.FLASH_MODE = TORCH` on the repeating request (and re-applied
  after a session rebuild / camera switch, so it survives Record-take).
* Front cameras almost never have a flash unit. For those, a **screen flash**:
  the stage draws a bright white border-glow overlay and the window brightness is
  pushed to 1.0 — this is what phone camera apps do for front-facing selfies.
* Radial petals, in the live-camera source ring:
  * `Flash: off / on` (hardware torch) — enabled when the *current* camera has a
    flash unit,
  * `Front light` (screen flash) — always available,
  * both `keepOpen = true` so the wheel stays up while toggling.
* Torch turns off automatically on pause / camera close / layer delete, so the
  LED is never left on.

## 5. OBS / Bandicam-grade compression (big length, few MB)

Current encoder config is the reason files are large:
* `KEY_I_FRAME_INTERVAL = 1` → a keyframe **every second**. Keyframes are ~10×
  the size of a P-frame. OBS/Bandicam use 2–5 s.
* CBR-ish default bitrate mode, no profile/level, no B-frames.
* Fixed bpp table 0.06 / 0.12 / 0.18 applied to every resolution and fps.

**Fix — shared `EncoderConfig` used by exporter AND recorder**

| setting | before | after |
|---|---|---|
| GOP (`I_FRAME_INTERVAL`) | 1 s | **4 s** (2 s for the live recorder, for seekability) |
| bitrate mode | default | **VBR** (`BITRATE_MODE_VBR`, API 21+) |
| B-frames | none | `KEY_MAX_B_FRAMES = 2` where supported (API 29+, H.264/HEVC) |
| profile/level | none | High profile / Main-10-less HEVC Main, best level the encoder reports |
| bpp curve | 0.06/0.12/0.18 flat | resolution-aware: bits/pixel falls as pixels rise (same perceptual quality, far fewer MB at 720p+) |
| codec default | H.264 | **HEVC when the device has an encoder** (~40 % smaller at equal quality), H.264 kept as the compatible choice |
| quality presets | fast / balanced / high | **Tiny · Small · Balanced · High** with the real predicted MB/min shown in the export sheet |

Expected: a 10-minute 720p30 reaction goes from ~1.1 GB to roughly **90–160 MB**
at *better* visual quality, because the bits move from redundant keyframes into
the frames that actually change.

Nothing in this section touches the preview path, so it cannot affect smoothness.

---

## Order of work

1. Colour fix (1 line + fallback loop) — smallest, highest-visibility.
2. PBO async read-back with full ES2 fallback.
3. `MediaSave` + wire exporter/recorder/dialogs to it.
4. `EncoderConfig` + export presets.
5. Flashlight (LiveCamera torch + screen flash + petals).
6. Build via CI, ship the APK.
