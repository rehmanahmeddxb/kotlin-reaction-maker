package com.rehman.ahmedreactionstudio.editor

import com.rehman.ahmedreactionstudio.R
import com.rehman.ahmedreactionstudio.core.Aspect
import com.rehman.ahmedreactionstudio.core.Layer
import com.rehman.ahmedreactionstudio.core.LayerType
import com.rehman.ahmedreactionstudio.core.Project
import com.rehman.ahmedreactionstudio.core.SourceController

/**
 * THE INTERFACE, AS RINGS.
 *
 * Everything the old tab bar and its button panels did is expressed here as a
 * tree of radial levels, so the user's model holds:
 *
 *      tap ◉  →  petals (Sources · Add · Controls · Dock · Mixing · Canvas ·
 *                        Export · Project)
 *      tap a petal  →  its sub-petals
 *      tap a sub-petal  →  the action happens
 *
 * Rings never own state: each `Level.items` lambda reads the live [Project]
 * every time it is drawn, and every verb goes through [SourceController] (or a
 * host callback) so undo/redo and preview==export still hold by construction.
 */
object RadialMenus {

    /** Everything the rings need from the editor. */
    interface Host {
        val project: Project
        val ctrl: SourceController
        fun selected(): Layer?
        fun selectId(id: String?)

        // add
        fun addVideo()
        fun addImage()
        fun addCameraLive()
        fun addCameraTake()
        fun addScreen()
        fun addTextSource()

        // transport / controls
        fun anyPlaying(): Boolean
        fun toggleMasterPlay()
        fun restart()
        fun nudge(ms: Long)
        fun toggleSourcePlay(l: Layer)
        fun snapshotFrame()
        fun undo()
        fun redo()

        // panels that genuinely need a sheet (sliders / pickers)
        fun openDockPanel()
        fun openMixerPanel()
        fun openExportPanel()
        fun openAdvanced(l: Layer)
        fun quickExport()

        // canvas / project
        fun setAspect(a: Aspect)
        fun setBg(color: Int)
        fun fitAllSources()
        fun renameProject()
        fun saveNow()
        fun openDiagnostics()
        fun closeProject()
        fun editText(l: Layer)
        fun cycleTextColor(l: Layer)

        // live camera
        fun isCameraRecording(l: Layer): Boolean
        fun toggleCameraRecord(l: Layer)
        fun switchCameraFacing(l: Layer)
        fun toggleCameraMirror(l: Layer)
        /** hardware LED torch state of the camera that is open right now */
        fun isTorchOn(l: Layer): Boolean
        /** true when the CURRENTLY selected camera actually has an LED */
        fun hasTorch(l: Layer): Boolean
        fun toggleTorch(l: Layer)
        // per-facing torch (front / back / both) — remember user wants both flashes option
        fun hasFrontTorch(): Boolean
        fun hasBackTorch(): Boolean
        fun isFrontTorchOn(): Boolean
        fun isBackTorchOn(): Boolean
        fun isBothTorchOn(): Boolean
        fun toggleFrontTorch()
        fun toggleBackTorch()
        fun toggleBothTorch()
        /** screen flash: the canvas glows white to light a front-camera face */
        fun isScreenLightOn(): Boolean
        fun toggleScreenLight()
        fun openFlashRing(l: Layer)

        // preview health overlay (also toggled from the editor overflow)
        fun isStatsHudOn(): Boolean
        fun toggleStatsHud()

        fun toast(msg: String)
    }

    private fun item(
        icon: Int, label: String, active: Boolean = false, danger: Boolean = false,
        badge: String? = null, keepOpen: Boolean = false, enabled: Boolean = true,
        action: () -> Unit
    ) = RadialMenuView.Item(icon, label, active, danger, badge, null, keepOpen, enabled, action)

    private fun folder(
        icon: Int, label: String, badge: String? = null, sub: () -> RadialMenuView.Level
    ) = RadialMenuView.Item(icon, label, false, false, badge, sub, false, true, null)

    // ================= ROOT =================

