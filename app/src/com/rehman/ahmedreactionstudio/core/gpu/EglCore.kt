package com.rehman.ahmedreactionstudio.core.gpu

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface

/**
 * Offscreen EGL context used by [GpuVideoDecoder] so MediaCodec can decode
 * into a SurfaceTexture without a visible View. One context is shared by the
 * decode thread that owns all GPU decoders.
 */
class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var released = false

    companion object {
        /** true when the shared context is OpenGL ES 3 (PBO read-back available) */
        @Volatile
        var es3: Boolean = false
            internal set
    }

    fun init() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        // Ask for an ES3 context first: ES3 brings Pixel Buffer Objects, which
        // turn the per-frame glReadPixels from a full CPU/GPU pipeline stall
        // (15-25 ms on a mid-range phone — the entire reason the preview was
        // capped around 17 fps) into an asynchronous transfer. Every device
        // that cannot give us ES3 falls back to the ES2 context and the old
        // synchronous read-back, so nothing regresses.
        var cfg: EGLConfig? = null
        var made = false
        for (glVer in intArrayOf(3, 2)) {
            val renderableBit = if (glVer == 3) 0x0040 else 4  // ES3_BIT_KHR : ES2_BIT
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderableBit,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, num, 0) ||
                num[0] == 0 || configs[0] == null) continue
            val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, glVer, EGL14.EGL_NONE)
            val c = EGL14.eglCreateContext(display, configs[0]!!, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
            if (c == null || c == EGL14.EGL_NO_CONTEXT) continue
            context = c
            cfg = configs[0]!!
            es3 = glVer == 3
            made = true
            break
        }
        if (!made || cfg == null) throw RuntimeException("eglCreateContext failed")
        val pbuf = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        surface = EGL14.eglCreatePbufferSurface(display, cfg, pbuf, 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")
        makeCurrent()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    fun release() {
        if (released) return
        released = true
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }
}

/**
 * One OES SurfaceTexture pair used as MediaCodec output. Must be created
 * while the [EglCore] context is current.
 */
class OesSurfaceTarget {
    val texId: Int
    val surfaceTexture: SurfaceTexture
    val surface: Surface
    /**
     * Identity, not zeros: the decoder may blit before the first
     * getTransformMatrix, and an all-zero matrix would collapse every texcoord
     * to (0,0) and paint the whole frame with a single corner pixel.
     */
    private val stMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    @Volatile var frameAvailable = false
        private set

    init {
        texId = GlUtil.createOesTexture()
        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setOnFrameAvailableListener {
            // NOTE: this callback is posted to the looper of the thread that
            // created the SurfaceTexture — which is the GL thread. Listeners
            // therefore NEVER fire while a decode runnable is executing on that
            // thread, so nothing may block waiting on this flag.
            frameAvailable = true
        }
        surface = Surface(surfaceTexture)
    }

    /** true once since the last [updateTexImage] call */
    fun consumeFrameAvailable(): Boolean {
        if (!frameAvailable) return false
        frameAvailable = false
        return true
    }

    /**
     * Pull the latest frame into the OES texture and return the ST matrix.
     *
     * Called unconditionally right after the codec releases an output buffer:
     * [SurfaceTexture.updateTexImage] acquires the newest queued buffer
     * directly, so it works synchronously on the GL thread. Waiting on the
     * onFrameAvailable listener instead never succeeds (see [frameAvailable])
     * and used to cost up to 16 ms of sleep plus one frame of extra latency
     * on every single preview frame.
     */
    fun updateTexImage(): FloatArray {
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        } catch (_: Exception) { }
        frameAvailable = false
        return stMatrix
    }

    fun setDefaultBufferSize(w: Int, h: Int) {
        try { surfaceTexture.setDefaultBufferSize(w, h) } catch (_: Exception) { }
    }

    fun release() {
        try { surface.release() } catch (_: Exception) { }
        try { surfaceTexture.release() } catch (_: Exception) { }
        GlUtil.deleteTexture(texId)
    }
}
