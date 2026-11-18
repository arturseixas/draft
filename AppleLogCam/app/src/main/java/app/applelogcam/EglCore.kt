package app.applelogcam

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * EGL/GLES3 com config RGBA1010102 (10 bits por canal). Esse é o formato que o
 * MediaCodec precisa para receber 10-bit pela input surface do HEVC Main10.
 */
class EglCore {

    private val display: EGLDisplay
    private val config: EGLConfig
    private var context: EGLContext

    /** True se o driver aceita marcar a surface como BT.2020 HLG (define matriz/primárias no encoder). */
    val supportsHlgSurface: Boolean

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay falhou" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize falhou" }

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        supportsHlgSurface = extensions.contains("EGL_EXT_gl_colorspace_bt2020_hlg")

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        check(ok && count[0] > 0 && configs[0] != null) { "Nenhum EGLConfig RGBA1010102 gravável" }
        config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext falhou" }
    }

    /** @param tagHlg marca a surface como BT.2020 HLG (usado só na surface do encoder). */
    fun createWindowSurface(surface: Surface, tagHlg: Boolean): EGLSurface {
        val attribs = if (tagHlg && supportsHlgSurface) {
            intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
        } else {
            intArrayOf(EGL14.EGL_NONE)
        }
        val s = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface falhou (0x${Integer.toHexString(EGL14.eglGetError())})" }
        return s
    }

    fun makeCurrent(s: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, s, s, context)) { "eglMakeCurrent falhou" }
    }

    fun swapIntervalZero() {
        EGL14.eglSwapInterval(display, 0)
    }

    fun swap(s: EGLSurface) {
        EGL14.eglSwapBuffers(display, s)
    }

    fun setPresentationTime(s: EGLSurface, ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, s, ns)
    }

    fun width(s: EGLSurface): Int = query(s, EGL14.EGL_WIDTH)
    fun height(s: EGLSurface): Int = query(s, EGL14.EGL_HEIGHT)

    private fun query(s: EGLSurface, what: Int): Int {
        val out = IntArray(1)
        EGL14.eglQuerySurface(display, s, what, out, 0)
        return out[0]
    }

    fun destroySurface(s: EGLSurface) {
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    }
}
