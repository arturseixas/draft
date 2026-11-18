package app.applelogcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Tudo o que o app precisa saber de uma câmera (traseira lógica com todas as lentes, ou frontal). */
class CamInfo(
    val id: String,
    val front: Boolean,
    val hlg10: Boolean,
    val score: Float,
    val sensorOrientation: Int,
    val activeArray: Rect,
    private val map: StreamConfigurationMap,
    val fpsRanges: List<Range<Int>>,
    val zoomRange: Range<Float>,
    val lensStops: List<Float>,
    val isoRange: Range<Int>,
    val exposureRange: Range<Long>,
    val evRange: Range<Int>,
    val evStep: Float,
    val minFocusDiopters: Float,
    val awbModes: IntArray,
    val videoStabModes: IntArray,
    val oisModes: IntArray,
    val edgeModes: IntArray,
    val nrModes: IntArray,
    val afModes: IntArray,
    val hasFlash: Boolean,
    val manualSensor: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int
) {
    val sizes: List<Size> = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()

    fun sizeFor(height: Int): Size {
        val wide = sizes.filter { it.width * 9 == it.height * 16 }
        return wide.firstOrNull { it.height == height }
            ?: wide.filter { it.height <= height }.maxByOrNull { it.height }
            ?: wide.maxByOrNull { it.height }
            ?: sizes.maxBy { it.width * it.height }
    }

    fun supportedFps(size: Size): List<Int> {
        val minDur = try { map.getOutputMinFrameDuration(SurfaceTexture::class.java, size) } catch (_: Throwable) { 0L }
        return listOf(24, 25, 30, 60).filter { f ->
            fpsRanges.any { it.upper == f } && (minDur == 0L || minDur <= 1_000_000_000L / f + 1000L)
        }.ifEmpty { listOf(30) }
    }

    fun fpsRange(fps: Int): Range<Int> =
        fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: Range(fps, fps)
}

/** Estado dos controles manuais/automáticos. Imutável: cada mudança gera uma cópia. */
data class CamControls(
    val aeAuto: Boolean = true,
    val iso: Int = 200,
    val exposureNs: Long = 20_833_333L,
    val evSteps: Int = 0,
    val aeLock: Boolean = false,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val awbLock: Boolean = false,
    val focusMode: Int = FOCUS_CONTINUOUS,
    val focusDiopters: Float = 0f,
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val stabilization: Boolean = true,
    val sharpening: Boolean = false,
    val noiseReduction: Int = 1,
    val antibanding: Int = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
) {
    companion object {
        const val FOCUS_CONTINUOUS = 0
        const val FOCUS_POINT = 1
        const val FOCUS_MANUAL = 2
    }
}

class CameraController(context: Context) {

    interface Callback {
        fun onSessionReady()
        fun onConfigFailed(message: String)
        fun onCameraError(message: String)
        fun onStats(iso: Int, exposureNs: Long, focusDiopters: Float, afState: Int)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera2").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var info: CamInfo? = null
    private var fps = 30
    private var fpsRange: Range<Int> = Range(30, 30)
    private var controls = CamControls()
    private var region: MeteringRectangle? = null
    private var generation = 0
    private var callback: Callback? = null
    private var frameCount = 0

    private val noRegion = arrayOf(MeteringRectangle(0, 0, 0, 0, MeteringRectangle.METERING_WEIGHT_DONT_CARE))

    // ---------------------------------------------------------------- descoberta

