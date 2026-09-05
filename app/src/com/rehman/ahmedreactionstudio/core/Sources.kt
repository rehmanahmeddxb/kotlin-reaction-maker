package com.rehman.ahmedreactionstudio.core

/**
 * OBS-style source command layer (docs/OBS_SOURCE_PLAN.md §6).
 *
 * Every mutation of a source goes through one of these verbs:
 *
 *   UI gesture / button
 *       ↓
 *   SourceController verb
 *       ↓  (pushes an undo snapshot first)
 *   Project state mutated
 *       ↓
 *   listener() → UI + compositor + audio refresh
 *
 * Nothing in the UI mutates Layer fields directly any more, which is what
 * makes undo/redo total and keeps preview == export (there is exactly one
 * state, read by both the Compositor and the Exporter).
 *
 * Solo semantics (§5 of the plan): a flag, never a stored "previous state".
 * While any source is soloed, every non-soloed source is EFFECTIVELY muted.
 * Toggling solo off cannot lose state because nothing was overwritten.
 */
class SourceController(
    private val projectRef: () -> Project,
    /** called before any mutation so the host can snapshot for undo */
    private val willMutate: () -> Unit,
    /** called after any mutation: refresh stage / dock / quick bar / autosave */
    private val onChanged: () -> Unit
) {
    private val p: Project get() = projectRef()

    // ---------- effective state (readers must use these) ----------

    fun anySolo(): Boolean = p.layers.any { it.solo }

    /** OBS rule: solo mutes everything else; plain mute is per-source. */
    fun effectiveMuted(l: Layer): Boolean = l.muted || (anySolo() && !l.solo)

    /** Visibility is purely visual; hidden sources KEEP their audio. */
    fun effectiveVisible(l: Layer): Boolean = l.visible

    // ---------- the verbs ----------

    private fun withLayer(id: String?, f: (Layer) -> Unit) {
        val l = p.layerById(id ?: return) ?: return
        willMutate()
        f(l)
        onChanged()
    }

    fun toggleVisible(id: String?) = withLayer(id) { it.visible = !it.visible }
    fun toggleMuted(id: String?) = withLayer(id) { it.muted = !it.muted }
    fun toggleLocked(id: String?) = withLayer(id) { it.locked = !it.locked }
    fun toggleSolo(id: String?) = withLayer(id) { it.solo = !it.solo }
    fun toggleLoop(id: String?) = withLayer(id) { it.loop = !it.loop }

    /** Flip between COVER (fill) and CONTAIN (fit). */
    fun toggleFit(id: String?) = withLayer(id) {
        it.fit = if (it.fit == Layer.FIT_FIT) Layer.FIT_FILL else Layer.FIT_FIT
    }

    fun setFit(id: String?, fit: String) = withLayer(id) { it.fit = fit }
    fun setOpacity(id: String?, v: Float) = withLayer(id) { it.opacity = v.coerceIn(0f, 1f) }
    fun setVolume(id: String?, v: Float) = withLayer(id) { it.volume = v.coerceIn(0f, 1f) }
    fun setName(id: String?, name: String) = withLayer(id) { it.name = name }

    /** Move inside the z-list. `index` semantics: 0 = canvas background. */
    fun moveZ(id: String?, mode: String) {
        val l = p.layerById(id ?: return) ?: return
        val list = p.layers
        val idx = list.indexOf(l)
        if (idx < 0) return
        willMutate()
        list.removeAt(idx)
        when (mode) {
            "front" -> list.add(l)
            "back" -> list.add(0, l)
            "up" -> list.add(minOf(idx + 1, list.size), l)
            "down" -> list.add(maxOf(idx - 1, 0), l)
            else -> list.add(idx, l)
        }
        onChanged()
    }

    fun anchor(id: String?, anchor: String) = withLayer(id) { LayerFit.anchorTo(it, anchor) }

    fun center(id: String?) = withLayer(id) { it.cx = 0.5f; it.cy = 0.5f }

    /** Reset position + rotation to the calm default, keeping the size. */
    fun resetGeometry(id: String?) = withLayer(id) {
        it.cx = 0.5f; it.cy = 0.5f; it.rotDeg = 0f
        LayerFit.clampInside(it)
    }

    /** Promote a source to the canvas background: full bleed + bottom of z. */
    fun setAsCanvasBackground(id: String?) {
        val l = p.layerById(id ?: return) ?: return
        if (l.isText()) return
        willMutate()
        LayerFit.fill(l)
        p.layers.remove(l)
        p.layers.add(0, l)
        onChanged()
    }

    /**
     * Duplicate a source. Returns the new id so the UI can select it.
     */
    fun duplicate(id: String?): String? {
        val l = p.layerById(id ?: return null) ?: return null
        willMutate()
        val copy = l.clone()
        copy.id = java.util.UUID.randomUUID().toString()
        copy.name = l.name + " copy"
        copy.solo = false
        copy.cx = ((l.cx + 0.06f) % 1f).coerceIn(0f, 1f)
        copy.cy = ((l.cy + 0.06f) % 1f).coerceIn(0f, 1f)
        val idx = p.layers.indexOf(l)
        p.layers.add(idx + 1, copy)
        onChanged()
        return copy.id
    }

    fun delete(id: String?) {
        val l = p.layerById(id ?: return) ?: return
        willMutate()
        p.layers.remove(l)
        onChanged()
    }

    /** Add a fully constructed layer on top of the stack. */
    fun add(l: Layer) {
        willMutate()
        p.layers.add(l)
        onChanged()
    }

    /** Raw reorder used by the dock drag: caller pushes undo at drag start. */
    fun reorderLive(fromIndex: Int, toIndex: Int) {
        val list = p.layers
        if (fromIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex.coerceIn(0, list.size), item)
    }
}
