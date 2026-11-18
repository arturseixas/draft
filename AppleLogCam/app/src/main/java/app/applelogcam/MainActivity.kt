package app.applelogcam

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), SurfaceHolder.Callback, CaptureEngine.Listener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var recBtn: Button
    private lateinit var monitorBtn: Button
    private lateinit var manualBtn: Button
    private lateinit var manualPanel: LinearLayout
    private lateinit var isoBar: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var shutterBar: SeekBar
    private lateinit var shutterLabel: TextView

    private var engine: CaptureEngine? = null
    private var surface: Surface? = null
    private var recording = false
    private var monitorLog = false
    private var manual = false
    private var isoMin = 50
    private var isoMax = 3200
    private var expMinNs = 100_000L
    private var expMaxNs = 33_333_333L

    private val shutterDenominators = intArrayOf(30, 48, 60, 90, 120, 250, 500, 1000, 2000)

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun styledButton(text: String): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isAllCaps = false
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(dp(170), dp(48)).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            textSize = 12f
            text = "Iniciando câmera…"
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(8); leftMargin = dp(8)
        })

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        recBtn = styledButton("Gravar").apply {
            setBackgroundColor(Color.argb(220, 200, 30, 30))
            setOnClickListener { toggleRecording() }
        }
        monitorBtn = styledButton("Monitor: 709").apply {
            setOnClickListener {
                monitorLog = !monitorLog
                engine?.monitorLog = monitorLog
                text = if (monitorLog) "Monitor: Log" else "Monitor: 709"
            }
        }
        manualBtn = styledButton("Exp: auto").apply {
            setOnClickListener { setManual(!manual) }
        }
        side.addView(recBtn)
        side.addView(monitorBtn)
        side.addView(manualBtn)
        root.addView(side, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(12)
        })

        manualPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        isoLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        isoBar = SeekBar(this).apply {
            max = 100
            progress = 30
            setOnSeekBarChangeListener(seekListener)
        }
        shutterLabel = TextView(this).apply { setTextColor(Color.WHITE) }
        shutterBar = SeekBar(this).apply {
            max = shutterDenominators.size - 1
            progress = 2   // 1/60
            setOnSeekBarChangeListener(seekListener)
        }
        manualPanel.addView(isoLabel)
        manualPanel.addView(isoBar)
        manualPanel.addView(shutterLabel)
        manualPanel.addView(shutterBar)
        root.addView(manualPanel, FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(12); leftMargin = dp(12)
        })

        setContentView(root)
        updateManualLabels()

        if (!granted(Manifest.permission.CAMERA) || !granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private val seekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            updateManualLabels()
            if (fromUser) pushManual()
        }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    }

    private fun currentIso(): Int {
        val t = isoBar.progress / 100.0
        return (isoMin * exp(t * ln(isoMax.toDouble() / isoMin))).roundToInt().coerceIn(isoMin, isoMax)
    }

    private fun currentExposureNs(): Long {
        val den = shutterDenominators[shutterBar.progress]
        return (1_000_000_000L / den).coerceIn(expMinNs, expMaxNs)
    }

    private fun updateManualLabels() {
        isoLabel.text = "ISO ${currentIso()}"
        shutterLabel.text = "Obturador 1/${shutterDenominators[shutterBar.progress]}"
    }

    private fun setManual(enabled: Boolean) {
        manual = enabled
        manualBtn.text = if (enabled) "Exp: manual" else "Exp: auto"
        manualPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        pushManual()
    }

    private fun pushManual() {
        engine?.setManual(manual, currentIso(), currentExposureNs())
    }

    // ---------------------------------------------------------------- permissões e superfície

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!granted(Manifest.permission.CAMERA)) {
            status.text = "Sem permissão de câmera"
            return
        }
        startEngineIfReady()
    }

    private fun startEngineIfReady() {
        val s = surface ?: return
        if (engine != null || !granted(Manifest.permission.CAMERA)) return
        engine = CaptureEngine(this, this).also {
            it.monitorLog = monitorLog
            it.start(s)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startEngineIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.release()      // bloqueia até o EGL soltar a Surface
        engine = null
        surface = null
        setRecordingUi(false)
    }

    // ---------------------------------------------------------------- ações

    private fun toggleRecording() {
        val e = engine ?: return
        if (recording) {
            e.stopRecording()
        } else {
            e.startRecording(withAudio = granted(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun setRecordingUi(on: Boolean) {
        recording = on
        recBtn.text = if (on) "Parar" else "Gravar"
    }

    // ---------------------------------------------------------------- CaptureEngine.Listener (thread principal)

    override fun onReady(description: String, isoRange: IntArray?, exposureRangeNs: LongArray?) {
        status.text = description
        if (isoRange != null) { isoMin = isoRange[0]; isoMax = minOf(isoRange[1], 3200) }
        if (exposureRangeNs != null) { expMinNs = exposureRangeNs[0]; expMaxNs = minOf(exposureRangeNs[1], 33_333_333L) }
        updateManualLabels()
        pushManual()
    }

    override fun onError(message: String) {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setRecordingUi(false)
    }

    override fun onRecording(recording: Boolean) {
        setRecordingUi(recording)
    }

    override fun onSaved(uri: Uri?) {
        Toast.makeText(
            this,
            if (uri != null) "Salvo em Movies/AppleLogCam" else "Nada gravado",
            Toast.LENGTH_SHORT
        ).show()
    }
}