    fun listCameras(): List<CamInfo> {
        val out = ArrayList<CamInfo>()
        for (id in manager.cameraIdList) {
            try {
                val ch = manager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) continue
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val front = facing == CameraCharacteristics.LENS_FACING_FRONT
                val hlg = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
                val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val zoom = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    ?: Range(1f, ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
                val score = (if (hlg) 100f else 0f) + (if (logical) 10f else 0f) + (zoom.upper / zoom.lower).coerceAtMost(9f)

                out += CamInfo(
                    id = id,
                    front = front,
                    hlg10 = hlg,
                    score = score,
                    sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
                    map = map,
                    fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
                    zoomRange = zoom,
                    lensStops = lensStops(ch, zoom, front),
                    isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200),
                    exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L),
                    evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
                    evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f,
                    minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
                    videoStabModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0),
                    oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0),
                    edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0),
                    nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0),
                    afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
                    hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                    manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                    maxAfRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
                    maxAeRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                )
                Log.i(LogRenderer.TAG, "câmera $id front=$front hlg10=$hlg logical=$logical zoom=$zoom")
            } catch (t: Throwable) {
                Log.w(LogRenderer.TAG, "câmera $id ignorada: ${t.message}")
            }
        }
        return out
    }

    /**
     * Paradas de lente (0.5x, 1x, 2x, 5x...). Calculadas a partir da distância focal equivalente de cada
     * câmera física dentro da câmera lógica; o zoom da câmera lógica troca de lente sozinho nesses pontos.
     */
    private fun lensStops(ch: CameraCharacteristics, zoom: Range<Float>, front: Boolean): List<Float> {
        fun equivalent(c: CameraCharacteristics): Float? {
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val diag = hypot(s.width, s.height)
            return if (diag > 0f) f / diag else null
        }

        val stops = sortedSetOf<Float>()
        val ref = equivalent(ch)
        if (ref != null) {
            for (pid in ch.physicalCameraIds) {
                val pc = try { manager.getCameraCharacteristics(pid) } catch (_: Throwable) { null } ?: continue
                val e = equivalent(pc) ?: continue
                val ratio = e / ref
                if (ratio < 1.3f) continue
                val r = (ratio * 2f).roundToInt() / 2f
                if (r <= zoom.upper + 0.01f) stops += r
            }
        }
        if (zoom.lower < 0.95f) stops += zoom.lower
        stops += 1f
        if (stops.none { it in 1.5f..2.6f } && zoom.upper >= 2f) stops += 2f
        if (!front && stops.none { it > 2.6f } && zoom.upper >= 5f) stops += 5f

        val list = ArrayList<Float>()
        for (s in stops) if (list.isEmpty() || s - list.last() > 0.15f) list += s
        return list
    }

    // ---------------------------------------------------------------- abrir / fechar

    @SuppressLint("MissingPermission")
    fun open(info: CamInfo, fps: Int, target: Surface, controls: CamControls, cb: Callback) {
        handler.post {
            closeInternal()
            val gen = ++generation
            this.info = info
            this.fps = fps
            this.fpsRange = info.fpsRange(fps)
            this.controls = controls
            this.callback = cb
            this.region = null
            try {
                manager.openCamera(info.id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation) { camera.close(); return }
                        device = camera
                        createSession(gen, camera, info, target)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (gen == generation) { device = null; cb.onCameraError("Câmera desconectada") }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (gen == generation) { device = null; cb.onCameraError("Erro da câmera (código $error)") }
                    }
                })
            } catch (t: Throwable) {
                cb.onCameraError("Não consegui abrir a câmera: ${t.message}")
            }
        }
    }

    private fun createSession(gen: Int, camera: CameraDevice, info: CamInfo, target: Surface) {
        try {
            val output = OutputConfiguration(target)
            if (info.hlg10) output.dynamicRangeProfile = DynamicRangeProfiles.HLG10
            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(target)
            builder = b
            applyControls(b)
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) { s.close(); return }
                        session = s
                        submit()
                        callback?.onSessionReady()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == generation) callback?.onConfigFailed("A câmera recusou essa combinação de resolução e FPS")
                    }
                }
            )
            config.sessionParameters = b.build()
            camera.createCaptureSession(config)
        } catch (t: Throwable) {
            callback?.onConfigFailed("Falha ao configurar a sessão: ${t.message}")
        }
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        session = null
        device = null
        builder = null
    }

    fun shutdown() {
        val latch = CountDownLatch(1)
        handler.post {
            generation++
            closeInternal()
            callback = null
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- controles

    fun update(c: CamControls) {
        handler.post {
            controls = c
            submit()
        }
    }

    /** Define (ou limpa, com null) o ponto de foco/medição. Com [trigger], dispara um ciclo de AF nesse ponto. */
    fun setRegion(r: MeteringRectangle?, trigger: Boolean, c: CamControls) {
        handler.post {
            region = r
            controls = c
            submit()
            if (trigger) triggerAf()
        }
    }

    private fun submit() {
        val s = session ?: return
        val b = builder ?: return
        applyControls(b)
        try {
            s.setRepeatingRequest(b.build(), captureCallback, handler)
        } catch (t: Throwable) {
            Log.w(LogRenderer.TAG, "setRepeatingRequest: ${t.message}")
        }
    }

    private fun triggerAf() {
        val s = session ?: return
        val b = builder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, handler)
        } catch (t: Throwable) {
            Log.w(LogRenderer.TAG, "AF trigger: ${t.message}")
        } finally {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }
    }

    private fun pick(available: IntArray, want: Int, fallback: Int): Int = when {
        available.contains(want) -> want
        available.contains(fallback) -> fallback
        else -> available.firstOrNull() ?: fallback
    }

    private fun applyControls(b: CaptureRequest.Builder) {
        val inf = info ?: return
        val c = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, c.antibanding)

        val frameNs = 1_000_000_000L / fps
        if (c.aeAuto || !inf.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evSteps.coerceIn(inf.evRange.lower, inf.evRange.upper))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso.coerceIn(inf.isoRange.lower, inf.isoRange.upper))
            val maxExp = minOf(inf.exposureRange.upper, frameNs)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exposureNs.coerceIn(inf.exposureRange.lower, maxExp))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        }

        b.set(
            CaptureRequest.FLASH_MODE,
            if (c.torch && inf.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
        )
        b.set(
            CaptureRequest.CONTROL_AWB_MODE,
            if (inf.awbModes.contains(c.awbMode)) c.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO
        )
        b.set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)

        when (c.focusMode) {
            CamControls.FOCUS_MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters.coerceIn(0f, inf.minFocusDiopters))
            }
            CamControls.FOCUS_POINT -> b.set(
                CaptureRequest.CONTROL_AF_MODE,
                pick(inf.afModes, CameraMetadata.CONTROL_AF_MODE_AUTO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            )
            else -> b.set(
                CaptureRequest.CONTROL_AF_MODE,
                pick(inf.afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CameraMetadata.CONTROL_AF_MODE_AUTO)
            )
        }
        val r = region
        val regions = if (r != null) arrayOf(r) else noRegion
        if (inf.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        if (inf.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)

        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom.coerceIn(inf.zoomRange.lower, inf.zoomRange.upper))

        val stab = c.stabilization && inf.videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (stab) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        if (inf.oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        b.set(
            CaptureRequest.EDGE_MODE,
            pick(inf.edgeModes, if (c.sharpening) CameraMetadata.EDGE_MODE_FAST else CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
        )
        val nr = when (c.noiseReduction) {
            0 -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
            2 -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        }
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, pick(inf.nrModes, nr, CameraMetadata.NOISE_REDUCTION_MODE_FAST))
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (++frameCount % 6 != 0) return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: 0
            callback?.onStats(iso, exp, fd, af)
        }
    }
}
