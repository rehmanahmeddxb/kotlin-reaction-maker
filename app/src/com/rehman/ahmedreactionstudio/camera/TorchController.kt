package com.rehman.ahmedreactionstudio.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/**
 * HARDWARE TORCH CONTROLLER — the single place in the app that is allowed to
 * switch a real camera LED on or off.
 *
 * Why this class exists (the bug it replaces):
 *  - the flashlight used to be driven only with `CaptureRequest.FLASH_MODE_TORCH`
 *    on the *currently open* camera session. That means the "torch" died every
 *    time the session was rebuilt (start/stop recording, facing switch, camera
 *    close, pause/resume), and on a camera without a flash unit the request was
 *    silently ignored — which is why the app ended up faking the rear
 *    flashlight with a white screen overlay instead of the rear LED.
 *  - `CameraManager.setTorchMode()` is Android's proper torch mechanism: it
 *    drives the flash LED of a camera id directly, without opening a camera
 *    device, so the torch survives session rebuilds and works even while the
 *    preview/recording session is being reconfigured.
 *
 * Contract:
 *  - [hasFlash] answers "does this side have a real LED?" from
 *    `CameraCharacteristics.FLASH_INFO_AVAILABLE`. We never claim a torch we
 *    cannot prove exists, so the UI can fall back to the screen light honestly.
 *  - [setTorch] returns false (with a reason) when the LED could not be driven
 *    instead of pretending it worked. Callers may then fall back to the
 *    capture-request torch (see LiveCamera/CameraActivity) or to screen light.
 *  - Every entry point swallows `CameraAccessException`, `SecurityException`,
 *    `IllegalArgumentException` and plain `Exception`: a device without a torch,
 *    a camera in use by another app, a disabled camera policy or a dying camera
 *    service must never crash the editor.
 *  - [releaseAll] / [shutdown] guarantee the LED is not left burning when the
 *    activity/pause/stop/destroy path runs.
 */
class TorchController(private val ctx: Context) {

    companion object {
        private const val BACK = CameraCharacteristics.LENS_FACING_BACK
        private const val FRONT = CameraCharacteristics.LENS_FACING_FRONT
    }

    /** Why the last [setTorch] call failed (kept for toasts / diagnostics). */
    enum class Fail {
        NONE,
        NO_CAMERA_SERVICE,
        NO_FLASH,
        TORCH_UNSUPPORTED,
        CAMERA_IN_USE,
        CAMERA_DISCONNECTED,
        CAMERA_DISABLED,
        CAMERA_ERROR,
        PERMISSION_DENIED
    }

    /** One camera id as reported by `CameraManager.getCameraIdList()`. */
    data class Cam(
        val id: String,
        val facing: Int,
        val flash: Boolean,
        val level: Int
    )

    private val manager: CameraManager? = try {
        ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    } catch (_: Exception) {
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cams = LinkedHashMap<String, Cam>()
    /** camera id -> LED state, kept in sync by the framework TorchCallback */
    private val torchState = HashMap<String, Boolean>()
    /** camera ids the framework explicitly reported as torch-less */
    private val torchUnavailable = HashSet<String>()
    /** facing -> candidate ids that expose a flash unit, most likely LED owner first */
    private val preferOrder = HashMap<Int, List<String>>()
    /** facing -> id we last commanded successfully (used by the report) */
    private val lastUsed = HashMap<Int, String>()

    @Volatile var lastFail: Fail = Fail.NONE
        private set
    @Volatile var lastError: String = ""
        private set

    private var registered = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            synchronized(this@TorchController) {
                torchState[cameraId] = enabled
                if (enabled) torchUnavailable.remove(cameraId)
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            synchronized(this@TorchController) {
                torchUnavailable.add(cameraId)
                torchState.remove(cameraId)
            }
        }
    }

    // ---------------- lifecycle ----------------

    /** Register the torch callback (idempotent; safe from any thread). */
    fun start() {
        if (registered) return
        val m = manager ?: return
        try {
            m.registerTorchCallback(torchCallback, mainHandler)
            registered = true
        } catch (_: Exception) {
            registered = false
        }
        refresh()
    }

    /** Turn every LED off and unregister. Called from stop/destroy paths. */
    fun shutdown() {
        releaseAll()
        val m = manager
        if (m != null && registered) {
            try {
                m.unregisterTorchCallback(torchCallback)
            } catch (_: Exception) {
            }
        }
        registered = false
    }

