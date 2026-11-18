package app.applelogcam

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class MonitorOpts(
    val lut: Boolean = true,
    val zebra: Float = -1f,
    val peaking: Boolean = false,
    val falseColor: Boolean = false,
    val magnify: Float = 1f
)

class RecordConfig(
    val name: String,
    val profile: Profile,
    val bitrate: Int,
    val audio: Boolean,
    val folder: String,
    val burnLut: Boolean
)

/**
 * Camera2 -> SurfaceTexture -> shader -> monitor na tela + encoder HEVC Main10.
 * GL e encoder de vídeo rodam na thread "gl"; a câmera tem a própria thread.
 */
class CaptureEngine(private val context: Context, private val listener: Listener) : CameraController.Callback {

    interface Listener {
        fun onCameraReady(info: CamInfo, size: Size, fps: Int, cameras: List<CamInfo>)
        fun onConfigFailed(message: String)
        fun onStats(iso: Int, exposureNs: Long, focusDiopters: Float, afState: Int)
        fun onError(message: String)
        fun onRecording(recording: Boolean)
        fun onSaved(uri: Uri?, name: String)
    }

    companion object {
        private const val TAG = LogRenderer.TAG
    }

    private val glThread = HandlerThread("gl").also { it.start() }
    private val gl = Handler(glThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val camera = CameraController(context)

    private var cameras: List<CamInfo> = emptyList()
    @Volatile private var info: CamInfo? = null
    @Volatile private var size = Size(3840, 2160)
    @Volatile var fps = 30
        private set

    private var egl: EglCore? = null
    private var renderer: LogRenderer? = null
    private var eglPreview: EGLSurface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val previewMatrix = FloatArray(16)
    private val recordMatrix = FloatArray(16)
    private val input = LogRenderer.Input()
    private val previewParams = LogRenderer.Params()
    private val recordParams = LogRenderer.Params()
    private var dataSpaceLogged = false

    @Volatile var monitor = MonitorOpts()
    @Volatile var profile = Profile.APPLE_LOG
    private var currentLut: CubeLut? = null

    @Volatile private var displayDeg = 0
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { displayDeg = currentDisplayDegrees() }
    }

    // Gravação
    @Volatile var recording = false
        private set
    @Volatile private var baseTsNs = -1L
    @Volatile var elapsedNs = 0L
        private set
    private var encW = 0
    private var encH = 0
    private var eglEncoder: EGLSurface? = null
    private var videoEncoder: VideoEncoder? = null
    @Volatile private var audio: AudioEncoder? = null
    private var sink: MuxerSink? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var outputName = ""

    @Volatile private var released = false

    // ---------------------------------------------------------------- ciclo de vida

    fun start(previewSurface: Surface, front: Boolean, height: Int, fps: Int, controls: CamControls) {
        gl.post {
            try {
                displayDeg = currentDisplayDegrees()
                displayManager?.registerDisplayListener(displayListener, gl)
                cameras = camera.listCameras()

                val e = EglCore()
                egl = e
                val pv = e.createWindowSurface(previewSurface, tagHlg = false)
                eglPreview = pv
                e.makeCurrent(pv)
                e.swapIntervalZero()

                val r = LogRenderer()
                renderer = r
                currentLut?.let { r.setLut(it) }
                val st = SurfaceTexture(r.texId)
                st.setOnFrameAvailableListener({ drawFrame() }, gl)
                surfaceTexture = st
                cameraSurface = Surface(st)

                openInternal(front, height, fps, controls)
            } catch (t: Throwable) {
                Log.e(TAG, "start falhou", t)
                postError(t.message ?: t.toString())
            }
        }
    }

    fun reconfigure(front: Boolean, height: Int, fps: Int, controls: CamControls) {
        gl.post {
            if (recording || released) return@post
            try {
                openInternal(front, height, fps, controls)
            } catch (t: Throwable) {
                postError(t.message ?: t.toString())
            }
        }
    }

