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
    var volume: Float = 1f,               // 0..1
    var opacity: Float = 1f,              // 0..1
    var playing: Boolean = true,          // independent layer pause semantics
    var pausedMediaMs: Long = 0L,
    var speed: Float = 1f,
    // TEXT layer fields
    var text: String = "",
    var textColor: Int = 0xFFFFFFFF.toInt(),
    var fontSizeN: Float = 0.08f,          // normalized to canvas height
    var shadow: Boolean = true,
    var addedAt: Long = System.currentTimeMillis()
) {
    fun isVideoLike(): Boolean = type == LayerType.VIDEO || type == LayerType.CAMERA || type == LayerType.SCREEN

    fun clone(): Layer {
        val l = Layer(id, type, name, relPath, durMs, srcW, srcH, srcRotation, cx, cy, wN, hN,
            rotDeg, visible, locked, muted, volume, opacity, playing, pausedMediaMs, speed,
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
        o.put("volume", volume.toDouble()); o.put("opacity", opacity.toDouble())
        o.put("playing", playing); o.put("pausedMediaMs", pausedMediaMs)
        o.put("speed", speed.toDouble())
        if (type == LayerType.TEXT) {
            o.put("text", text); o.put("textColor", textColor.toLong())
            o.put("fontSizeN", fontSizeN.toDouble()); o.put("shadow", shadow)
        }
        o.put("addedAt", addedAt)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Layer {
            val t = LayerType.from(o.optString("type", "VIDEO"))
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
                volume = o.optDouble("volume", 1.0).toFloat(), opacity = o.optDouble("opacity", 1.0).toFloat(),
                playing = o.optBoolean("playing", true), pausedMediaMs = o.optLong("pausedMediaMs"),
                speed = o.optDouble("speed", 1.0).toFloat(),
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

/** New-layer defaults helper. */
object LayerPresets {
    fun fullscreen(type: LayerType, name: String, relPath: String?, durMs: Long, sw: Int, sh: Int, rot: Int): Layer {
        val l = Layer(type = type, name = name, relPath = relPath, durMs = durMs, srcW = sw, srcH = sh, srcRotation = rot)
        l.wN = 1f; l.hN = 1f; l.cx = 0.5f; l.cy = 0.5f
        return l
    }

    /**
     * PiP default: box is 44% of canvas width with a 3:4 box shape, centered in the
     * lower third. canvasW/H are the logical canvas pixels so geometry is exact.
     */
    fun pipDefault(type: LayerType, name: String, relPath: String?, durMs: Long, sw: Int, sh: Int, rot: Int, canvasW: Int, canvasH: Int): Layer {
        val l = Layer(type = type, name = name, relPath = relPath, durMs = durMs, srcW = sw, srcH = sh, srcRotation = rot)
        l.wN = 0.44f
        l.hN = (l.wN * canvasW * 0.75f) / canvasH
        l.cx = 0.5f
        l.cy = 0.76f
        return l
    }
}
