package com.tiboard.quest3replika.sender

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Étage GPU entre l'écran virtuel et l'encodeur :
 *  - reçoit les images capturées dans une texture externe (SurfaceTexture),
 *  - recadre (œil gauche, zoom, décalage) et redimensionne vers la taille de l'encodeur,
 *  - limite la cadence à [maxFps] pour ne pas surcharger l'encodeur et le Wi-Fi.
 * Tout s'exécute sur un thread GL dédié.
 */
class GlCropRenderer(
    private val outputSurface: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    private val inWidth: Int,
    private val inHeight: Int,
    maxFps: Int,
) : SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("QM-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val minIntervalMs = 1000L / maxFps

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uCrop = 0
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set

    private val texMatrix = FloatArray(16)
    @Volatile private var crop = RectF(0f, 0f, 1f, 1f)
    private var lastDrawMs = 0L
    private var drawScheduled = false
    private var released = false

    private val quad: FloatBuffer = floatBuffer(
        // x, y, u, v
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    /** Initialise EGL/GL et renvoie la Surface à donner au VirtualDisplay. */
    fun start(): Surface {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                error = t
            }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        error?.let { throw RuntimeException("Initialisation GL impossible", it) }
        return inputSurface
    }

    /** Zone source en fractions (origine en haut à gauche). Appliqué à l'image suivante. */
    fun setCrop(rect: RectF) {
        crop = RectF(rect)
        handler.post { scheduleDraw() }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        val config = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setDefaultBufferSize(inWidth, inHeight)
        surfaceTexture.setOnFrameAvailableListener(this, handler)
        inputSurface = Surface(surfaceTexture)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        // On consomme toujours l'image la plus récente pour libérer le producteur.
        surfaceTexture.updateTexImage()
        scheduleDraw()
    }

    private fun scheduleDraw() {
        if (released || drawScheduled) return
        val now = SystemClock.uptimeMillis()
        val wait = minIntervalMs - (now - lastDrawMs)
        if (wait <= 2) {
            draw()
        } else {
            drawScheduled = true
            handler.postDelayed({
                drawScheduled = false
                if (!released) draw()
            }, wait)
        }
    }

    private fun draw() {
        lastDrawMs = SystemClock.uptimeMillis()
        surfaceTexture.getTransformMatrix(texMatrix)
        val c = crop
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        // Les coordonnées de texture GL ont l'origine en bas : on convertit le haut/bas de la zone.
        GLES20.glUniform4f(uCrop, c.left, 1f - c.bottom, c.width(), c.height())
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Log.w(TAG, "eglSwapBuffers : 0x%x".format(EGL14.eglGetError()))
        }
    }

    fun release() {
        val latch = CountDownLatch(1)
        handler.post {
            released = true
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                inputSurface.release()
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            } catch (t: Throwable) {
                Log.w(TAG, "release", t)
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Link : " + GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader : " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val TAG = "GlCropRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop; // x, y (origine en bas), largeur, hauteur
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 uv = uCrop.xy + aTexCoord * uCrop.zw;
                vTexCoord = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v); position(0)
            }
    }
}