    /**
     * Root ring, kept to 7 petals so it NEVER pages (8/page). One-tap jobs
     * live on the persistent bottom bar; the ring is the power-user shortcut.
     * Deleted rings and where their verbs went:
     *  - Controls → transport row (±10 s buttons flank it) + Snapshot in Project
     *  - Dock     → Layers bottom-bar sheet (with ‹ › selection steppers)
     *  - Mixing   → Audio sheet (single mixer: mute/solo/loop/level per channel)
     */
    fun root(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_wheel, "Studio", "tap a petal · tap the hub to close"
    ) {
        val n = h.project.layers.size
        val live = h.project.layers.firstOrNull { it.isLive() }
        val lightBadge = if (h.isScreenLightOn() || (live != null && (h.isTorchOn(live) || h.isFrontTorchOn() || h.isBackTorchOn())) || h.isBothTorchOn()) "ON" else null
        val audioN = h.project.layers.count { it.isClip() }
        listOf(
            folder(R.drawable.ic_layers, "Sources", badge = if (n > 0) "$n" else null) { sources(h) },
            folder(R.drawable.ic_add, "Add") { add(h) },
            item(R.drawable.ic_volume, "Audio", badge = if (audioN > 0) "$audioN" else null) { h.openMixerPanel() },
            folder(R.drawable.ic_flash, "Light", badge = lightBadge) { lightRoot(h) },
            folder(R.drawable.ic_aspect, "Canvas") { canvas(h) },
            folder(R.drawable.ic_export, "Export") { export(h) },
            folder(R.drawable.ic_settings, "Project") { project(h) }
        )
    }

    /** Top-level Light menu so flashlight is discoverable without selecting a source */
    fun lightRoot(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_flash, "Light", "front · back · both · screen flash"
    ) {
        val live = h.project.layers.firstOrNull { it.isLive() }
        val out = ArrayList<RadialMenuView.Item>()
        if (live != null) {
            out.addAll(flashItems(h, live))
        } else {
            out.add(item(R.drawable.ic_camera, "Add live camera first") { h.addCameraLive() })
            val screenOn = h.isScreenLightOn()
            out.add(item(R.drawable.ic_eye, if (screenOn) "Screen light: on" else "Screen light: off", active = screenOn, keepOpen = true) { h.toggleScreenLight() })
        }
        out
    }

    private fun flashItems(h: Host, l: Layer): List<RadialMenuView.Item> {
        val out = ArrayList<RadialMenuView.Item>()
        val frontHas = h.hasFrontTorch()
        val backHas = h.hasBackTorch()
        val frontOn = h.isFrontTorchOn()
        val backOn = h.isBackTorchOn()
        val bothOn = h.isBothTorchOn()
        val screenOn = h.isScreenLightOn()
        if (frontHas) out.add(item(R.drawable.ic_flash, if (frontOn) "Front flash: on" else "Front flash: off", active = frontOn, badge = if (frontOn) "LED" else null, keepOpen = true) { h.toggleFrontTorch() })
        else out.add(item(R.drawable.ic_flash, "Front: no LED — use screen light", enabled = false) { })
        if (backHas) out.add(item(R.drawable.ic_flash, if (backOn) "Back flash: on" else "Back flash: off", active = backOn, badge = if (backOn) "LED" else null, keepOpen = true) { h.toggleBackTorch() })
        else out.add(item(R.drawable.ic_flash, "Back: no LED", enabled = false) { })
        if (frontHas && backHas) out.add(item(R.drawable.ic_flash, if (bothOn) "Both flashes: on" else "Both flashes: off", active = bothOn, keepOpen = true) { h.toggleBothTorch() })
        out.add(item(R.drawable.ic_eye, if (screenOn) "Screen light: on" else "Screen light: off", active = screenOn, badge = if (screenOn) "BRIGHT" else null, keepOpen = true) { h.toggleScreenLight() })
        out.add(item(R.drawable.ic_switch, if (l.camFacing == 0) "Switch to back cam" else "Switch to front cam", keepOpen = true) { h.switchCameraFacing(l) })
        return out
    }

    // ================= SOURCES =================

    /** One petal per source, top-most first; each opens that source's ring. */
    fun sources(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_layers, "Sources", "every source · tap for its controls"
    ) {
        val p = h.project
        if (p.layers.isEmpty())
            listOf(folder(R.drawable.ic_add, "Add the first source") { add(h) })
        else p.layers.indices.reversed().map { i ->
            val l = p.layers[i]
            folder(Ic.typeIcon(l.type), l.name.ifBlank { l.type.label }, badge = badgeOf(h, l)) {
                source(h, l.id)
            }
        }
    }

    private fun badgeOf(h: Host, l: Layer): String? = when {
        l.isLive() && h.isCameraRecording(l) -> "REC"
        l.isLive() -> "LIVE"
        !l.visible -> "HIDDEN"
        l.locked -> "LOCK"
        l.solo -> "SOLO"
        l.isClip() && h.ctrl.effectiveMuted(l) -> "MUTED"
        l.isClip() && !l.playing -> "PAUSED"
        else -> null
    }

