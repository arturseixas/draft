package app.applelogcam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** Camera2 com perfil de faixa dinâmica HLG10 (10 bits, BT.2020) em 4K30. */
class CameraController(context: Context) {

    class Choice(
        val id: String,
        val size: Size,
        val fps: Int,
        val fpsRange: Range<Int>,
        val sensorOrientation: Int,
        val isoRange: Range<Int>?,
        val exposureRangeNs: Range<Long>?,
        val timestampRealtime: Boolean
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera2").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var fpsRange: Range<Int> = Range(30, 30)

    private var manual = false
    private var iso = 100
    private var exposureNs = 16_666_666L

    /** Procura uma câmera traseira que ofereça HLG10 e escolhe 3840x2160 (ou o maior 16:9 abaixo disso). */
    fun choose(): Choice? {
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val profiles = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) ?: continue
            if (!profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) continue

            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            val size = sizes.filter { it.width <= 3840 && it.width * 9 == it.height * 16 }.maxByOrNull { it.width }
                ?: sizes.filter { it.width <= 3840 }.maxByOrNull { it.width * it.height }
                ?: continue

            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            val range = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
                ?: Range(30, 30)

            val tsSource = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            val realtime = tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            if (!realtime) Log.w(LogRenderer.TAG, "Timestamps da câmera não são REALTIME; sync de áudio pode desalinhar")

            return Choice(
                id = id,
                size = size,
                fps = 30,
                fpsRange = range,
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                exposureRangeNs = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                timestampRealtime = realtime
            )
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun open(choice: Choice, target: Surface, fail: (String) -> Unit) {
        fpsRange = choice.fpsRange
        manager.openCamera(choice.id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, target, fail)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
                fail("Câmera desconectada")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                fail("Erro da câmera (código $error)")
            }
        })
    }

    private fun createSession(camera: CameraDevice, target: Surface, fail: (String) -> Unit) {
        val output = OutputConfiguration(target)
        output.dynamicRangeProfile = DynamicRangeProfiles.HLG10

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(target)
                        b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                        builder = b
                        submit()
                    } catch (t: Throwable) {
                        fail("Falha ao iniciar a captura: ${t.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    fail("A câmera recusou a sessão HLG10 em 4K30")
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private fun submit() {
        val s = session ?: return
        val b = builder ?: return
        if (manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        try {
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (t: Throwable) {
            Log.w(LogRenderer.TAG, "setRepeatingRequest: ${t.message}")
        }
    }

    fun setManual(enabled: Boolean, iso: Int, exposureNs: Long) {
        handler.post {
            this.manual = enabled
            this.iso = iso
            this.exposureNs = exposureNs
            submit()
        }
    }

    fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        session = null
        device = null
        thread.quitSafely()
    }
}
