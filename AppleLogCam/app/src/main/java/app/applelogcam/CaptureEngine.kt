package app.applelogcam

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Liga tudo: Camera2 (HLG10) -> SurfaceTexture -> shader Apple Log -> encoder HEVC Main10 + monitor na tela.
 * Todo o GL e o encoder rodam numa thread só ("gl").
 */
class CaptureEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady(description: String, isoRange: IntArray?, exposureRangeNs: LongArray?)
        fun onError(message: String)
        fun onRecording(recording: Boolean)
        fun onSaved(uri: Uri?)
    }

    companion object {
        private const val TAG = LogRenderer.TAG
        private const val BITRATE = 100_000_000
    }

    private val glThread = HandlerThread("gl").also { it.start() }
    private val gl = Handler(glThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val camera = CameraController(context)

    private var choice: CameraController.Choice? = null
    private var egl: EglCore? = null
    private var renderer: LogRenderer? = null
    private var eglPreview: EGLSurface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    // Gravação
    @Volatile private var recording = false
    @Volatile private var baseTsNs = -1L
    private var eglEncoder: EGLSurface? = null
    private var videoEncoder: VideoEncoder? = null
    private var audio: AudioEncoder? = null
    private var sink: MuxerSink? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    @Volatile var monitorLog = false
    @Volatile private var released = false

    private val dataSpaceMethod = runCatching { SurfaceTexture::class.java.getMethod("getDataSpace") }.getOrNull()
    private var dataSpaceLogged = false

    // ---------------------------------------------------------------- ciclo de vida

    fun start(previewSurface: Surface) {
        gl.post {
            try {
                val ch = camera.choose()
                    ?: throw IllegalStateException("Nenhuma câmera traseira com HLG10 (10 bits) encontrada")
                choice = ch

                val e = EglCore()
                egl = e
                val pv = e.createWindowSurface(previewSurface, tagHlg = false)
                eglPreview = pv
                e.makeCurrent(pv)
                e.swapIntervalZero()

                val r = LogRenderer()
                renderer = r

                val st = SurfaceTexture(r.texId)
                st.setDefaultBufferSize(ch.size.width, ch.size.height)
                st.setOnFrameAvailableListener({ drawFrame() }, gl)
                surfaceTexture = st
                val cs = Surface(st)
                cameraSurface = cs

                camera.open(ch, cs) { msg -> postError(msg) }

                val desc = "Apple Log · ${ch.size.width}x${ch.size.height} · ${ch.fps} fps · HEVC 10-bit"
                val iso = ch.isoRange?.let { intArrayOf(it.lower, it.upper) }
                val exp = ch.exposureRangeNs?.let { longArrayOf(it.lower, it.upper) }
                main.post { listener.onReady(desc, iso, exp) }
            } catch (t: Throwable) {
                Log.e(TAG, "start falhou", t)
                postError(t.message ?: t.toString())
            }
        }
    }

    /** Bloqueia até o teardown terminar: a Surface do preview não pode ser usada depois de surfaceDestroyed. */
    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        gl.post {
            try {
                finishRecording()
                camera.close()
                cameraSurface?.release()
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                renderer?.release()
                val e = egl
                if (e != null) {
                    eglPreview?.let { e.destroySurface(it) }
                    e.release()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "release: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(6, TimeUnit.SECONDS)
        glThread.quitSafely()
    }

    // ---------------------------------------------------------------- controles

    fun setManual(enabled: Boolean, iso: Int, exposureNs: Long) = camera.setManual(enabled, iso, exposureNs)

    fun startRecording(withAudio: Boolean) {
        gl.post { beginRecording(withAudio) }
    }

    fun stopRecording() {
        gl.post { finishRecording() }
    }

    // ---------------------------------------------------------------- frame loop (thread gl)

    private fun drawFrame() {
        if (released) return
        val e = egl ?: return
        val st = surfaceTexture ?: return
        val r = renderer ?: return
        val pv = eglPreview ?: return
        val ch = choice ?: return
        try {
            e.makeCurrent(pv)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ts = st.timestamp
            val fullRange = readFullRange(st)

            // Monitor: letterbox na surface do preview
            val pw = e.width(pv)
            val ph = e.height(pv)
            if (pw > 0 && ph > 0) {
                GLES20.glViewport(0, 0, pw, ph)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                var vw = pw
                var vh = pw * ch.size.height / ch.size.width
                if (vh > ph) {
                    vh = ph
                    vw = ph * ch.size.width / ch.size.height
                }
                val mode = if (monitorLog) LogRenderer.MODE_APPLE_LOG else LogRenderer.MODE_MONITOR_709
                r.draw(texMatrix, mode, 1f, fullRange, (pw - vw) / 2, (ph - vh) / 2, vw, vh)
                e.swap(pv)
            }

            // Encoder: sempre Apple Log
            val enc = eglEncoder
            if (recording && enc != null) {
                if (baseTsNs < 0) baseTsNs = ts
                e.makeCurrent(enc)
                r.draw(texMatrix, LogRenderer.MODE_APPLE_LOG, 1f, fullRange, 0, 0, ch.size.width, ch.size.height)
                e.setPresentationTime(enc, ts - baseTsNs)
                e.swap(enc)
                videoEncoder?.drain(false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame: ${t.message}", t)
            if (recording) {
                postError("Erro durante a gravação: ${t.message}")
                finishRecording()
            }
        }
    }

    /** Faixa (full/limited) do buffer atual, lida do dataspace que a câmera anexou ao frame. */
    private fun readFullRange(st: SurfaceTexture): Boolean {
        val ds = try { dataSpaceMethod?.invoke(st) as? Int ?: 0 } catch (_: Throwable) { 0 }
        val range = (ds shr 27) and 0x7        // 1 = full, 2 = limited, 0 = não informado
        if (!dataSpaceLogged) {
            dataSpaceLogged = true
            Log.i(TAG, "dataspace do frame = 0x${Integer.toHexString(ds)}, range=$range")
        }
        return if (range == 0) DEFAULT_FULL_RANGE_IF_UNKNOWN else range == 1
    }

    // ---------------------------------------------------------------- gravação (thread gl)

    private fun beginRecording(withAudio: Boolean) {
        if (recording || released) return
        val e = egl ?: return
        val ch = choice ?: return
        var createdUri: Uri? = null
        var audioEnc: AudioEncoder? = null
        try {
            val name = "AppleLog_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AppleLogCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não consegui criar o arquivo em Movies/AppleLogCam")
            createdUri = uri
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Não consegui abrir o arquivo para escrita")
            pfd = descriptor
            outputUri = uri

            val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(orientationHint(ch))

            audioEnc = if (withAudio) AudioEncoder.create() else null
            val s = MuxerSink(muxer, if (audioEnc != null) 2 else 1)
            sink = s

            val venc = VideoEncoder(ch.size.width, ch.size.height, ch.fps, BITRATE, s)
            videoEncoder = venc
            eglEncoder = e.createWindowSurface(venc.inputSurface, tagHlg = true)

            baseTsNs = -1L
            audio = audioEnc
            audioEnc?.begin(s) { baseTsNs }
            recording = true
            main.post { listener.onRecording(true) }
        } catch (t: Throwable) {
            Log.e(TAG, "beginRecording falhou", t)
            audioEnc?.abort()
            cleanupRecording(discard = true, uri = createdUri)
            postError("Não consegui iniciar a gravação: ${t.message}")
        }
    }

    private fun finishRecording() {
        if (!recording) return
        recording = false
        val e = egl
        try {
            eglPreview?.let { p -> e?.makeCurrent(p) }
            videoEncoder?.drain(true)
        } catch (t: Throwable) {
            Log.w(TAG, "drain final: ${t.message}")
        }
        val uri = outputUri
        val gotFrames = baseTsNs >= 0          // parou antes do primeiro frame: não há arquivo válido
        cleanupRecording(discard = !gotFrames, uri = uri)
        main.post {
            listener.onRecording(false)
            listener.onSaved(if (gotFrames) uri else null)
        }
    }

    private fun cleanupRecording(discard: Boolean, uri: Uri?) {
        recording = false
        try { audio?.stopAndJoin() } catch (_: Throwable) {}
        audio = null
        try { eglEncoder?.let { egl?.destroySurface(it) } } catch (_: Throwable) {}
        eglEncoder = null
        try { videoEncoder?.release() } catch (_: Throwable) {}
        videoEncoder = null
        try { sink?.stop() } catch (t: Throwable) { Log.w(TAG, "muxer.stop: ${t.message}") }
        sink = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        outputUri = null
        if (uri != null) {
            try {
                if (discard) {
                    context.contentResolver.delete(uri, null, null)
                } else {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, v, null, null)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MediaStore: ${t.message}")
            }
        }
    }

    /** Rotação (graus horários) que o player precisa aplicar para o vídeo ficar em pé na orientação atual. */
    private fun orientationHint(ch: CameraController.Choice): Int {
        val rotation = try { context.display?.rotation ?: 0 } catch (_: Throwable) { 0 }
        val degrees = rotation * 90
        return (ch.sensorOrientation - degrees + 360) % 360
    }

    private fun postError(msg: String) {
        main.post { listener.onError(msg) }
    }

    private val DEFAULT_FULL_RANGE_IF_UNKNOWN = true
}
