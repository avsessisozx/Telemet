package io.github.avsessisozx.telemet.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Gösterge karelerini H.264 videoya yazar.
 *
 * Kareler Canvas ile bir Bitmap'e çizilir, OpenGL ile kodlayıcının giriş yüzeyine aktarılır ve her
 * kareye zaman damgası açıkça verilir (eglPresentationTimeANDROID). Böylece video, telefonun ne kadar
 * hızlı çizdiğinden bağımsız olarak tam [fps] hızında ve doğru sürede olur.
 *
 * Veri saniyede [updatesPerSecond] kez yeniden çizilir; aradaki kareler aynı görüntüyü tekrar kullanır
 * (dokuyu yeniden yüklemek gerekmez), bu yüzden uzun videolar da makul sürede biter.
 */
class OverlayEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val updatesPerSecond: Int = 10,
    private val bitrate: Int = width * height * 2, // yeşil zemin çok iyi sıkışır
) {
    class Cancelled : Exception()

    /**
     * @param durationMs videonun süresi
     * @param drawFrame verilen zamandaki (videonun başından itibaren ms) kareyi çizer
     * @param progress 0..1; false dönerse iptal edilir
     */
    fun encode(out: FileDescriptor, durationMs: Long, drawFrame: (Canvas, Long) -> Unit, progress: (Float) -> Boolean) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var egl: Egl? = null
        var inputSurface: Surface? = null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            egl = Egl(inputSurface)
            val gl = TexturePainter()

            val canvas = Canvas(bitmap)
            val frames = ((durationMs * fps + 999) / 1000).coerceAtLeast(1)
            val framesPerUpdate = (fps / updatesPerSecond).coerceAtLeast(1)
            val drain = Drainer(codec, muxer)
            for (i in 0 until frames) {
                val tMs = i * 1000L / fps
                if (i % framesPerUpdate == 0L) {
                    drawFrame(canvas, tMs)
                    gl.upload(bitmap)
                }
                gl.draw(width, height)
                egl.present(i * 1_000_000_000L / fps)
                drain.drain(false)
                if (i % fps == 0L && !progress(i.toFloat() / frames)) throw Cancelled()
            }
            codec.signalEndOfInputStream()
            drain.drain(true)
            progress(1f)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            egl?.release()
            inputSurface?.release()
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            bitmap.recycle()
        }
    }

    /** Kodlayıcı çıktısını dosyaya aktarır. */
    private class Drainer(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1

        fun drain(end: Boolean) {
            var waitedForEnd = 0
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!end) return
                        if (++waitedForEnd > 500) return // ~5 sn; kodlayıcı takıldıysa vazgeç
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0 && buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** Kodlayıcı yüzeyine bağlı en küçük EGL ortamı. */
    private class Egl(surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
        }

        fun present(nanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }

    /** Bir dokuyu tüm ekrana çizen küçük OpenGL programı. */
    private class TexturePainter {
        private val program: Int
        private val texture: Int
        private val aPos: Int
        private val aTex: Int
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v — Bitmap'in üstü dokuda v=0; OpenGL'de ekranın üstü y=1.
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }

        init {
            val vs = compile(GLES20.GL_VERTEX_SHADER, """
                attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;
                void main() { gl_Position = aPos; vTex = aTex; }
            """.trimIndent())
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
                void main() { gl_FragColor = texture2D(uTex, vTex); }
            """.trimIndent())
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "GL link: " + GLES20.glGetProgramInfoLog(program) }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private var uploaded = false

        fun upload(bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (!uploaded) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                uploaded = true
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }
        }

        fun draw(w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "GL shader: " + GLES20.glGetShaderInfoLog(sh) }
            return sh
        }
    }
}
