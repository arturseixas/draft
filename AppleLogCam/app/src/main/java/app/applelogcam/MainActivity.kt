package app.applelogcam

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : Activity(), SurfaceHolder.Callback, CaptureEngine.Listener {

    private enum class Param(val title: String) {
        FPS("FPS"), SHUTTER("SHUTTER"), ISO("ISO"), EV("EV"), WB("WB"), FOCUS("FOCUS")
    }

    private class WbOpt(val label: String, val mode: Int)

    companion object {
        private val ISO_LIST = intArrayOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
        )
        private val ANGLES = floatArrayOf(11.25f, 22.5f, 45f, 90f, 120f, 144f, 172.8f, 180f, 216f, 270f, 360f)
        private val SPEEDS = intArrayOf(24, 25, 30, 48, 50, 60, 100, 120, 125, 160, 250, 500, 1000, 2000, 4000, 8000)
        private val FRAME_LINES = listOf(
            0f to "OFF", 2.39f to "2.39:1", 1.85f to "1.85:1", 4f / 3f to "4:3", 1f to "1:1", 9f / 16f to "9:16"
        )
        private val ZEBRA_LEVELS = listOf(70, 80, 90, 95, 100)
        private val BITRATES = listOf(50, 100, 150, 200)
        private val WB_OPTIONS = listOf(
            WbOpt("AUTO", CameraMetadata.CONTROL_AWB_MODE_AUTO),
            WbOpt("3200K", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
            WbOpt("4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
            WbOpt("5600K", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            WbOpt("6500K", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            WbOpt("7500K", CameraMetadata.CONTROL_AWB_MODE_SHADE)
        )
        private const val FOCUS_STEPS = 48
    }

    private lateinit var settings: CamSettings
    private var controls = CamControls()
    private var engine: CaptureEngine? = null
    private var surface: Surface? = null

    private var info: CamInfo? = null
    private var cameras: List<CamInfo> = emptyList()
    private var size = Size(3840, 2160)
    private var fps = 30
    private var recording = false
    private var lastIso = 0
    private var lastExp = 0L
    private var lastFocus = 0f
    private var openParam: Param? = null
    private var focusAssist = false
    private var zoomAnim: ValueAnimator? = null
    private var pinching = false
    private var chromeKey = ""

    private val ui = Handler(Looper.getMainLooper())
    private val content = RectF()

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var topBar: LinearLayout
    private lateinit var recDot: TextView
    private lateinit var recLabel: TextView
    private lateinit var timecode: TextView
    private lateinit var clipLabel: TextView
    private lateinit var statusLine1: TextView
    private lateinit var statusLine2: TextView
    private lateinit var bottomStack: LinearLayout
    private lateinit var dialPanel: LinearLayout
    private lateinit var dial: RulerDial
    private lateinit var dialLeft: TextView
    private lateinit var lensBar: LensBar
    private val chips = LinkedHashMap<Param, ParamChip>()
    private lateinit var leftBar: LinearLayout
    private lateinit var rightBar: LinearLayout
    private lateinit var recordButton: RecordButton
    private lateinit var thumb: ImageView
    private lateinit var flipBtn: TextView
    private val toggles = LinkedHashMap<String, TextView>()
    private lateinit var meter: AudioMeter
    private lateinit var settingsPanel: FrameLayout
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // ================================================================ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = CamSettings(getSharedPreferences("cam", MODE_PRIVATE))
        controls = baseControls()
        createGestures()
        buildUi()
        setContentView(root)
        pushMonitor()
        refreshAll()
        loadThumb()

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            when {
                settingsPanel.visibility == View.VISIBLE -> closeSettings()
                openParam != null -> closeDial()
                else -> finish()
            }
        }

        if (!granted(Manifest.permission.CAMERA) || !granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        ui.post(statusTicker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(statusTicker)
        super.onPause()
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event.repeatCount == 0) toggleRecord()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!granted(Manifest.permission.CAMERA)) {
            overlay.flash("SEM PERMISSÃO DE CÂMERA")
            return
        }
        startEngineIfReady()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startEngineIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.release()
        engine = null
        surface = null
        setRecordingUi(false)
    }

    private fun startEngineIfReady() {
        val s = surface ?: return
        if (engine != null || !granted(Manifest.permission.CAMERA)) return
        val e = CaptureEngine(this, this)
        engine = e
        pushMonitor()
        e.start(s, settings.front, settings.height, settings.fps, controls)
    }

    // ================================================================ montagem da interface

    private fun text(s: String, sizeSp: Float, color: Int, tf: Typeface = Ui.COND, spacing: Float = 0.1f) =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            typeface = tf
            letterSpacing = spacing
            includeFontPadding = false
        }

    private fun outline(radiusDp: Float, stroke: Int = Ui.FAINT, fill: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp)
        setStroke(dpi(1), stroke)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay = OverlayView(this)
        overlay.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- barra superior: REC + timecode à esquerda, status à direita
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(14), dpi(10), dpi(14), dpi(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xC8000000.toInt(), 0x00000000)
            )
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recDot = text("●", 12f, Ui.DIM, Ui.MONO, 0f)
        recLabel = text("STBY", 12f, Ui.DIM, Ui.COND, 0.2f)
        timecode = text("00:00:00:00", 20f, Ui.CREAM, Ui.MONO_BOLD, 0.02f)
        recRow.addView(recDot)
        recRow.addView(recLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dpi(6) })
        recRow.addView(timecode, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dpi(14) })
        clipLabel = text("", 10f, Ui.DIM, Ui.MONO, 0.08f)
        left.addView(recRow)
        left.addView(clipLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(4) })
        topBar.addView(left, LinearLayout.LayoutParams(WRAP, WRAP))
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        statusLine1 = text("", 11f, Ui.CREAM, Ui.MONO, 0.04f)
        statusLine2 = text("", 10f, Ui.DIM, Ui.MONO, 0.04f)
        right.addView(statusLine1, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })
        right.addView(statusLine2, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END; topMargin = dpi(5) })
        topBar.addView(right, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP))

        // ---- parte de baixo: régua (quando aberta), seletor de lentes, chips de parâmetros
        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        dialPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = outline(14f, Ui.LINE, Ui.PANEL)
            visibility = View.GONE
        }
        dialLeft = text("AUTO", 12f, Ui.DIM, Ui.COND, 0.16f).apply { gravity = Gravity.CENTER }
        dial = RulerDial(this)
        val dialClose = text("✕", 16f, Ui.DIM, Ui.COND, 0f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { closeDial() }
        }
        dialPanel.addView(dialLeft, LinearLayout.LayoutParams(dpi(76), MATCH))
        dialPanel.addView(dial, LinearLayout.LayoutParams(0, dpi(84), 1f))
        dialPanel.addView(dialClose, LinearLayout.LayoutParams(dpi(50), MATCH))
        bottomStack.addView(dialPanel, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dpi(16), 0, dpi(16), dpi(6))
        })

        lensBar = LensBar(this).apply { onSelect = { setZoom(it, true) } }
        bottomStack.addView(lensBar, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dpi(2) })

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(8), dpi(14), dpi(8), dpi(2))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xD6000000.toInt(), 0x00000000)
            )
        }
        for (p in Param.entries) {
            val chip = ParamChip(this, p.title)
            chip.setOnClickListener { openDial(p) }
            chipRow.addView(chip, LinearLayout.LayoutParams(0, dpi(56), 1f))
            chips[p] = chip
        }
        bottomStack.addView(chipRow, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottomStack, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // ---- barra lateral esquerda: menu, ferramentas, medidor de áudio
        leftBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpi(14), 0, dpi(12))
            setBackgroundColor(Ui.BG)
        }
        val menu = text("MENU", 12f, Ui.CREAM, Ui.COND, 0.22f).apply {
            gravity = Gravity.CENTER
            background = outline(6f)
            setPadding(dpi(11), dpi(7), dpi(11), dpi(7))
            setOnClickListener { openSettings() }
        }
        leftBar.addView(menu)
        leftBar.addView(View(this), LinearLayout.LayoutParams(1, dpi(10)))
        for ((key, label) in listOf(
            "lut" to "LUT", "zebra" to "ZEBRA", "peak" to "PEAK", "false" to "FALSE", "grid" to "GRID", "torch" to "TORCH"
        )) {
            val t = text(label, 11f, Ui.DIM, Ui.MONO_BOLD, 0.06f).apply {
                gravity = Gravity.CENTER
                setPadding(dpi(6), dpi(7), dpi(6), dpi(7))
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onToggle(key)
                }
            }
            toggles[key] = t
            leftBar.addView(t)
        }
        leftBar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        meter = AudioMeter(this)
        leftBar.addView(meter)
        leftBar.addView(text("MIC", 9f, Ui.DIM, Ui.COND, 0.2f), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(3) })
        root.addView(leftBar, FrameLayout.LayoutParams(dpi(84), MATCH))

        // ---- barra lateral direita: último clipe, gravar, trocar câmera
        rightBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = outline(10f, Ui.FAINT, 0xFF1A1918.toInt())
            clipToOutline = true
            contentDescription = "Último clipe"
            setOnClickListener { openLastClip() }
        }
        rightBar.addView(thumb, LinearLayout.LayoutParams(dpi(46), dpi(46)).apply { bottomMargin = dpi(22) })
        recordButton = RecordButton(this).apply { setOnClickListener { toggleRecord() } }
        rightBar.addView(recordButton, LinearLayout.LayoutParams(dpi(74), dpi(74)))
        flipBtn = text("⇄ CAM", 11f, Ui.CREAM, Ui.COND, 0.16f).apply {
            gravity = Gravity.CENTER
            background = outline(6f)
            setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
            setOnClickListener { flipCamera() }
        }
        rightBar.addView(flipBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(22) })
        root.addView(rightBar, FrameLayout.LayoutParams(dpi(96), MATCH, Gravity.END))

        // ---- menu de configurações (cobre o quadro)
        settingsPanel = FrameLayout(this).apply {
            setBackgroundColor(0xF20B0B0C.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(settingsPanel, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutChrome() }
    }

    /** Posiciona as barras em volta do quadro (16:9 dentro da tela 20:9). */
    private fun layoutChrome() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val aspect = engine?.contentAspect() ?: (16f / 9f)
        var cw = w.toFloat()
        var ch = cw / aspect
        if (ch > h) {
            ch = h.toFloat()
            cw = ch * aspect
        }
        val l = (w - cw) / 2f
        val t = (h - ch) / 2f
        val key = "$w:$h:$aspect"
        if (key == chromeKey) return
        chromeKey = key
        content.set(l, t, l + cw, t + ch)
        overlay.content.set(content)
        overlay.invalidate()

        val li = l.roundToInt()
        val ti = t.roundToInt()
        val cwi = cw.roundToInt()
        val chi = ch.roundToInt()
        place(topBar, li, ti, cwi, WRAP, Gravity.TOP or Gravity.START)
        place(bottomStack, li, h - (ti + chi), cwi, WRAP, Gravity.BOTTOM or Gravity.START)
        place(leftBar, 0, 0, maxOf(li, dpi(84)), MATCH, Gravity.TOP or Gravity.START)
        place(rightBar, 0, 0, maxOf(w - (li + cwi), dpi(96)), MATCH, Gravity.TOP or Gravity.END)
        place(settingsPanel, li, ti, cwi, chi, Gravity.TOP or Gravity.START)
    }

    private fun place(v: View, x: Int, y: Int, width: Int, height: Int, gravity: Int) {
        val lp = (v.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(width, height)
        lp.width = width
        lp.height = height
        lp.gravity = gravity
        lp.leftMargin = if ((gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END) 0 else x
        lp.rightMargin = 0
        if ((gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
            lp.topMargin = 0
            lp.bottomMargin = y
        } else {
            lp.topMargin = y
            lp.bottomMargin = 0
        }
        v.layoutParams = lp
    }

    // ================================================================ gestos no quadro

    private fun createGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                zoomAnim?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(controls.zoom * detector.scaleFactor, false)
                overlay.flash(fmtZoom(controls.zoom) + "×")
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
            }
        })
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (openParam != null) closeDial() else focusAt(e.x, e.y, false)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetFocus()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!pinching) focusAt(e.x, e.y, true)
            }
        })
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        if (!content.contains(x, y)) return
        val e = engine ?: return
        val nx = (x - content.left) / content.width()
        val ny = (y - content.top) / content.height()
        val rect = e.meteringRect(nx, ny) ?: return
        controls = controls.copy(focusMode = CamControls.FOCUS_POINT, aeLock = false)
        e.setRegion(rect, true, controls)
        overlay.showFocus(x, y, false)
        overlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        ui.removeCallbacks(lockRunnable)
        if (lock) ui.postDelayed(lockRunnable, 700)
        refreshChips()
    }

    private val lockRunnable = Runnable {
        controls = controls.copy(aeLock = true)
        engine?.updateControls(controls)
        overlay.setLocked(true)
        overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        refreshChips()
    }

    private fun resetFocus() {
        ui.removeCallbacks(lockRunnable)
        controls = controls.copy(focusMode = CamControls.FOCUS_CONTINUOUS, aeLock = false)
        engine?.setRegion(null, false, controls)
        overlay.hideFocus()
        overlay.flash("AF-C · MEDIÇÃO CENTRAL")
        refreshChips()
    }

    private fun setZoom(target: Float, animate: Boolean) {
        val inf = info ?: return
        val t = target.coerceIn(inf.zoomRange.lower, inf.zoomRange.upper)
        zoomAnim?.cancel()
        if (!animate) {
            controls = controls.copy(zoom = t)
            engine?.updateControls(controls)
            lensBar.zoom = t
            return
        }
        zoomAnim = ValueAnimator.ofFloat(controls.zoom, t).apply {
            duration = 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val z = it.animatedValue as Float
                controls = controls.copy(zoom = z)
                engine?.updateControls(controls)
                lensBar.zoom = z
            }
            start()
        }
    }

    // ================================================================ réguas de parâmetros

    private fun frameNs() = 1_000_000_000L / fps

    private fun shutterLabel(exp: Long): String {
        if (exp <= 0) return "—"
        return if (settings.shutterAngle) {
            val angle = exp.toDouble() * fps * 360.0 / 1e9
            if (abs(angle - angle.roundToInt()) < 0.05) "${angle.roundToInt()}°"
            else String.format(Locale.US, "%.1f°", angle)
        } else {
            val den = (1e9 / exp).roundToLong()
            if (den <= 1) String.format(Locale.US, "%.1fs", exp / 1e9) else "1/$den"
        }
    }

    private fun shutterValues(inf: CamInfo): List<Long> {
        val lo = inf.exposureRange.lower
        val hi = minOf(inf.exposureRange.upper, frameNs())
        val raw = if (settings.shutterAngle) {
            ANGLES.map { (it / 360.0 * 1e9 / fps).toLong() }
        } else {
            SPEEDS.filter { it >= fps }.map { 1_000_000_000L / it }.sortedDescending()
        }
        val list = raw.filter { it in lo..hi }
        return if (settings.shutterAngle) list else list
    }

    private fun isoValues(inf: CamInfo): List<Int> =
        ISO_LIST.filter { it in inf.isoRange.lower..inf.isoRange.upper }
            .ifEmpty { listOf(inf.isoRange.lower, inf.isoRange.upper) }

    private fun evLabel(steps: Int): String {
        val inf = info ?: return "±0"
        val v = steps * inf.evStep
        return if (abs(v) < 0.05f) "±0" else String.format(Locale.US, "%+.1f", v)
    }

    private fun focusLabel(d: Float): String = when {
        d <= 0.001f -> "∞"
        1f / d < 1f -> "${(100f / d).roundToInt()}cm"
        else -> String.format(Locale.US, "%.1fm", 1f / d)
    }

    private fun wbOptions(inf: CamInfo) = WB_OPTIONS.filter { it.mode == CameraMetadata.CONTROL_AWB_MODE_AUTO || inf.awbModes.contains(it.mode) }

    private fun nearestIndex(values: List<Long>, v: Long): Int =
        values.indices.minByOrNull { abs(values[it] - v) } ?: 0

    private fun openDial(p: Param) {
        if (openParam == p) {
            closeDial()
            return
        }
        val inf = info ?: return
        when (p) {
            Param.FPS -> {
                if (recording) { overlay.flash("PARE A GRAVAÇÃO PARA MUDAR O FPS"); return }
                val list = inf.supportedFps(size)
                dial.setItems(list.map { "$it" }, list.indexOf(fps).coerceAtLeast(0))
                setDialLeft(null, false) {}
                dial.onChange = null
                dial.onCommit = { i -> if (list[i] != fps) changeFps(list[i]) }
            }
            Param.SHUTTER -> {
                if (!inf.manualSensor) { overlay.flash("ESSA CÂMERA NÃO ACEITA OBTURADOR MANUAL"); return }
                val values = shutterValues(inf)
                if (values.isEmpty()) return
                val cur = if (controls.aeAuto) lastExp else controls.exposureNs
                dial.setItems(values.map { shutterLabel(it) }, nearestIndex(values, cur))
                setDialLeft("AUTO", controls.aeAuto) { setAutoExposure() }
                dial.onChange = { i -> setManualExposure(exp = values[i]) }
                dial.onCommit = null
            }
            Param.ISO -> {
                if (!inf.manualSensor) { overlay.flash("ESSA CÂMERA NÃO ACEITA ISO MANUAL"); return }
                val values = isoValues(inf)
                val cur = if (controls.aeAuto) lastIso else controls.iso
                val idx = values.indices.minByOrNull { abs(values[it] - cur) } ?: 0
                dial.setItems(values.map { "$it" }, idx)
                setDialLeft("AUTO", controls.aeAuto) { setAutoExposure() }
                dial.onChange = { i -> setManualExposure(iso = values[i]) }
                dial.onCommit = null
            }
            Param.EV -> {
                if (inf.evRange.lower == inf.evRange.upper) { overlay.flash("SEM COMPENSAÇÃO DE EXPOSIÇÃO"); return }
                val steps = (inf.evRange.lower..inf.evRange.upper).toList()
                dial.setItems(steps.map { evLabel(it) }, steps.indexOf(controls.evSteps).coerceAtLeast(0))
                setDialLeft("AE-L", controls.aeLock) {
                    controls = controls.copy(aeLock = !controls.aeLock)
                    pushControls()
                    refreshDialLeft("AE-L", controls.aeLock)
                }
                dial.onChange = { i ->
                    if (!controls.aeAuto) overlay.flash("EV LIGA A EXPOSIÇÃO AUTOMÁTICA")
                    controls = controls.copy(evSteps = steps[i], aeAuto = true)
                    pushControls()
                }
                dial.onCommit = null
            }
            Param.WB -> {
                val opts = wbOptions(inf)
                dial.setItems(opts.map { it.label }, opts.indexOfFirst { it.mode == controls.awbMode }.coerceAtLeast(0))
                setDialLeft("LOCK", controls.awbLock) {
                    controls = controls.copy(awbLock = !controls.awbLock)
                    pushControls()
                    refreshDialLeft("LOCK", controls.awbLock)
                }
                dial.onChange = { i ->
                    controls = controls.copy(awbMode = opts[i].mode)
                    pushControls()
                }
                dial.onCommit = null
            }
            Param.FOCUS -> {
                if (inf.minFocusDiopters <= 0f) { overlay.flash("FOCO FIXO NESSA CÂMERA"); return }
                val values = (0..FOCUS_STEPS).map { inf.minFocusDiopters * it / FOCUS_STEPS }
                val cur = if (controls.focusMode == CamControls.FOCUS_MANUAL) controls.focusDiopters else lastFocus
                val idx = values.indices.minByOrNull { abs(values[it] - cur) } ?: 0
                dial.setItems(values.map { focusLabel(it) }, idx)
                setDialLeft("AF-C", controls.focusMode == CamControls.FOCUS_CONTINUOUS) {
                    resetFocus()
                    refreshDialLeft("AF-C", true)
                }
                dial.onChange = { i ->
                    ui.removeCallbacks(assistOff)
                    setFocusAssist(true)
                    controls = controls.copy(focusMode = CamControls.FOCUS_MANUAL, focusDiopters = values[i])
                    pushControls()
                    refreshDialLeft("AF-C", false)
                }
                dial.onCommit = { ui.removeCallbacks(assistOff); ui.postDelayed(assistOff, 1400) }
            }
        }
        openParam = p
        dialPanel.visibility = View.VISIBLE
        lensBar.visibility = View.GONE
        refreshChips()
    }

    private fun setDialLeft(label: String?, on: Boolean, action: () -> Unit) {
        if (label == null) {
            dialLeft.visibility = View.INVISIBLE
            dialLeft.setOnClickListener(null)
            return
        }
        dialLeft.visibility = View.VISIBLE
        refreshDialLeft(label, on)
        dialLeft.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun refreshDialLeft(label: String, on: Boolean) {
        dialLeft.text = label
        dialLeft.setTextColor(if (on) Ui.AMBER else Ui.DIM)
    }

    private fun closeDial() {
        if (openParam == Param.FOCUS) {
            ui.removeCallbacks(assistOff)
            setFocusAssist(false)
        }
        openParam = null
        dialPanel.visibility = View.GONE
        lensBar.visibility = View.VISIBLE
        refreshChips()
    }

    private val assistOff = Runnable { setFocusAssist(false) }

    private fun setFocusAssist(on: Boolean) {
        if (focusAssist == on) return
        focusAssist = on
        pushMonitor()
    }

    private fun setAutoExposure() {
        controls = controls.copy(aeAuto = true, aeLock = false)
        pushControls()
        refreshDialLeft("AUTO", true)
    }

    private fun setManualExposure(iso: Int? = null, exp: Long? = null) {
        val baseIso = if (controls.aeAuto) (if (lastIso > 0) lastIso else 200) else controls.iso
        val baseExp = if (controls.aeAuto) {
            if (lastExp > 0) minOf(lastExp, frameNs()) else frameNs() / 2
        } else controls.exposureNs
        controls = controls.copy(aeAuto = false, aeLock = false, iso = iso ?: baseIso, exposureNs = exp ?: baseExp)
        pushControls()
        refreshDialLeft("AUTO", false)
    }

    private fun changeFps(f: Int) {
        if (recording) return
        if (!controls.aeAuto && settings.shutterAngle) {
            val angle = controls.exposureNs.toDouble() * fps * 360.0 / 1e9
            controls = controls.copy(exposureNs = (angle / 360.0 * 1e9 / f).toLong())
        }
        settings.fps = f
        settings.save()
        reconfigure()
    }

    // ================================================================ ações

    private fun baseControls() = CamControls(
        stabilization = settings.stabilization,
        sharpening = settings.sharpening,
        noiseReduction = settings.noiseReduction,
        antibanding = settings.antibanding
    )

    private fun reconfigure() {
        engine?.reconfigure(settings.front, settings.height, settings.fps, controls)
    }

    private fun pushControls() {
        engine?.updateControls(controls)
        refreshChips()
    }

    private fun pushMonitor() {
        val zebra = if (settings.zebra) (settings.zebraLevel / 100f).coerceAtMost(0.985f) else -1f
        engine?.monitor = MonitorOpts(
            lut = settings.lut,
            zebra = zebra,
            peaking = settings.peaking || focusAssist,
            falseColor = settings.falseColor,
            magnify = if (focusAssist) 3f else 1f
        )
        engine?.profile = settings.profile
        overlay.grid = settings.grid
        val fl = FRAME_LINES.getOrElse(settings.frameLine) { FRAME_LINES[0] }
        overlay.frameLabel = fl.second
        overlay.frameAspect = fl.first
        overlay.assist = focusAssist
    }

    private fun onToggle(key: String) {
        when (key) {
            "lut" -> settings.lut = !settings.lut
            "zebra" -> settings.zebra = !settings.zebra
            "peak" -> settings.peaking = !settings.peaking
            "false" -> settings.falseColor = !settings.falseColor
            "grid" -> settings.grid = !settings.grid
            "torch" -> {
                if (info?.hasFlash != true) { overlay.flash("SEM LANTERNA NESSA CÂMERA"); return }
                controls = controls.copy(torch = !controls.torch)
                pushControls()
            }
        }
        settings.save()
        pushMonitor()
        refreshToggles()
    }

    private fun flipCamera() {
        if (recording) { overlay.flash("PARE A GRAVAÇÃO PARA TROCAR DE CÂMERA"); return }
        val target = !settings.front
        if (cameras.none { it.front == target }) { overlay.flash("SÓ HÁ UMA CÂMERA"); return }
        settings.front = target
        settings.save()
        controls = baseControls()
        lastIso = 0
        lastExp = 0L
        closeDial()
        overlay.hideFocus()
        flipBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        reconfigure()
    }

    private fun clipName() = String.format(Locale.US, "A001C%03d", settings.clip)

    private fun toggleRecord() {
        val e = engine ?: return
        recordButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (recording) {
            e.stopRecording()
            return
        }
        val stamp = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
        val name = clipName() + "_" + stamp
        e.startRecording(
            RecordConfig(name, settings.profile, settings.bitrateMbps * 1_000_000, settings.audio && granted(Manifest.permission.RECORD_AUDIO))
        )
    }

    private fun setRecordingUi(on: Boolean) {
        recording = on
        recordButton.recording = on
        overlay.recording = on
        recLabel.text = if (on) "REC" else "STBY"
        recLabel.setTextColor(if (on) Ui.RED else Ui.DIM)
        recDot.setTextColor(if (on) Ui.RED else Ui.DIM)
        flipBtn.alpha = if (on) 0.35f else 1f
        chips[Param.FPS]?.disabled = on
        if (!on) timecode.text = "00:00:00:00"
        refreshClipLabel()
    }

    private fun openLastClip() {
        val s = settings.lastClip
        if (s == null) { overlay.flash("NENHUM CLIPE AINDA"); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(s), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Throwable) {
            overlay.flash("NENHUM APP PARA ABRIR O VÍDEO")
        }
    }

    private fun loadThumb() {
        val s = settings.lastClip ?: return
        val uri = Uri.parse(s)
        Thread {
            val bmp = try { contentResolver.loadThumbnail(uri, Size(192, 192), null) } catch (_: Throwable) { null }
            runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) else thumb.setImageDrawable(null) }
        }.start()
    }

    // ================================================================ menu

    private fun openSettings() {
        closeDial()
        buildSettings()
        settingsPanel.visibility = View.VISIBLE
    }

    private fun closeSettings() {
        settingsPanel.visibility = View.GONE
    }

    private fun buildSettings() {
        settingsPanel.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("MENU", 18f, Ui.CREAM, Ui.COND, 0.3f))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(text("✕", 20f, Ui.CREAM, Ui.COND, 0f).apply {
            setPadding(dpi(14), dpi(6), dpi(4), dpi(6))
            setOnClickListener { closeSettings() }
        })
        settingsPanel.addView(header, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dpi(24), dpi(12), dpi(24), 0) })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24), 0, dpi(24), dpi(24))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(column)
        settingsPanel.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dpi(52) })

        section(column, "GRAVAÇÃO")
        row(column, "Resolução", listOf("4K UHD", "HD 1080"), if (settings.height >= 2160) 0 else 1, true) { i ->
            settings.height = if (i == 0) 2160 else 1080
            reconfigure()
        }
        row(column, "Perfil de cor", Profile.entries.map { it.label }, settings.profile.ordinal, true) { i ->
            settings.profile = Profile.entries[i]
        }
        row(column, "Bitrate (Mb/s)", BITRATES.map { "$it" }, BITRATES.indexOf(settings.bitrateMbps).coerceAtLeast(0), true) { i ->
            settings.bitrateMbps = BITRATES[i]
        }
        row(column, "Áudio", listOf("LIGADO", "DESLIGADO"), if (settings.audio) 0 else 1, true) { i ->
            settings.audio = i == 0
        }

        section(column, "IMAGEM")
        row(column, "Estabilização", listOf("LIGADA", "DESLIGADA"), if (settings.stabilization) 0 else 1, false) { i ->
            settings.stabilization = i == 0
            controls = controls.copy(stabilization = settings.stabilization)
            pushControls()
        }
        row(column, "Nitidez", listOf("DESLIGADA", "LIGADA"), if (settings.sharpening) 1 else 0, false) { i ->
            settings.sharpening = i == 1
            controls = controls.copy(sharpening = settings.sharpening)
            pushControls()
        }
        row(column, "Redução de ruído", listOf("DESLIGADA", "BAIXA", "ALTA"), settings.noiseReduction, false) { i ->
            settings.noiseReduction = i
            controls = controls.copy(noiseReduction = i)
            pushControls()
        }
        row(column, "Anti-flicker", listOf("OFF", "50 HZ", "60 HZ", "AUTO"), settings.antibanding, false) { i ->
            settings.antibanding = i
            controls = controls.copy(antibanding = i)
            pushControls()
        }

        section(column, "MONITOR")
        row(column, "LUT de visualização", listOf("REC.709", "DESLIGADA"), if (settings.lut) 0 else 1, false) { i ->
            settings.lut = i == 0
        }
        val zebraIdx = if (!settings.zebra) 0 else ZEBRA_LEVELS.indexOf(settings.zebraLevel).coerceAtLeast(0) + 1
        row(column, "Zebra", listOf("OFF") + ZEBRA_LEVELS.map { "$it%" }, zebraIdx, false) { i ->
            settings.zebra = i > 0
            if (i > 0) settings.zebraLevel = ZEBRA_LEVELS[i - 1]
        }
        row(column, "Focus peaking", listOf("DESLIGADO", "LIGADO"), if (settings.peaking) 1 else 0, false) { i ->
            settings.peaking = i == 1
        }
        row(column, "False color", listOf("DESLIGADO", "LIGADO"), if (settings.falseColor) 1 else 0, false) { i ->
            settings.falseColor = i == 1
        }
        row(column, "Grade", listOf("OFF", "TERÇOS"), if (settings.grid) 1 else 0, false) { i ->
            settings.grid = i == 1
        }
        row(column, "Linhas de corte", FRAME_LINES.map { it.second }, settings.frameLine, false) { i ->
            settings.frameLine = i
        }
        row(column, "Obturador em", listOf("ÂNGULO", "VELOCIDADE"), if (settings.shutterAngle) 0 else 1, false) { i ->
            settings.shutterAngle = i == 0
        }

        section(column, "CLIPES")
        column.addView(
            text(
                "Os clipes vão para Movies/AppleLogCam com nome de rolo e clipe (A001C001…). " +
                    "No Apple Log o arquivo sai marcado como BT.2020 HLG: no editor, defina o espaço de cor de entrada " +
                    "como Apple Log (Rec.2020). O HLG10 do sensor alimenta a curva, então a faixa dinâmica é a do Pixel.",
                12f, Ui.DIM, Ui.COND_REG, 0.02f
            ).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(8) }
        )
        val reset = text("ZERAR CONTADOR (PRÓXIMO: ${clipName()})", 11f, Ui.CREAM, Ui.COND, 0.14f).apply {
            background = outline(6f)
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
        }
        reset.setOnClickListener {
            settings.clip = 1
            settings.save()
            reset.text = "ZERAR CONTADOR (PRÓXIMO: ${clipName()})"
            refreshClipLabel()
        }
        column.addView(reset, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(12) })
    }

    private fun section(column: LinearLayout, title: String) {
        column.addView(text(title, 11f, Ui.AMBER, Ui.COND, 0.3f), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dpi(22)
            bottomMargin = dpi(6)
        })
        column.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, LinearLayout.LayoutParams(MATCH, dpi(1)))
    }

    private fun row(
        column: LinearLayout,
        label: String,
        options: List<String>,
        selected: Int,
        needsStandby: Boolean,
        onSelect: (Int) -> Unit
    ) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(9), 0, dpi(9))
        }
        r.addView(text(label, 14f, Ui.CREAM, Ui.COND_REG, 0.02f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun style(sel: Int) {
            for ((i, v) in views.withIndex()) {
                val on = i == sel
                v.setTextColor(if (on) Ui.AMBER else Ui.DIM)
                v.background = if (on) outline(6f, Ui.AMBER) else null
            }
        }
        for ((i, o) in options.withIndex()) {
            val v = text(o, 11f, Ui.DIM, Ui.MONO_BOLD, 0.04f).apply {
                gravity = Gravity.CENTER
                setPadding(dpi(10), dpi(7), dpi(10), dpi(7))
            }
            v.setOnClickListener {
                if (needsStandby && recording) {
                    overlay.flash("PARE A GRAVAÇÃO PRIMEIRO")
                    return@setOnClickListener
                }
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                style(i)
                onSelect(i)
                settings.save()
                pushMonitor()
                refreshAll()
            }
            views += v
            seg.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dpi(4) })
        }
        style(selected)
        r.addView(seg)
        column.addView(r)
    }

    // ================================================================ atualização da tela

    private fun refreshAll() {
        refreshChips()
        refreshToggles()
        refreshStatus()
        refreshClipLabel()
    }

    private fun refreshChips() {
        val inf = info
        chips[Param.FPS]?.apply {
            value = "$fps"
            badge = null
            disabled = recording
        }
        val exp = if (controls.aeAuto) lastExp else controls.exposureNs
        chips[Param.SHUTTER]?.apply {
            value = shutterLabel(exp)
            badge = if (controls.aeAuto) "AUTO" else null
            auto = controls.aeAuto
            disabled = inf != null && !inf.manualSensor && !controls.aeAuto
        }
        val iso = if (controls.aeAuto) lastIso else controls.iso
        chips[Param.ISO]?.apply {
            value = if (iso > 0) "$iso" else "—"
            badge = if (controls.aeAuto) "AUTO" else null
            auto = controls.aeAuto
        }
        chips[Param.EV]?.apply {
            value = if (controls.aeAuto) evLabel(controls.evSteps) else "—"
            badge = if (controls.aeLock) "LOCK" else null
            disabled = !controls.aeAuto
        }
        chips[Param.WB]?.apply {
            value = WB_OPTIONS.firstOrNull { it.mode == controls.awbMode }?.label ?: "AUTO"
            badge = if (controls.awbLock) "LOCK" else null
            auto = controls.awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        chips[Param.FOCUS]?.apply {
            if (inf != null && inf.minFocusDiopters <= 0f) {
                value = "FIXO"; badge = null; disabled = true
            } else {
                disabled = false
                when (controls.focusMode) {
                    CamControls.FOCUS_MANUAL -> { value = focusLabel(controls.focusDiopters); badge = "MF"; auto = false }
                    CamControls.FOCUS_POINT -> { value = "AF-S"; badge = null; auto = true }
                    else -> { value = "AF-C"; badge = null; auto = true }
                }
            }
        }
        for ((p, c) in chips) c.active = p == openParam
    }

    private fun refreshToggles() {
        fun set(key: String, on: Boolean, enabled: Boolean = true) {
            toggles[key]?.setTextColor(
                when {
                    !enabled -> Ui.FAINT
                    on -> Ui.AMBER
                    else -> Ui.DIM
                }
            )
        }
        set("lut", settings.lut)
        set("zebra", settings.zebra)
        set("peak", settings.peaking)
        set("false", settings.falseColor)
        set("grid", settings.grid)
        set("torch", controls.torch, info?.hasFlash == true)
    }

    private fun refreshStatus() {
        val inf = info
        val res = if (size.height >= 2160) "4K UHD" else "HD ${size.height}"
        statusLine1.text = "$res · ${fps}P · ${settings.profile.label} · HEVC 10-BIT · ${settings.bitrateMbps} Mb/s"
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Throwable) { 0L }
        val bps = settings.bitrateMbps * 1_000_000L + 192_000L
        val minutes = free * 8L / bps / 60L
        val bat = try {
            getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) { -1 }
        val cam = when {
            inf == null -> "—"
            inf.front -> "FRONTAL"
            else -> "TRASEIRA"
        }
        val src = if (inf?.hlg10 == true) "HLG10" else "SDR"
        val card = if (minutes >= 60) "${minutes / 60}H${String.format(Locale.US, "%02d", minutes % 60)}" else "$minutes MIN"
        statusLine2.text = "$cam · $src · CARD $card · BAT $bat%"
    }

    private fun refreshClipLabel() {
        clipLabel.text = if (recording) "${clipName()} · GRAVANDO" else "PRÓXIMO ${clipName()}"
    }

    private fun formatTc(ns: Long): String {
        val f = maxOf(1, fps)
        val frames = ns * f / 1_000_000_000L
        val ff = frames % f
        val total = frames / f
        return String.format(Locale.US, "%02d:%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60, ff)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val e = engine
            if (e != null && recording) {
                timecode.text = formatTc(e.elapsedNs)
                val lv = e.audioLevels()
                meter.set(lv[0], lv[1])
                val blink = (System.currentTimeMillis() / 500) % 2 == 0L
                recDot.setTextColor(if (blink) Ui.RED else 0x40E5322D)
            } else {
                meter.set(0f, 0f)
            }
            ui.postDelayed(this, 50)
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 3000)
        }
    }

    // ================================================================ CaptureEngine.Listener

    override fun onCameraReady(info: CamInfo, size: Size, fps: Int, cameras: List<CamInfo>) {
        this.info = info
        this.cameras = cameras
        this.size = size
        this.fps = fps
        if (settings.fps != fps) {
            settings.fps = fps
            settings.save()
        }
        if (!info.manualSensor && !controls.aeAuto) controls = controls.copy(aeAuto = true)
        val z = controls.zoom.coerceIn(info.zoomRange.lower, info.zoomRange.upper)
        if (z != controls.zoom) {
            controls = controls.copy(zoom = z)
            engine?.updateControls(controls)
        }
        lensBar.stops = info.lensStops
        lensBar.zoom = controls.zoom
        flipBtn.visibility = if (cameras.any { it.front } && cameras.any { !it.front }) View.VISIBLE else View.INVISIBLE
        chromeKey = ""
        layoutChrome()
        refreshAll()
    }

    override fun onConfigFailed(message: String) {
        when {
            settings.fps > 30 -> {
                settings.fps = 30
                overlay.flash("${fps}P RECUSADO NESSA RESOLUÇÃO · VOLTANDO PARA 30P")
            }
            settings.height > 1080 -> {
                settings.height = 1080
                overlay.flash("4K RECUSADO · USANDO 1080P")
            }
            else -> {
                onError(message)
                return
            }
        }
        settings.save()
        reconfigure()
    }

    override fun onStats(iso: Int, exposureNs: Long, focusDiopters: Float, afState: Int) {
        lastIso = iso
        lastExp = exposureNs
        lastFocus = focusDiopters
        refreshChips()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setRecordingUi(false)
    }

    override fun onRecording(recording: Boolean) {
        setRecordingUi(recording)
        if (recording) closeSettings()
    }

    override fun onSaved(uri: Uri?, name: String) {
        if (uri == null) {
            overlay.flash("NADA GRAVADO")
            return
        }
        settings.lastClip = uri.toString()
        settings.clip += 1
        settings.save()
        loadThumb()
        refreshClipLabel()
        overlay.flash("SALVO · $name")
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
