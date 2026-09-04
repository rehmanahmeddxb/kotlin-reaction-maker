package com.rehman.ahmedreactionstudio.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Supported canvas aspect ratios (logical canvas from the master plan). */
enum class Aspect(val code: String, val canvasW: Int, val canvasH: Int, val label: String) {
    R169("16:9", 1920, 1080, "16:9 \u00b7 Landscape"),
    R916("9:16", 1080, 1920, "9:16 \u00b7 Portrait"),
    R11("1:1", 1080, 1080, "1:1 \u00b7 Square");

    companion object {
        fun from(code: String): Aspect = entries.firstOrNull { it.code == code } ?: R169
    }
}

enum class LayerType(val label: String) {
    VIDEO("Video"),
    CAMERA("Camera"),
    SCREEN("Screen record"),
    IMAGE("Image"),
    TEXT("Text");

    companion object {
        fun from(s: String): LayerType = entries.firstOrNull { it.name == s } ?: VIDEO
    }
}

/**
 * One layer of the composition.
 *
 * Geometry is fully normalized (independent of canvas pixels):
 *  - cx, cy  : center of the layer rectangle in [0..1] relative to canvas
 *  - wN, hN  : width/height relative to canvas width / canvas height
 *  - rotDeg  : rotation in degrees around the center
 *
 * A video/CAMERA layer keeps an independent playback state (`playing`).
 * `pausedMediaMs` is the media position the layer is frozen at while paused
 * (freeze is separate from master playback, per spec section 14).
 *
 * OBS-style source controls (see docs/OBS_SOURCE_PLAN.md):
 *  - fit   : "fill" = COVER (frame fills its box, edges cropped) or
 *            "fit"  = CONTAIN (whole frame visible, letterboxed in the box).
 *            The "camera cuts out on canvas" bug was a world where only COVER
 *            existed; fit is now a first-class per-source control.
 *  - loop  : video wraps at its end, or holds its last frame (then auto-pauses)
 *  - solo  : audio solo — while any source is soloed, every NON-soloed source
 *            is effectively muted (computed state, nothing is overwritten)
 */
