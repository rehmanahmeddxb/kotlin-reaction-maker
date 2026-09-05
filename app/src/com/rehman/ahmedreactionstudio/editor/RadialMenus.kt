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

    /**
     * Everything the rings need from the editor.
     *
     * NOTE (post-consolidation): several members are no longer called by any
     * ring — their verbs moved to a canonical non-ring home (UI Plan2 §4):
     *   fitAllSources / toggleSourcePlay / toggleMasterPlay → quick bar + transport
     *   snapshotFrame / restart / nudge                      → transport overflow
     *   cycleTextColor                                       → Layers sheet text block
     *   openAdvanced                                         → quick-bar ⋮
     *   openDiagnostics                                      → editor top bar
     *   the torch members                                    → the single Light sheet
     *   addCameraTake                                        → automatic camera fallback
     * They are deliberately KEPT on the interface: the editor still implements
     * them, they are the tested entry points, and deleting them would be a
     * wide refactor with no user-visible benefit. Do not re-add ring petals for
     * them — that is exactly the duplication this pass removed.
     */
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
        /** delete a source through the editor's safe path (camera teardown,
         *  decoder eviction, selection clearing, Undo snackbar) */
        fun deleteSource(l: Layer)
        fun quickExport()

        // canvas / project
        fun setAspect(a: Aspect)
        fun setBg(color: Int)
        fun fitAllSources()
        fun renameProject()
        fun saveNow()
        fun openDiagnostics()
        /** Full Canvas mode: hide every overlay, fit the canvas into the safe area */
        fun enterFullCanvas()
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
        /** T-20: opens the single Light sheet (front / back / both / screen) */
        fun openFlashRing(l: Layer)
        /** T-20: the Light sheet without needing a selected camera */
        fun openLight()

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
            item(R.drawable.ic_flash, "Light", badge = lightBadge) { h.openLight() },
            folder(R.drawable.ic_aspect, "Canvas") { canvas(h) },
            folder(R.drawable.ic_export, "Export") { export(h) },
            folder(R.drawable.ic_settings, "Project") { project(h) }
        )
    }

    // T-20 — the `lightRoot` / `flash` / `flashItems` ring levels are deleted.
    // Front, back, both and screen light now live in ONE capability-aware
    // Light sheet (EditorActivity.openLightSheet). Keeping a ring copy meant
    // two torch models, two label sets and two places to fix a bug.

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
            // UI Plan2 §4, one verb one home — deleted from this ring:
            //   Hide/Show (V03) → quick bar 👁 + dock eye
            //   Pause source (V04) → quick bar ⏯ + dock status line
            //   Mute (V01) / Solo (V02) / Loop (V32) → Audio sheet
            //   Fit/Fill (V09) → quick bar ⤢
            //   Duplicate (V14) → Layers sheet
            //   Arrange folder (V10/V11/V12) → Layers sheet inspector
            // The ring keeps only navigation, camera capture and the two verbs
            // that have no other one-tap home.

            if (l.isLive()) {
                // T-21 — the camera toolbar is the quick bar's camera cluster
                // (record take · switch facing · mirror · light), which appears
                // on the canvas the moment the camera is selected. Record take
                // and Switch cam are NOT repeated here; the ring only signposts
                // the toolbar so the verbs keep a single home.
                out.add(item(R.drawable.ic_camera, "Camera controls",
                    badge = if (h.isCameraRecording(l)) "REC" else "LIVE") { h.selectId(l.id) })
                out.add(item(R.drawable.ic_flash, "Light",
                    badge = if (h.isTorchOn(l) || h.isScreenLightOn()) "ON" else null) {
                    h.openLight()
                })
            } else if (l.isClip()) {
                // audio verbs (mute/solo/loop/level) live in the Audio sheet
                out.add(item(R.drawable.ic_volume, "Audio mixer…") { h.openMixerPanel() })
            }

            if (l.isText()) {
                out.add(item(R.drawable.ic_edit, "Edit text") { h.editText(l) })
            }

            out.add(item(if (l.locked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                if (l.locked) "Unlock" else "Lock", active = l.locked, keepOpen = true) {
                h.ctrl.toggleLocked(l.id)
            })
            out.add(item(R.drawable.ic_drag, "Layout & order…") { h.openDockPanel() })
            // never ctrl.delete() raw: a live camera needs its capture session
            // torn down and the engine needs the decoder evicted (V13)
            out.add(item(R.drawable.ic_delete, "Delete", danger = true) { h.deleteSource(l) })
            out
        }
    }

    // The ARRANGE ring is deleted (UI Plan2 T-17/T-18/T-19). Z-order,
    // the 3×3 anchor grid, "Reset position" and "Set as background" all have a
    // single home now: the selected-source inspector in the Layers sheet,
    // reachable from the ring via "Layout & order…".

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
            item(R.drawable.ic_fullscreen, "Full canvas") { h.enterFullCanvas() }
            // Deleted: "Fit all sources" (V09 lives on the quick bar ⤢) and
            // "Selection as background" (V12 lives in the Layers sheet under
            // its single name, "Set as background").
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
            item(R.drawable.ic_info, if (hudOn) "Stats overlay: on" else "Stats overlay: off",
                active = hudOn, keepOpen = true) { h.toggleStatsHud() },
            item(R.drawable.ic_undo, "Undo", keepOpen = true) { h.undo() },
            item(R.drawable.ic_redo, "Redo", keepOpen = true) { h.redo() },
            item(R.drawable.ic_back, "Close project", danger = true) { h.closeProject() }
        )
    }
}