    private fun openInternal(front: Boolean, height: Int, fpsWanted: Int, controls: CamControls) {
        val inf = pick(front) ?: pick(!front)
            ?: throw IllegalStateException("Nenhuma câmera disponível")
        val sz = inf.sizeFor(height)
        val list = inf.supportedFps(sz)
        val f = if (fpsWanted in list) fpsWanted else list.minBy { abs(it - fpsWanted) }
        info = inf
        size = sz
        fps = f
        dataSpaceLogged = false
        surfaceTexture?.setDefaultBufferSize(sz.width, sz.height)
        val cs = cameraSurface ?: return
        camera.open(inf, f, cs, controls, this)
        val all = cameras
        main.post { listener.onCameraReady(inf, sz, f, all) }
    }

    private fun pick(front: Boolean): CamInfo? = cameras.filter { it.front == front }.maxByOrNull { it.score }

    /** Bloqueia até o teardown terminar: a Surface do preview não pode ser usada depois de surfaceDestroyed. */
    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        gl.post {
            try {
                displayManager?.unregisterDisplayListener(displayListener)
                finishRecording()
                camera.shutdown()
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

    fun updateControls(c: CamControls) = camera.update(c)

    fun setRegion(r: MeteringRectangle?, trigger: Boolean, c: CamControls) = camera.setRegion(r, trigger, c)

    fun startRecording(cfg: RecordConfig) {
        gl.post { beginRecording(cfg) }
    }

    fun stopRecording() {
        gl.post { finishRecording() }
    }

    /** Carrega (ou remove, com null) a LUT importada. Vale para o monitor e, se pedido, para o arquivo. */
    fun setLut(lut: CubeLut?) {
        gl.post {
            currentLut = lut
            val r = renderer ?: return@post
            try {
                egl?.let { e -> eglPreview?.let { e.makeCurrent(it) } }
                r.setLut(lut)
            } catch (t: Throwable) {
                Log.w(TAG, "setLut: ${t.message}")
            }
        }
    }

    fun audioLevels(): FloatArray {
        val a = audio ?: return floatArrayOf(0f, 0f)
        return floatArrayOf(a.levelL, a.levelR)
    }

    /** Proporção do quadro como aparece na tela (já girado). */
    fun contentAspect(): Float {
        val inf = info ?: return 16f / 9f
        val sz = size
        val rot = cameraRotation(inf, displayDeg)
        return if (rot % 180 != 0) sz.height.toFloat() / sz.width else sz.width.toFloat() / sz.height
    }

    /**
     * Converte um toque no quadro (nx, ny de 0 a 1, origem no canto superior esquerdo da imagem na tela)
     * para um retângulo de medição no active array do sensor.
     */
    fun meteringRect(nx: Float, ny: Float): MeteringRectangle? {
        val inf = info ?: return null
        val sz = size
        val rot = cameraRotation(inf, displayDeg)
        val dx = if (inf.front) 1f - nx else nx
        val dy = ny
        val rx: Float
        val ry: Float
        when (rot) {
            90 -> { rx = dy; ry = 1f - dx }
            180 -> { rx = 1f - dx; ry = 1f - dy }
            270 -> { rx = 1f - dy; ry = dx }
            else -> { rx = dx; ry = dy }
        }
        val a = inf.activeArray
        val aw = a.width().toFloat()
        val ah = a.height().toFloat()
        val streamAspect = sz.width.toFloat() / sz.height
        var vw = aw
        var vh = ah
        if (aw / ah < streamAspect) vh = aw / streamAspect else vw = ah * streamAspect
        val px = (aw - vw) / 2f + rx * vw
        val py = (ah - vh) / 2f + ry * vh
        val half = vw * 0.06f
        val l = (px - half).coerceIn(0f, aw - 2f)
        val t = (py - half).coerceIn(0f, ah - 2f)
        val w = (half * 2f).coerceAtMost(aw - l)
        val h = (half * 2f).coerceAtMost(ah - t)
        return MeteringRectangle(l.toInt(), t.toInt(), w.toInt(), h.toInt(), MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    // ---------------------------------------------------------------- CameraController.Callback

    override fun onSessionReady() {}

    override fun onConfigFailed(message: String) {
        main.post { listener.onConfigFailed(message) }
    }

    override fun onCameraError(message: String) = postError(message)

    override fun onStats(iso: Int, exposureNs: Long, focusDiopters: Float, afState: Int) {
        main.post { listener.onStats(iso, exposureNs, focusDiopters, afState) }
    }

    // ---------------------------------------------------------------- frame loop (thread gl)

    private fun drawFrame() {
        if (released) return
        val e = egl ?: return
        val st = surfaceTexture ?: return
        val r = renderer ?: return
        val pv = eglPreview ?: return
        val inf = info ?: return
        val sz = size
        try {
            e.makeCurrent(pv)
            st.updateTexImage()
            val ts = st.timestamp
            updateInput(st.dataSpace, inf, sz)

            // A matriz do SurfaceTexture gira o frame para o retrato natural do celular; usamos o buffer cru
            // e a nossa rotação (sensor + tela), espelhando o preview da frontal.
            val rot = cameraRotation(inf, displayDeg)
            buildTexMatrix(rot, inf.front, previewMatrix)
            val swap = rot % 180 != 0
            val cw = if (swap) sz.height else sz.width
            val chh = if (swap) sz.width else sz.height

            val m = monitor
            val prof = LogRenderer.outFor(profile)
            previewParams.useLut = m.lut && r.hasLut
            previewParams.lutIn = prof
            previewParams.out = if (m.lut) LogRenderer.OUT_709 else prof
            previewParams.tools = true
            previewParams.zebra = m.zebra
            previewParams.peaking = m.peaking
            previewParams.falseColor = m.falseColor
            previewParams.mag = m.magnify

            val pw = e.width(pv)
            val ph = e.height(pv)
            if (pw > 0 && ph > 0) {
                GLES20.glViewport(0, 0, pw, ph)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                var vw = pw
                var vh = pw * chh / cw
                if (vh > ph) {
                    vh = ph
                    vw = ph * cw / chh
                }
                r.draw(previewMatrix, input, previewParams, (pw - vw) / 2, (ph - vh) / 2, vw, vh)
                e.swap(pv)
            }

            val enc = eglEncoder
            if (recording && enc != null) {
                if (baseTsNs < 0) baseTsNs = ts
                e.makeCurrent(enc)
                r.draw(recordMatrix, input, recordParams, 0, 0, encW, encH)
                e.setPresentationTime(enc, ts - baseTsNs)
                e.swap(enc)
                elapsedNs = ts - baseTsNs
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

    /** Matriz YUV, faixa e transferência do frame atual, lidas do dataspace anexado pela câmera. */
    private fun updateInput(ds: Int, inf: CamInfo, sz: Size) {
        val standard = (ds shr 16) and 0x3F
        val transfer = (ds shr 22) and 0x1F
        val range = (ds shr 27) and 0x7
        input.hlg = when (transfer) {
            8 -> true
            0 -> inf.hlg10
            else -> false
        }
        input.tenBit = inf.hlg10
        input.fullRange = range != 2
        when (standard) {
            1 -> { input.kr = 0.2126f; input.kb = 0.0722f }
            2, 3, 4, 5 -> { input.kr = 0.299f; input.kb = 0.114f }
            6, 7 -> { input.kr = 0.2627f; input.kb = 0.0593f }
            else -> if (inf.hlg10) { input.kr = 0.2627f; input.kb = 0.0593f } else { input.kr = 0.299f; input.kb = 0.114f }
        }
        input.texelX = 1.5f / sz.width
        input.texelY = 1.5f / sz.height
        if (!dataSpaceLogged) {
            dataSpaceLogged = true
            Log.i(TAG, "dataspace=0x${Integer.toHexString(ds)} std=$standard transfer=$transfer range=$range")
        }
    }

    // ---------------------------------------------------------------- gravação (thread gl)

    private fun beginRecording(cfg: RecordConfig) {
        if (recording || released) return
        val e = egl ?: return
        val inf = info ?: return
        val sz = size
        var createdUri: Uri? = null
        var audioEnc: AudioEncoder? = null
        try {
            val rot = cameraRotation(inf, displayDeg)
            buildTexMatrix(rot, false, recordMatrix)
            val swap = rot % 180 != 0
            encW = if (swap) sz.height else sz.width
            encH = if (swap) sz.width else sz.height

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, cfg.name + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AppleLogCam/" + if (cfg.folder.isEmpty()) "" else "${cfg.folder}/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não consegui criar o arquivo em Movies/AppleLogCam")
            createdUri = uri
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Não consegui abrir o arquivo para escrita")
            pfd = descriptor
            outputUri = uri
            outputName = cfg.name

            val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)   // o frame já sai em pé

            audioEnc = if (cfg.audio) AudioEncoder.create() else null
            val s = MuxerSink(muxer, if (audioEnc != null) 2 else 1)
            sink = s

            val burn = cfg.burnLut && renderer?.hasLut == true
            val sdr = cfg.profile == Profile.REC709 || burn
            val venc = VideoEncoder(
                encW, encH, fps, cfg.bitrate,
                if (sdr) MediaFormat.COLOR_STANDARD_BT709 else MediaFormat.COLOR_STANDARD_BT2020,
                if (sdr) MediaFormat.COLOR_TRANSFER_SDR_VIDEO else MediaFormat.COLOR_TRANSFER_HLG,
                s
            )
            videoEncoder = venc
            eglEncoder = e.createWindowSurface(venc.inputSurface, tagHlg = !sdr)

            recordParams.out = LogRenderer.outFor(cfg.profile)
            recordParams.useLut = burn
            recordParams.lutIn = LogRenderer.outFor(cfg.profile)
            recordParams.tools = false
            recordParams.mag = 1f

            baseTsNs = -1L
            elapsedNs = 0L
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
        val name = outputName
        val gotFrames = baseTsNs >= 0
        cleanupRecording(discard = !gotFrames, uri = uri)
        main.post {
            listener.onRecording(false)
            listener.onSaved(if (gotFrames) uri else null, name)
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

    // ---------------------------------------------------------------- geometria

    private fun currentDisplayDegrees(): Int {
        val rotation = try { context.display?.rotation ?: 0 } catch (_: Throwable) { 0 }
        return rotation * 90
    }

    /** Graus (sentido horário) para girar o buffer cru do sensor e deixá-lo em pé na tela. */
    private fun cameraRotation(inf: CamInfo, displayDegrees: Int): Int =
        if (inf.front) (inf.sensorOrientation + displayDegrees) % 360
        else (inf.sensorOrientation - displayDegrees + 360) % 360

    /**
     * Matriz (column-major) que leva a coordenada da tela (u, v; v para cima) à coordenada do buffer cru
     * (s, t; t = 0 é o topo da imagem), girando [rotation] graus no sentido horário e, se [mirror], espelhando.
     */
    private fun buildTexMatrix(rotation: Int, mirror: Boolean, m: FloatArray) {
        // s = a*u + b*v + c ; t = d*u + e*v + f
        var a: Float; val b: Float; var c: Float; var d: Float; val ee: Float; var f: Float
        when (rotation) {
            90 -> { a = 0f; b = -1f; c = 1f; d = -1f; ee = 0f; f = 1f }
            180 -> { a = -1f; b = 0f; c = 1f; d = 0f; ee = 1f; f = 0f }
            270 -> { a = 0f; b = 1f; c = 0f; d = 1f; ee = 0f; f = 0f }
            else -> { a = 1f; b = 0f; c = 0f; d = 0f; ee = -1f; f = 1f }
        }
        if (mirror) {   // u -> 1 - u
            c += a; a = -a
            f += d; d = -d
        }
        java.util.Arrays.fill(m, 0f)
        m[0] = a; m[1] = d
        m[4] = b; m[5] = ee
        m[10] = 1f
        m[12] = c; m[13] = f
        m[15] = 1f
    }

    private fun postError(msg: String) {
        main.post { listener.onError(msg) }
    }
}