class Layer(
    var id: String = UUID.randomUUID().toString(),
    val type: LayerType = LayerType.VIDEO,
    var name: String = "",
    var relPath: String? = null,          // relative to the project dir; null for TEXT
    var durMs: Long = 0L,                 // source media duration
    var srcW: Int = 0,
    var srcH: Int = 0,
    var srcRotation: Int = 0,
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var wN: Float = 1f,
    var hN: Float = 1f,
    var rotDeg: Float = 0f,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var muted: Boolean = false,
    var solo: Boolean = false,
    var loop: Boolean = false,
    var fit: String = FIT_FILL,
    var volume: Float = 1f,               // 0..1
    var opacity: Float = 1f,              // 0..1
    var playing: Boolean = true,          // independent layer pause semantics
    var pausedMediaMs: Long = 0L,
    var speed: Float = 1f,
    // LIVE CAMERA fields: a CAMERA layer with live = true has no relPath —
    // its frames are pushed into the PreviewEngine by LiveCamera, so the
    // camera composites on the canvas like any other source (no modal screen).
    var live: Boolean = false,
    var camFacing: Int = FACING_FRONT,
    var mirror: Boolean = true,
    // TEXT layer fields
    var text: String = "",
    var textColor: Int = 0xFFFFFFFF.toInt(),
    var fontSizeN: Float = 0.08f,          // normalized to canvas height
    var shadow: Boolean = true,
    var addedAt: Long = System.currentTimeMillis()
) {
    fun isVideoLike(): Boolean = type == LayerType.VIDEO || type == LayerType.CAMERA || type == LayerType.SCREEN

    /** A live camera feed on the canvas (frames pushed, nothing on disk). */
    fun isLive(): Boolean = live && type == LayerType.CAMERA

    /** Clip-backed sources: the ones the clock, audio and exporter can seek. */
    fun isClip(): Boolean = isVideoLike() && !isLive()

    /**
     * Text layers resize freely; media layers never change aspect ratio.
     * (A helper rather than `type == LayerType.TEXT` because inside a View
     * subclass the simple name `LayerType` resolves to `View.LayerType`.)
     */
    fun isText(): Boolean = type == LayerType.TEXT

    fun clone(): Layer {
        val l = Layer(id, type, name, relPath, durMs, srcW, srcH, srcRotation, cx, cy, wN, hN,
            rotDeg, visible, locked, muted, solo, loop, fit, volume, opacity, playing,
            pausedMediaMs, speed, live, camFacing, mirror,
            text, textColor, fontSizeN, shadow, addedAt)
        return l
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("type", type.name); o.put("name", name)
        relPath?.let { o.put("relPath", it) }
        o.put("durMs", durMs); o.put("srcW", srcW); o.put("srcH", srcH); o.put("srcRotation", srcRotation)
        o.put("cx", cx.toDouble()); o.put("cy", cy.toDouble())
        o.put("wN", wN.toDouble()); o.put("hN", hN.toDouble()); o.put("rotDeg", rotDeg.toDouble())
        o.put("visible", visible); o.put("locked", locked); o.put("muted", muted)
        o.put("solo", solo); o.put("loop", loop); o.put("fit", fit)
        o.put("volume", volume.toDouble()); o.put("opacity", opacity.toDouble())
        o.put("playing", playing); o.put("pausedMediaMs", pausedMediaMs)
        o.put("speed", speed.toDouble())
        if (type == LayerType.CAMERA) {
            o.put("live", live); o.put("camFacing", camFacing); o.put("mirror", mirror)
        }
        if (type == LayerType.TEXT) {
            o.put("text", text); o.put("textColor", textColor.toLong())
            o.put("fontSizeN", fontSizeN.toDouble()); o.put("shadow", shadow)
        }
        o.put("addedAt", addedAt)
        return o
    }

    companion object {
        /** COVER: frame fills its box, cropped at the edges (full-bleed mains). */
        const val FIT_FILL = "fill"
        /** CONTAIN: whole frame visible, letterboxed inside the box (never cuts). */
        const val FIT_FIT = "fit"

        /** camera facing, mirroring CameraCharacteristics.LENS_FACING_* values */
        const val FACING_BACK = 1
        const val FACING_FRONT = 0

        fun fromJson(o: JSONObject): Layer {
            val t = LayerType.from(o.optString("type", "VIDEO"))
            // Old projects have no "fit" key. Camera takes are exactly what
            // users complained about being cropped, so they default to the
            // never-cut CONTAIN mode; everything else keeps the old COVER look.
            val defaultFit = if (t == LayerType.CAMERA) FIT_FIT else FIT_FILL
            val l = Layer(
                id = o.optString("id", UUID.randomUUID().toString()),
                type = t,
                name = o.optString("name", t.label),
                relPath = if (o.has("relPath")) o.getString("relPath") else null,
                durMs = o.optLong("durMs"),
                srcW = o.optInt("srcW"), srcH = o.optInt("srcH"), srcRotation = o.optInt("srcRotation"),
                cx = o.optDouble("cx", 0.5).toFloat(), cy = o.optDouble("cy", 0.5).toFloat(),
                wN = o.optDouble("wN", 1.0).toFloat(), hN = o.optDouble("hN", 1.0).toFloat(),
                rotDeg = o.optDouble("rotDeg", 0.0).toFloat(),
                visible = o.optBoolean("visible", true), locked = o.optBoolean("locked", false),
                muted = o.optBoolean("muted", false),
                solo = o.optBoolean("solo", false),
                loop = o.optBoolean("loop", false),
                fit = o.optString("fit", defaultFit),
                volume = o.optDouble("volume", 1.0).toFloat(), opacity = o.optDouble("opacity", 1.0).toFloat(),
                playing = o.optBoolean("playing", true), pausedMediaMs = o.optLong("pausedMediaMs"),
                speed = o.optDouble("speed", 1.0).toFloat(),
                live = o.optBoolean("live", false),
                camFacing = o.optInt("camFacing", FACING_FRONT),
                mirror = o.optBoolean("mirror", true),
                text = o.optString("text", ""),
                textColor = o.optInt("textColor", 0xFFFFFFFF.toInt()),
                fontSizeN = o.optDouble("fontSizeN", 0.08).toFloat(),
                shadow = o.optBoolean("shadow", true),
                addedAt = o.optLong("addedAt", System.currentTimeMillis())
            )
            return l
        }
    }
}

