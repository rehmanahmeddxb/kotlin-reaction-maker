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

    fun init() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val cfg = configs[0]!!
        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
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
    private val stMatrix = FloatArray(16)
    @Volatile var frameAvailable = false
        private set

    init {
        texId = GlUtil.createOesTexture()
        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setOnFrameAvailableListener {
            frameAvailable = true
        }
        surface = Surface(surfaceTexture)
    }

    /** Pull the latest frame into the OES texture; returns the ST matrix. */
    fun updateTexImage(): FloatArray {
        if (frameAvailable) {
            try {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(stMatrix)
            } catch (_: Exception) { }
            frameAvailable = false
        }
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