    /** The per-source ring — every verb the old quick bar + sheet exposed. */
    fun source(h: Host, id: String): RadialMenuView.Level = RadialMenuView.Level(
        Ic.typeIcon(h.project.layerById(id)?.type ?: LayerType.VIDEO),
        h.project.layerById(id)?.name?.ifBlank { "Source" } ?: "Source",
        "controls for this source"
    ) {
        val l = h.project.layerById(id)
        if (l == null) emptyList() else {
            val out = ArrayList<RadialMenuView.Item>()
            out.add(item(R.drawable.ic_check, "Select on canvas",
                active = h.selected()?.id == l.id) { h.selectId(l.id) })
            out.add(item(if (l.visible) R.drawable.ic_eye_off else R.drawable.ic_eye,
                if (l.visible) "Hide" else "Show", active = !l.visible, keepOpen = true) {
                h.ctrl.toggleVisible(l.id)
            })

            if (l.isLive()) {
                val rec = h.isCameraRecording(l)
                out.add(item(if (rec) R.drawable.ic_stop else R.drawable.ic_camera,
                    if (rec) "Stop take" else "Record take",
                    active = rec, danger = rec) { h.toggleCameraRecord(l) })
                out.add(item(R.drawable.ic_switch, "Switch cam", keepOpen = true) {
                    h.switchCameraFacing(l)
                })
                out.add(item(R.drawable.ic_loop, if (l.mirror) "Mirror: on" else "Mirror: off",
                    active = l.mirror, keepOpen = true) { h.toggleCameraMirror(l) })
                out.add(folder(R.drawable.ic_flash, "Light",
                    badge = if (h.isTorchOn(l) || h.isScreenLightOn()) "ON" else null) {
                    flash(h, l.id)
                })
            } else if (l.isClip()) {
                out.add(item(if (l.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (l.playing) "Pause" else "Play", active = !l.playing, keepOpen = true) {
                    h.toggleSourcePlay(l)
                })
                val m = h.ctrl.effectiveMuted(l)
                out.add(item(if (m) R.drawable.ic_volume_off else R.drawable.ic_volume,
                    if (m) "Unmute" else "Mute", active = m, keepOpen = true) {
                    h.ctrl.toggleMuted(l.id)
                })
                out.add(item(R.drawable.ic_loop, if (l.loop) "Loop: on" else "Loop: off",
                    active = l.loop, keepOpen = true) { h.ctrl.toggleLoop(l.id) })
                out.add(item(R.drawable.ic_star, if (l.solo) "Solo: on" else "Solo",
                    active = l.solo, keepOpen = true) { h.ctrl.toggleSolo(l.id) })
            }

            if (l.isText()) {
                out.add(item(R.drawable.ic_edit, "Edit text") { h.editText(l) })
                out.add(item(R.drawable.ic_palette, "Colour", keepOpen = true) { h.cycleTextColor(l) })
            } else {
                // Naming standard (used in every surface): Fit = whole frame,
                // Fill = crop to box. Never "Fill" for background promotion.
                out.add(item(if (l.fit == Layer.FIT_FIT) R.drawable.ic_fit else R.drawable.ic_fill,
                    if (l.fit == Layer.FIT_FIT) "Fit: whole frame" else "Fill: crop to box",
                    active = l.fit == Layer.FIT_FIT, keepOpen = true) { h.ctrl.toggleFit(l.id) })
            }

            out.add(item(if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                if (l.locked) "Unlock" else "Lock", active = l.locked, keepOpen = true) {
                h.ctrl.toggleLocked(l.id)
            })
            out.add(folder(R.drawable.ic_drag, "Arrange") { arrange(h, l.id) })
            out.add(item(R.drawable.ic_settings, "Advanced…") { h.openAdvanced(l) })
            if (!l.isLive()) out.add(item(R.drawable.ic_copy, "Duplicate") {
                h.ctrl.duplicate(l.id)
            })
            out.add(item(R.drawable.ic_delete, "Delete", danger = true) { h.ctrl.delete(l.id) })
            out
        }
    }

    /**
     * LIGHT RING — flashlight for both cameras + both-on + screen flash.
     * Shows Front LED, Back LED, Both, and Screen Light as independent toggles
     * so a device with LEDs on both sides can run front, back or both while recording.
     */
    fun flash(h: Host, id: String): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_flash, "Light", "front · back · both · screen flash"
    ) {
        val l = h.project.layerById(id) ?: return@Level emptyList()
        flashItems(h, l)
    }