/** Full project document (spec: versioned, deterministic JSON). */
class Project(
    var id: String,
    var name: String,
    var aspect: Aspect,
    var bgColor: Int = 0xFF101418.toInt(),
    val layers: MutableList<Layer> = ArrayList(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastPlayheadMs: Long = 0L
) {
    fun layerById(id: String): Layer? = layers.firstOrNull { it.id == id }

    /** Composition duration = longest video/camera source (static layers extend to it). */
    fun durationMs(): Long {
        var max = 0L
        for (l in layers) if (l.isVideoLike() && l.durMs > max) max = l.durMs
        if (max <= 0L) max = 5000L
        return max
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("schemaVersion", 1)
        o.put("id", id); o.put("name", name); o.put("aspect", aspect.code)
        o.put("bgColor", bgColor.toLong()); o.put("createdAt", createdAt); o.put("updatedAt", updatedAt)
        o.put("lastPlayheadMs", lastPlayheadMs)
        val arr = JSONArray()
        for (l in layers) arr.put(l.toJson())
        o.put("layers", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Project {
            val p = Project(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Untitled"),
                aspect = Aspect.from(o.optString("aspect", "16:9")),
                bgColor = o.optInt("bgColor", 0xFF101418.toInt()),
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                lastPlayheadMs = o.optLong("lastPlayheadMs")
            )
            val arr = o.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                try { p.layers.add(Layer.fromJson(arr.getJSONObject(i))) } catch (_: Exception) { }
            }
            return p
        }
    }
}

/**
 * Every placement rule of the editor, in one place.
 *
 * Boxes are NORMALIZED and anisotropic: `wN` is a fraction of the canvas
 * WIDTH while `hN` is a fraction of the canvas HEIGHT. A box that must keep
 * the source's pixel aspect ratio therefore has to be derived through
 * canvasW/canvasH every single time — doing it by eye (a fixed 0.44 x 0.31
 * box, say) crops a portrait camera take down to a thin landscape sliver,
 * which is exactly the "overlay point looks wrong" bug.
 *
 * The compositor draws a media layer into its box according to the layer's
 * `fit` mode (OBS plan §3):
 *   - fit = fill (COVER)  -> frame fills the box; a canvas-aspect box is
 *     full-bleed, a different-aspect frame is cropped at the edges
 *   - fit = fit (CONTAIN) -> whole frame visible, letterboxed in the box
 * A box with the source aspect shows the whole frame in either mode.
 */
object LayerFit {

    /** Source pixels as a decoded frame looks: rotation metadata applied. */
    fun effective(srcW: Int, srcH: Int, rotation: Int): Pair<Int, Int> =
        if ((rotation == 90 || rotation == 270) && srcW > 0 && srcH > 0) Pair(srcH, srcW)
        else Pair(srcW, srcH)

    /** Pixel aspect of the layer's source, falling back to the canvas aspect. */
    fun sourceAspect(l: Layer, canvasW: Int, canvasH: Int): Float {
        val (w, h) = effective(l.srcW, l.srcH, l.srcRotation)
        if (w <= 0 || h <= 0) return canvasW.toFloat() / canvasH
        return w.toFloat() / h
    }

    /** True when the layer already covers the whole canvas (it is the background). */
    fun isFullBleed(l: Layer): Boolean = l.wN >= 0.985f && l.hN >= 0.985f

    /**
     * MAIN CANVAS: the box IS the canvas. The compositor cover-crops the frame,
     * so the picture still fills every pixel — but the box no longer sticks out
     * of the canvas, which keeps the selection frame and all 8 resize handles
     * on screen and makes drag/resize behave.
     */
    fun fill(l: Layer) {
        l.cx = 0.5f; l.cy = 0.5f; l.wN = 1f; l.hN = 1f; l.rotDeg = 0f
    }

    /** CONTAIN: the whole frame, undistorted, centred, optionally inset. */
    fun contain(l: Layer, canvasW: Int, canvasH: Int, inset: Float = 1f) {
        val sa = sourceAspect(l, canvasW, canvasH)
        val ca = canvasW.toFloat() / canvasH
        if (sa >= ca) { l.wN = 1f; l.hN = ca / sa } else { l.hN = 1f; l.wN = sa / ca }
        l.wN *= inset; l.hN *= inset
        l.cx = 0.5f; l.cy = 0.5f
    }

    /**
     * Reaction-cam PiP: the SOURCE aspect ratio, fitted inside a
     * [maxW] x [maxH] region of the canvas and pinned to [anchor] with a
     * [margin] (all normalized), always fully inside the canvas.
     */
    fun pip(
        l: Layer,
        canvasW: Int,
        canvasH: Int,
        anchor: String = "br",
        maxW: Float = 0.36f,
        maxH: Float = 0.42f,
        margin: Float = 0.035f
    ) {
        val (ew, eh) = effective(l.srcW, l.srcH, l.srcRotation)
        val w = if (ew > 0 && eh > 0) ew.toFloat() else canvasW.toFloat()
        val h = if (ew > 0 && eh > 0) eh.toFloat() else canvasH.toFloat()
        val s = minOf(maxW * canvasW / w, maxH * canvasH / h)
        l.wN = (w * s) / canvasW
        l.hN = (h * s) / canvasH
        l.rotDeg = 0f
        anchorTo(l, anchor, margin)
    }

    /** Corner / edge anchors with a normalized margin, clamped inside. */
    fun anchorTo(l: Layer, anchor: String, margin: Float = 0.035f) {
        val mx = margin; val my = margin
        when (anchor) {
            "tl" -> { l.cx = mx + l.wN / 2f; l.cy = my + l.hN / 2f }
            "tc" -> { l.cx = 0.5f; l.cy = my + l.hN / 2f }
            "tr" -> { l.cx = 1f - mx - l.wN / 2f; l.cy = my + l.hN / 2f }
            "bl" -> { l.cx = mx + l.wN / 2f; l.cy = 1f - my - l.hN / 2f }
            "bc" -> { l.cx = 0.5f; l.cy = 1f - my - l.hN / 2f }
            "br" -> { l.cx = 1f - mx - l.wN / 2f; l.cy = 1f - my - l.hN / 2f }
            else -> { l.cx = 0.5f; l.cy = 0.5f }
        }
        clampInside(l)
    }

    /**
     * Keep at least [keep] of the layer visible on each axis, so a PiP can sit
     * against an edge but can never be dragged off-canvas and lost.
     */
    fun clampInside(l: Layer, keep: Float = 0.4f) {
        l.cx = clampAxis(l.cx, l.wN, keep)
        l.cy = clampAxis(l.cy, l.hN, keep)
    }

    private fun clampAxis(v: Float, size: Float, keep: Float): Float {
        val half = size / 2f
        val lo = half - size * (1f - keep)
        val hi = 1f - half + size * (1f - keep)
        return if (lo >= hi) 0.5f else v.coerceIn(lo, hi)
    }
}

/** Builds a new layer already placed by [LayerFit]. */
object LayerPresets {
    /** main-canvas candidate: full bleed */
    fun fullscreen(type: LayerType, name: String, relPath: String?, durMs: Long, sw: Int, sh: Int, rot: Int): Layer {
        val l = Layer(type = type, name = name, relPath = relPath, durMs = durMs, srcW = sw, srcH = sh, srcRotation = rot)
        LayerFit.fill(l)
        return l
    }

    /** overlay candidate: source aspect ratio, pinned to the reaction-cam corner */
    fun pipDefault(type: LayerType, name: String, relPath: String?, durMs: Long, sw: Int, sh: Int, rot: Int, canvasW: Int, canvasH: Int): Layer {
        val l = Layer(type = type, name = name, relPath = relPath, durMs = durMs, srcW = sw, srcH = sh, srcRotation = rot)
        LayerFit.pip(l, canvasW, canvasH)
        return l
    }
}