    /** (Re)scan the camera list. Cheap: characteristics are cached by the OS. */
    fun refresh() {
        synchronized(this) {
            cams.clear()
            preferOrder.clear()
            val m = manager ?: return
            try {
                for (id in m.cameraIdList) {
                    val ch = try {
                        m.getCameraCharacteristics(id)
                    } catch (_: Exception) {
                        continue
                    }
                    val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1
                    val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
                    cams[id] = Cam(id, facing, flash, level)
                }
                for (f in intArrayOf(BACK, FRONT)) {
                    // "0" (and then ascending ids) is the main sensor on every
                    // device we know of — that is the camera that owns the LED.
                    val ids = cams.values
                        .filter { it.facing == f && it.flash }
                        .map { it.id }
                        .sorted()
                    if (ids.isNotEmpty()) preferOrder[f] = ids
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- queries ----------------

    /** Camera ids that expose a flash unit for this side, best candidate first. */
    fun candidates(front: Boolean): List<String> =
        synchronized(this) { preferOrder[if (front) FRONT else BACK] ?: emptyList() }

    /** Does this side have a real LED at all? Never lies about the front. */
    fun hasFlash(front: Boolean): Boolean = candidates(front).isNotEmpty()

    /** The id we use for the hardware torch on this side (null when no LED). */
    fun torchIdFor(front: Boolean): String? = candidates(front).firstOrNull()

    /** Id actually commanded last time (may differ from the preferred id). */
    fun usedTorchId(front: Boolean): String? = synchronized(this) {
        lastUsed[if (front) FRONT else BACK]
    }

    /** Real LED state as reported by the framework callback. */
    fun isTorchOn(front: Boolean): Boolean = synchronized(this) {
        candidates(front).any { torchState[it] == true }
    }

    /** True when the framework told us this camera cannot do torch mode at all. */
    fun torchUnsupported(front: Boolean): Boolean {
        val ids = candidates(front)
        return ids.isNotEmpty() && ids.all { torchUnavailable.contains(it) }
    }

    /** Human readable summary for Diagnostics / bug reports. */
    fun describe(front: Boolean): String {
        val ids = candidates(front)
        if (ids.isEmpty()) return "none — screen light only"
        val used = usedTorchId(front)
        val state = when {
            isTorchOn(front) -> "LED on"
            torchUnsupported(front) -> "LED present, torch mode unsupported"
            else -> "LED off"
        }
        return "id ${ids.firstOrNull()}${if (used != null && used != ids.firstOrNull()) " (used $used)" else ""} · $state"
    }

    fun failureText(): String = when (lastFail) {
        Fail.NONE -> ""
        Fail.NO_CAMERA_SERVICE -> "No camera service on this device"
        Fail.NO_FLASH -> "No hardware flash on this camera"
        Fail.TORCH_UNSUPPORTED -> "Torch mode unsupported on this device"
        Fail.CAMERA_IN_USE -> "Camera is in use by another app"
        Fail.CAMERA_DISCONNECTED -> "Camera disconnected"
        Fail.CAMERA_DISABLED -> "Camera disabled by policy"
        Fail.CAMERA_ERROR -> "Camera error: $lastError"
        Fail.PERMISSION_DENIED -> "Camera permission denied"
    }

    /** All camera ids on the device (diagnostics / report). */
    fun cameraIds(): List<Cam> = synchronized(this) { cams.values.toList() }

    // ---------------- control ----------------

    /**
     * Turn the hardware LED for [front] on or off with
     * `CameraManager.setTorchMode()`.
     *
     * @return true when the command was accepted (or the LED is already in the
     *         requested state); false when there is no LED / the framework
     *         refused. Never throws.
     */
    fun setTorch(front: Boolean, on: Boolean): Boolean {
        start()
        val facing = if (front) FRONT else BACK
        val ids = candidates(front)
        if (ids.isEmpty()) {
            lastFail = Fail.NO_FLASH
            lastError = "no flash unit"
            return false
        }
        val m = manager
        if (m == null) {
            lastFail = Fail.NO_CAMERA_SERVICE
            lastError = "no CameraManager"
            return false
        }
        lastFail = Fail.NONE
        lastError = ""

        if (!on) {
            // Turning off is best effort over every candidate: some devices
            // route the LED through a different camera id than the one we
            // switched it on with.
            var ok = false
            for (id in ids) {
                try {
                    m.setTorchMode(id, false)
                    synchronized(this) { torchState[id] = false }
                    ok = true
                } catch (e: Exception) {
                    recordFail(e)
                }
            }
            return ok
        }

        for (id in ids) {
            if (synchronized(this) { torchState[id] == true }) {
                synchronized(this) { lastUsed[facing] = id }
                return true
            }
            try {
                m.setTorchMode(id, true)
                synchronized(this) {
                    torchState[id] = true
                    lastUsed[facing] = id
                }
                return true
            } catch (e: Exception) {
                recordFail(e)
                when (lastFail) {
                    // no point asking the other ids: the answer won't change
                    Fail.PERMISSION_DENIED, Fail.CAMERA_DISABLED -> return false
                    else -> { /* try the next candidate id */ }
                }
            }
        }
        return false
    }

    /** Turn every LED on the device off. Never throws. */
    fun releaseAll() {
        val m = manager ?: return
        if (synchronized(this) { cams.isEmpty() }) refresh()
        val ids = synchronized(this) { cams.values.filter { it.flash }.map { it.id } }
        for (id in ids) {
            try {
                m.setTorchMode(id, false)
            } catch (_: Exception) {
            }
        }
        synchronized(this) {
            torchState.clear()
            lastUsed.clear()
        }
    }

    private fun recordFail(e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        lastFail = when {
            e is SecurityException -> Fail.PERMISSION_DENIED
            e is IllegalArgumentException -> Fail.TORCH_UNSUPPORTED
            e is CameraAccessException -> when (e.reason) {
                CameraAccessException.CAMERA_IN_USE,
                CameraAccessException.MAX_CAMERAS_IN_USE -> Fail.CAMERA_IN_USE
                CameraAccessException.CAMERA_DISCONNECTED -> Fail.CAMERA_DISCONNECTED
                CameraAccessException.CAMERA_DISABLED -> Fail.CAMERA_DISABLED
                else -> Fail.CAMERA_ERROR
            }
            else -> Fail.CAMERA_ERROR
        }
    }
}
