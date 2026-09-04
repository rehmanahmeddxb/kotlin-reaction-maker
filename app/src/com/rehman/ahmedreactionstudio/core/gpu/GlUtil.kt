package com.rehman.ahmedreactionstudio.core.gpu

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimal GLES2 helpers for OES external textures (MediaCodec → SurfaceTexture)
 * and offscreen FBO reads used by the GPU video pipeline.
 */
object GlUtil {

    const val NO_TEXTURE = 0

    private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun identity(): FloatArray = IDENTITY.copyOf()

    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(coords.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(coords)
        fb.position(0)
        return fb
    }

    fun checkGl(op: String) {
        var err = GLES20.glGetError()
        while (err != GLES20.GL_NO_ERROR) {
            android.util.Log.w("GlUtil", "$op: glError 0x${Integer.toHexString(err)}")
            err = GLES20.glGetError()
        }
    }

    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] != GLES20.GL_TRUE) {
            android.util.Log.e("GlUtil", "link fail: " + GLES20.glGetProgramInfoLog(prog))
            GLES20.glDeleteProgram(prog)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun loadShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            android.util.Log.e("GlUtil", "shader compile: " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    /** Create an external OES texture for a SurfaceTexture. */
    fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val id = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return id
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    /**
     * Renders an OES external texture into an FBO and reads ARGB pixels into [out]
     * (or a new bitmap when [out] is null / wrong size). Longest side <= [maxPx].
     */
    class OesToBitmap {
        private var prog = 0
        private var aPos = 0
        private var aTex = 0
        private var uMvp = 0
        private var uSt = 0
        private var uTex = 0
        private var fbo = 0
        private var colorTex = 0
        private var fboW = 0
        private var fboH = 0
        private var pixelBuf: ByteBuffer? = null
        private val mvp = FloatArray(16)
        private val st = FloatArray(16)
        /** set once if a device rejects [Bitmap.copyPixelsFromBuffer] */
        private var slowPath = false

        private val VERT = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMvp;
            uniform mat4 uSt;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = (uSt * aTex).xy;
            }
        """.trimIndent()

        /**
         * The `.bgra` swizzle is the hot path of the whole preview: it lets the
         * GPU emit pixels already in Android's native ARGB_8888 memory order,
         * so [draw] can hand glReadPixels' buffer straight to
         * [Bitmap.copyPixelsFromBuffer] (a native memcpy) instead of running a
         * ~1M-iteration Java swizzle loop that allocated a fresh IntArray every
         * frame. That loop alone was ~20-40 ms per frame plus ~110 MB/s of
         * garbage at 30 fps — the dominant cause of preview stutter.
         */
        private val FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex).bgra;
            }
        """.trimIndent()

        // full-screen quad, y flipped so glReadPixels is upright bitmap order
        private val POS = createFloatBuffer(floatArrayOf(
            -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
        ))
        private val TEX = createFloatBuffer(floatArrayOf(
            0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
        ))

        fun ensureProgram() {
            if (prog != 0) return
            prog = createProgram(VERT, FRAG)
            if (prog == 0) return
            aPos = GLES20.glGetAttribLocation(prog, "aPos")
            aTex = GLES20.glGetAttribLocation(prog, "aTex")
            uMvp = GLES20.glGetUniformLocation(prog, "uMvp")
            uSt = GLES20.glGetUniformLocation(prog, "uSt")
            uTex = GLES20.glGetUniformLocation(prog, "uTex")
            Matrix.setIdentityM(mvp, 0)
            // flip Y so bitmap is upright
            Matrix.scaleM(mvp, 0, 1f, -1f, 1f)
        }

        private fun ensureFbo(w: Int, h: Int) {
            if (fbo != 0 && fboW == w && fboH == h) return
            releaseFbo()
            fboW = w; fboH = h
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            colorTex = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fbos = IntArray(1)
            GLES20.glGenFramebuffers(1, fbos, 0)
            fbo = fbos[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, colorTex, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.e("GlUtil", "FBO incomplete: 0x${Integer.toHexString(status)}")
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // 64 bytes of slack: some Skia builds pad a bitmap's rowBytes past
            // width * 4, and copyPixelsFromBuffer refuses a buffer smaller than
            // getByteCount(). The tail is never read.
            pixelBuf = ByteBuffer.allocateDirect(w * h * 4 + 64).order(ByteOrder.nativeOrder())
        }

        private fun releaseFbo() {
            if (fbo != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                fbo = 0
            }
            if (colorTex != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(colorTex), 0)
                colorTex = 0
            }
            fboW = 0; fboH = 0
            pixelBuf = null
        }

        /**
         * Draw [oesTexId] with [stMatrix] into a [outW]x[outH] bitmap.
         * Reuses [reuse] when size matches.
         */
        fun draw(oesTexId: Int, stMatrix: FloatArray, outW: Int, outH: Int, reuse: Bitmap?): Bitmap? {
            if (oesTexId == 0 || outW <= 0 || outH <= 0) return null
            ensureProgram()
            if (prog == 0) return null
            ensureFbo(outW, outH)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, outW, outH)
            // No glClear: the quad is opaque and covers the whole viewport, and
            // skipping it saves a full-frame write every single preview frame.

            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            System.arraycopy(stMatrix, 0, st, 0, 16)
            GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0)

            POS.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
            TEX.position(0)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)

            val buf = pixelBuf ?: return null
            buf.rewind()
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            val bmp = if (reuse != null && !reuse.isRecycled &&
                reuse.width == outW && reuse.height == outH &&
                reuse.config == Bitmap.Config.ARGB_8888) reuse
            else Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

            buf.rewind()
            // The shader already emitted BGRA, which is exactly Android's
            // ARGB_8888 byte order — so this is a straight native copy.
            if (!slowPath) {
                try {
                    bmp.copyPixelsFromBuffer(buf)
                    return bmp
                } catch (_: Exception) {
                    // Some exotic buffer/pixel-row combinations refuse the raw
                    // copy; fall back to the slow-but-correct path for good.
                    slowPath = true
                    android.util.Log.w("GlUtil", "copyPixelsFromBuffer failed; using swizzle fallback")
                }
            }
            buf.rewind()
            val px = IntArray(outW * outH)
            var i = 0
            while (i < px.size) {
                val b0 = buf.get().toInt() and 0xFF   // shader wrote .b here
                val g = buf.get().toInt() and 0xFF
                val r = buf.get().toInt() and 0xFF
                val a = buf.get().toInt() and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b0
                i++
            }
            bmp.setPixels(px, 0, outW, 0, 0, outW, outH)
            return bmp
        }

        fun release() {
            releaseFbo()
            if (prog != 0) {
                GLES20.glDeleteProgram(prog)
                prog = 0
            }
        }
    }

    /** Scale dims so longest side <= maxPx, keep even sizes for YUV paths. */
    fun fitSize(srcW: Int, srcH: Int, maxPx: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return Pair(2, 2)
        if (maxPx <= 0) return Pair(srcW and 1.inv(), srcH and 1.inv())
        val long = maxOf(srcW, srcH).toFloat()
        val scale = if (long > maxPx) maxPx / long else 1f
        var w = (srcW * scale).toInt().coerceAtLeast(2)
        var h = (srcH * scale).toInt().coerceAtLeast(2)
        w = w and 1.inv()
        h = h and 1.inv()
        return Pair(w.coerceAtLeast(2), h.coerceAtLeast(2))
    }
}