    fun arrange(h: Host, id: String): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_drag, "Arrange", "position · size · z-order"
    ) {
        val l = h.project.layerById(id)
        if (l == null) emptyList() else listOf(
            item(R.drawable.ic_up, "To front", keepOpen = true) { h.ctrl.moveZ(l.id, "front") },
            item(R.drawable.ic_down, "To back", keepOpen = true) { h.ctrl.moveZ(l.id, "back") },
            item(R.drawable.ic_reset, "Centre + unrotate", keepOpen = true) { h.ctrl.resetGeometry(l.id) },
            item(R.drawable.ic_corner_tl, "Corner: top-left", keepOpen = true) { h.ctrl.anchor(l.id, "tl") },
            item(R.drawable.ic_corner_tr, "Corner: top-right", keepOpen = true) { h.ctrl.anchor(l.id, "tr") },
            item(R.drawable.ic_corner_bl, "Corner: bottom-left", keepOpen = true) { h.ctrl.anchor(l.id, "bl") },
            item(R.drawable.ic_corner_br, "Corner: bottom-right", keepOpen = true) { h.ctrl.anchor(l.id, "br") },
            item(R.drawable.ic_fill, "Set as background") {
                h.ctrl.setAsCanvasBackground(l.id)
            }
        )
    }

    // ================= ADD =================

    fun add(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_add, "Add source",
        if (h.project.layers.isEmpty()) "the first one fills the canvas" else "added as a PiP overlay"
    ) {
        listOf(
            item(R.drawable.ic_camera, "Camera (live on canvas)") { h.addCameraLive() },
            item(R.drawable.ic_video, "Video file") { h.addVideo() },
            item(R.drawable.ic_image, "Image") { h.addImage() },
            item(R.drawable.ic_screen, "Screen record") { h.addScreen() },
            item(R.drawable.ic_text, "Text overlay") { h.addTextSource() }
            // NOTE: the old 6th item "Fullscreen take (fallback)" is gone on
            // purpose — the fullscreen recorder now only appears automatically
            // when the live camera fails on a device. One camera path to learn.
        )
    }

    // ================= CANVAS =================

    fun canvas(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_aspect, "Canvas", "ratio · background · layout"
    ) {
        val p = h.project
        listOf(
            item(R.drawable.ic_aspect, "16:9", active = p.aspect == Aspect.R169, keepOpen = true) {
                h.setAspect(Aspect.R169)
            },
            item(R.drawable.ic_aspect, "9:16", active = p.aspect == Aspect.R916, keepOpen = true) {
                h.setAspect(Aspect.R916)
            },
            item(R.drawable.ic_aspect, "1:1", active = p.aspect == Aspect.R11, keepOpen = true) {
                h.setAspect(Aspect.R11)
            },
            folder(R.drawable.ic_palette, "Background") { background(h) },
            item(R.drawable.ic_fit, "Fit all sources", keepOpen = true) { h.fitAllSources() },
            item(R.drawable.ic_fill, "Selection as background", keepOpen = true) {
                val s = h.selected()
                if (s == null) h.toast("Select a source first") else h.ctrl.setAsCanvasBackground(s.id)
            }
        )
    }

    fun background(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_palette, "Background", "canvas colour"
    ) {
        listOf(
            item(R.drawable.ic_palette, "Dark", keepOpen = true) { h.setBg(0xFF101418.toInt()) },
            item(R.drawable.ic_palette, "Black", keepOpen = true) { h.setBg(0xFF000000.toInt()) },
            item(R.drawable.ic_palette, "White", keepOpen = true) { h.setBg(0xFFFFFFFF.toInt()) },
            item(R.drawable.ic_palette, "Orange", keepOpen = true) { h.setBg(0xFFFF5A2C.toInt()) },
            item(R.drawable.ic_palette, "Navy", keepOpen = true) { h.setBg(0xFF1E3C78.toInt()) },
            item(R.drawable.ic_palette, "Green", keepOpen = true) { h.setBg(0xFF14785A.toInt()) },
            item(R.drawable.ic_palette, "Purple", keepOpen = true) { h.setBg(0xFF781E5A.toInt()) }
        )
    }

    // ================= EXPORT =================

    fun export(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_export, "Export", "render the composition"
    ) {
        listOf(
            item(R.drawable.ic_export, "Quick export 720p30") { h.quickExport() },
            item(R.drawable.ic_settings, "Export settings…") { h.openExportPanel() }
        )
    }

    // ================= PROJECT =================

    fun project(h: Host): RadialMenuView.Level = RadialMenuView.Level(
        R.drawable.ic_settings, "Project", h.project.name
    ) {
        val hudOn = h.isStatsHudOn()
        listOf(
            item(R.drawable.ic_edit, "Rename project") { h.renameProject() },
            item(R.drawable.ic_check, "Save now", keepOpen = true) { h.saveNow() },
            item(R.drawable.ic_image, "Snapshot frame") { h.snapshotFrame() },
            item(R.drawable.ic_info, if (hudOn) "Stats overlay: on" else "Stats overlay: off",
                active = hudOn, keepOpen = true) { h.toggleStatsHud() },
            item(R.drawable.ic_undo, "Undo", keepOpen = true) { h.undo() },
            item(R.drawable.ic_redo, "Redo", keepOpen = true) { h.redo() },
            item(R.drawable.ic_info, "Diagnostics") { h.openDiagnostics() },
            item(R.drawable.ic_back, "Close project", danger = true) { h.closeProject() }
        )
    }
}
