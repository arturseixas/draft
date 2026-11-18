package app.applelogcam

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Paleta e tipografia: preto quente, creme, âmbar de câmera de cinema e vermelho de REC. */
object Ui {
    val BG = 0xFF0A0A0B.toInt()
    val PANEL = 0xEE0E0E10.toInt()
    val CREAM = 0xFFEDE6D6.toInt()
    val CREAM_DIM = 0xB3EDE6D6.toInt()
    val DIM = 0xFF8C867A.toInt()
    val FAINT = 0x4DEDE6D6
    val LINE = 0x29EDE6D6
    val AMBER = 0xFFFFB000.toInt()
    val RED = 0xFFE5322D.toInt()
    val GREEN = 0xFF5BD06E.toInt()
    val MONO: Typeface = Typeface.create("monospace", Typeface.NORMAL)
    val MONO_BOLD: Typeface = Typeface.create("monospace", Typeface.BOLD)
    val COND: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val COND_REG: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

fun fmtStop(s: Float): String = when {
    s < 0.995f -> "." + (s * 10f).roundToInt()
    abs(s - s.roundToInt()) < 0.05f -> s.roundToInt().toString()
    else -> String.format(Locale.US, "%.1f", s)
}

fun fmtZoom(z: Float): String = when {
    z < 0.995f -> "." + (z * 10f).roundToInt()
    abs(z - z.roundToInt()) < 0.05f -> z.roundToInt().toString()
    else -> String.format(Locale.US, "%.1f", z)
}

// ------------------------------------------------------------------------------------------------

/** Parâmetro da barra inferior: rótulo pequeno em cima, valor grande em mono embaixo. */
class ParamChip(context: Context, private val label: String) : View(context) {
    var value: String = "—"
        set(v) { if (field != v) { field = v; invalidate() } }
    var badge: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var auto = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var disabled = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.COND; textSize = context.sp(10f); letterSpacing = 0.16f
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.COND; textSize = context.sp(8.5f); letterSpacing = 0.1f; color = Ui.AMBER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.MONO_BOLD; textSize = context.sp(17f); textAlign = Paint.Align.CENTER
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.AMBER }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dpi(84), widthMeasureSpec),
            resolveSize(context.dpi(56), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val gap = context.dp(4f)
        labelPaint.color = if (active) Ui.AMBER else Ui.DIM
        val lw = labelPaint.measureText(label)
        val b = badge
        val bw = if (b != null) badgePaint.measureText(b) + gap else 0f
        val x = cx - (lw + bw) / 2f
        val ly = context.dp(18f)
        canvas.drawText(label, x, ly, labelPaint)
        if (b != null) canvas.drawText(b, x + lw + gap, ly, badgePaint)
        valuePaint.color = when {
            disabled -> Ui.FAINT
            auto -> Ui.CREAM_DIM
            else -> Ui.CREAM
        }
        canvas.drawText(value, cx, context.dp(41f), valuePaint)
        if (active) {
            canvas.drawRoundRect(
                cx - context.dp(16f), height - context.dp(5f), cx + context.dp(16f), height - context.dp(3f),
                2f, 2f, markPaint
            )
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.55f else 1f
    }
}

// ------------------------------------------------------------------------------------------------

/** Régua horizontal com marcas, arrastável, com inércia e encaixe em cada valor. */
class RulerDial(context: Context) : View(context) {
    /** Chamado a cada valor novo vindo do dedo. */
    var onChange: ((Int) -> Unit)? = null
    /** Chamado quando a régua para. */
    var onCommit: ((Int) -> Unit)? = null

    private var items: List<String> = emptyList()
    var index = 0
        private set
    private val spacing = context.dp(15f)
    private var offset = 0f
    private var labelEvery = 1
    private var animator: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var lastX = 0f
    private var downX = 0f
    private var moved = false

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CREAM; strokeWidth = context.dp(1f) }
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CREAM; strokeWidth = context.dp(1.6f) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.MONO; textSize = context.sp(10f); color = Ui.DIM; textAlign = Paint.Align.CENTER
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.MONO_BOLD; textSize = context.sp(20f); color = Ui.AMBER; textAlign = Paint.Align.CENTER
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.AMBER; strokeWidth = context.dp(2f) }
    private val tri = Path()

    fun setItems(list: List<String>, selected: Int) {
        animator?.cancel()
        items = list
        index = selected.coerceIn(0, max(0, list.size - 1))
        offset = index * spacing
        val widest = list.maxOfOrNull { small.measureText(it) } ?: 0f
        labelEvery = max(1, ceil((widest + context.dp(12f)) / spacing).toInt())
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dpi(240), widthMeasureSpec),
            resolveSize(context.dpi(84), heightMeasureSpec)
        )
    }

    private fun maxOffset() = max(0, items.size - 1) * spacing

    private fun syncIndex() {
        if (items.isEmpty()) return
        val i = (offset / spacing).roundToInt().coerceIn(0, items.size - 1)
        if (i != index) {
            index = i
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(i)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                lastX = e.x
                downX = e.x
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (abs(e.x - downX) > context.dp(4f)) moved = true
                offset = (offset - (e.x - lastX)).coerceIn(0f, maxOffset())
                lastX = e.x
                syncIndex()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                val target = if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    offset + (e.x - width / 2f)          // toque numa marca: vai até ela
                } else {
                    offset - vx * 0.16f                  // inércia
                }
                val snapped = (target / spacing).roundToInt().coerceIn(0, items.size - 1) * spacing
                settle(snapped)
            }
        }
        return true
    }

    private fun settle(to: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(offset, to).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                offset = it.animatedValue as Float
                syncIndex()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) { if (!canceled) onCommit?.invoke(index) }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val cx = width / 2f
        val base = height - context.dp(12f)
        canvas.drawText(items[index], cx, context.dp(24f), big)
        val half = width / 2f
        val first = max(0, floor((offset - half) / spacing).toInt() - 1)
        val last = min(items.size - 1, ceil((offset + half) / spacing).toInt() + 1)
        for (i in first..last) {
            val x = cx + i * spacing - offset
            val fade = (1f - abs(x - cx) / half).coerceIn(0f, 1f)
            val a = (fade * fade * 255).toInt()
            val isMajor = i % labelEvery == 0
            val p = if (isMajor) major else tick
            p.alpha = a
            val h = if (isMajor) context.dp(14f) else context.dp(8f)
            canvas.drawLine(x, base - h, x, base, p)
            if (isMajor && abs(x - cx) > spacing * 0.6f) {
                small.alpha = a
                canvas.drawText(items[i], x, base - context.dp(20f), small)
            }
        }
        canvas.drawLine(cx, base - context.dp(22f), cx, base + context.dp(4f), needle)
        tri.reset()
        tri.moveTo(cx - context.dp(5f), base - context.dp(29f))
        tri.lineTo(cx + context.dp(5f), base - context.dp(29f))
        tri.lineTo(cx, base - context.dp(22f))
        tri.close()
        canvas.drawPath(tri, needle)
    }
}

// ------------------------------------------------------------------------------------------------

/** Botão de gravar: anel + círculo vermelho que vira quadrado arredondado e pulsa durante a gravação. */
class RecordButton(context: Context) : View(context) {
    var recording = false
        set(v) {
            if (field == v) return
            field = v
            animateTo(if (v) 1f else 0f)
        }
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(3f); color = Ui.CREAM
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.RED }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Gravar"
    }

    private fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(t, target).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dpi(74)
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.dp(3f)
        if (recording) {
            val pulse = (sin(SystemClock.uptimeMillis() / 320.0) * 0.5 + 0.5).toFloat()
            ring.color = Ui.RED
            ring.alpha = (120 + 135 * pulse).toInt()
            postInvalidateOnAnimation()
        } else {
            ring.color = Ui.CREAM
            ring.alpha = 255
        }
        canvas.drawCircle(cx, cy, r, ring)
        val half = lerp(r - context.dp(7f), r * 0.40f, t)
        val corner = lerp(half, context.dp(6f), t)
        fill.alpha = if (isPressed) 170 else 255
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, fill)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }
}

// ------------------------------------------------------------------------------------------------

/** Camada por cima do quadro: grade, linhas de corte, retícula de foco, borda de REC e avisos. */
class OverlayView(context: Context) : View(context) {
    val content = RectF()
    var grid = false
        set(v) { field = v; invalidate() }
    var frameAspect = 0f
        set(v) { field = v; invalidate() }
    var frameLabel = ""
    var recording = false
        set(v) { field = v; invalidate() }
    var assist = false
        set(v) { field = v; invalidate() }

    private var fx = 0f
    private var fy = 0f
    private var focusAt = 0L
    private var focusLocked = false
    private var focusVisible = false
    private var message: String? = null
    private var messageAt = 0L

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = context.dp(1f) }
    private val mask = Paint().apply { color = 0x9E000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xB3EDE6D6.toInt(); strokeWidth = context.dp(1f)
    }
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.AMBER; strokeWidth = context.dp(1.6f)
    }
    private val rec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.RED; strokeWidth = context.dp(3f)
    }
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9000000.toInt() }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.COND; textSize = context.sp(13f); color = Ui.CREAM; letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.COND; textSize = context.sp(10f); color = Ui.AMBER; letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }
    private val frameText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.MONO; textSize = context.sp(9f); color = 0xB3EDE6D6.toInt()
    }

    fun showFocus(x: Float, y: Float, locked: Boolean) {
        fx = x; fy = y
        focusLocked = locked
        focusAt = SystemClock.uptimeMillis()
        focusVisible = true
        invalidate()
    }

    fun setLocked(locked: Boolean) {
        focusLocked = locked
        focusAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun clearFocus() {
        focusVisible = false
        focusLocked = false
        invalidate()
    }

    fun flash(msg: String) {
        message = msg
        messageAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (content.isEmpty) return
        val now = SystemClock.uptimeMillis()
        var animating = false

        if (frameAspect > 0f) {
            val a = content.width() / content.height()
            val r = RectF(content)
            if (frameAspect > a) {
                val h = content.width() / frameAspect
                r.top = content.centerY() - h / 2f
                r.bottom = r.top + h
            } else {
                val w = content.height() * frameAspect
                r.left = content.centerX() - w / 2f
                r.right = r.left + w
            }
            canvas.drawRect(content.left, content.top, content.right, r.top, mask)
            canvas.drawRect(content.left, r.bottom, content.right, content.bottom, mask)
            canvas.drawRect(content.left, r.top, r.left, r.bottom, mask)
            canvas.drawRect(r.right, r.top, content.right, r.bottom, mask)
            canvas.drawRect(r, frame)
            canvas.drawText(frameLabel, r.left + context.dp(6f), r.bottom - context.dp(6f), frameText)
        }

        if (grid) {
            for (i in 1..2) {
                val x = content.left + content.width() * i / 3f
                val y = content.top + content.height() * i / 3f
                canvas.drawLine(x, content.top, x, content.bottom, line)
                canvas.drawLine(content.left, y, content.right, y, line)
            }
        }

        // cruz central discreta
        val c = context.dp(7f)
        canvas.drawLine(content.centerX() - c, content.centerY(), content.centerX() + c, content.centerY(), line)
        canvas.drawLine(content.centerX(), content.centerY() - c, content.centerX(), content.centerY() + c, line)

        if (focusVisible) {
            val age = now - focusAt
            val alpha = if (focusLocked) 255 else (255 * (1f - ((age - 1300f) / 600f).coerceIn(0f, 1f))).toInt()
            if (alpha <= 0) {
                focusVisible = false
            } else {
                val grow = 1f + 0.3f * (1f - (age / 200f).coerceIn(0f, 1f))
                val s = context.dp(34f) * grow
                val k = context.dp(10f)
                focus.alpha = alpha
                // cantos
                canvas.drawLine(fx - s, fy - s, fx - s + k, fy - s, focus)
                canvas.drawLine(fx - s, fy - s, fx - s, fy - s + k, focus)
                canvas.drawLine(fx + s, fy - s, fx + s - k, fy - s, focus)
                canvas.drawLine(fx + s, fy - s, fx + s, fy - s + k, focus)
                canvas.drawLine(fx - s, fy + s, fx - s + k, fy + s, focus)
                canvas.drawLine(fx - s, fy + s, fx - s, fy + s - k, focus)
                canvas.drawLine(fx + s, fy + s, fx + s - k, fy + s, focus)
                canvas.drawLine(fx + s, fy + s, fx + s, fy + s - k, focus)
                canvas.drawCircle(fx, fy, context.dp(2f), focus)
                if (focusLocked) {
                    smallText.alpha = alpha
                    canvas.drawText("AE/AF LOCK", fx, fy + s + context.dp(16f), smallText)
                }
                if (!focusLocked || age < 220) animating = true
            }
        }

        if (assist) {
            canvas.drawText("FOCUS ASSIST  3×", content.centerX(), content.top + context.dp(76f), smallText.apply { alpha = 255 })
        }

        if (recording) {
            val i = context.dp(1.5f)
            canvas.drawRect(content.left + i, content.top + i, content.right - i, content.bottom - i, rec)
        }

        val msg = message
        if (msg != null) {
            val age = now - messageAt
            if (age > 1700) {
                message = null
            } else {
                val a = (1f - ((age - 1300f) / 400f).coerceIn(0f, 1f))
                pillBg.alpha = (217 * a).toInt()
                pillText.alpha = (255 * a).toInt()
                val w = pillText.measureText(msg) + context.dp(28f)
                val h = context.dp(32f)
                val cx = content.centerX()
                val cy = content.top + content.height() * 0.30f
                canvas.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, h / 2f, h / 2f, pillBg)
                canvas.drawText(msg, cx, cy + pillText.textSize * 0.36f, pillText)
                animating = true
            }
        }

        if (animating) postInvalidateOnAnimation()
    }
}

// ------------------------------------------------------------------------------------------------

/** Medidor de áudio vertical L/R em segmentos, com retenção de pico. */
class AudioMeter(context: Context) : View(context) {
    private val levels = FloatArray(2)
    private val peaks = FloatArray(2)
    private val peakAt = LongArray(2)
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.COND; textSize = context.sp(8f); color = Ui.DIM; textAlign = Paint.Align.CENTER
    }

    fun set(l: Float, r: Float) {
        val now = SystemClock.uptimeMillis()
        val input = floatArrayOf(l, r)
        for (i in 0..1) {
            val db = 20f * log10(max(input[i], 0.00001f))
            val n = ((db + 48f) / 48f).coerceIn(0f, 1f)
            levels[i] = max(n, levels[i] - 0.035f)
            if (n >= peaks[i] || now - peakAt[i] > 1200) {
                peaks[i] = n
                peakAt[i] = now
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(context.dpi(30), widthMeasureSpec), resolveSize(context.dpi(92), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val n = 20
        val bottom = height - context.dp(12f)
        val top = context.dp(2f)
        val segH = (bottom - top) / n
        val barW = context.dp(5f)
        val gap = context.dp(5f)
        val x0 = width / 2f - barW - gap / 2f
        for (ch in 0..1) {
            val x = x0 + ch * (barW + gap)
            val lit = (levels[ch] * n).toInt()
            val peakSeg = (peaks[ch] * n).toInt() - 1
            for (s in 0 until n) {
                val frac = s / n.toFloat()
                val color = when {
                    frac > 0.88f -> Ui.RED
                    frac > 0.72f -> Ui.AMBER
                    else -> Ui.GREEN
                }
                seg.color = color
                seg.alpha = if (s < lit || s == peakSeg) 255 else 38
                val yb = bottom - s * segH
                canvas.drawRect(x, yb - segH + context.dp(1f), x + barW, yb, seg)
            }
            canvas.drawText(if (ch == 0) "L" else "R", x + barW / 2f, height - context.dp(2f), label)
        }
    }
}

// ------------------------------------------------------------------------------------------------

/** Seletor de lentes em pílula: .5  1  2  5. A lente ativa mostra o zoom atual. */
class LensBar(context: Context) : View(context) {
    var stops: List<Float> = listOf(1f)
        set(v) { field = v; requestLayout(); invalidate() }
    var zoom = 1f
        set(v) { if (field != v) { field = v; invalidate() } }
    var onSelect: ((Float) -> Unit)? = null

    private val d = context.dp(38f)
    private val gap = context.dp(4f)
    private val pad = context.dp(4f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val activeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF21C1B19.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.MONO; textSize = context.sp(11f); color = Ui.CREAM; textAlign = Paint.Align.CENTER
    }
    private val activeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.MONO_BOLD; textSize = context.sp(12f); color = Ui.AMBER; textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = max(1, stops.size)
        val w = (n * d + (n - 1) * gap + 2 * pad).toInt()
        val h = (d + 2 * pad).toInt()
        setMeasuredDimension(w, h)
    }

    private fun activeIndex(): Int {
        var a = 0
        for (i in stops.indices) if (zoom >= stops[i] - 0.05f) a = i
        return a
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2f, h / 2f, bg)
        val active = activeIndex()
        for (i in stops.indices) {
            val cx = pad + d / 2f + i * (d + gap)
            val cy = h / 2f
            if (i == active) {
                canvas.drawCircle(cx, cy, d / 2f, activeBg)
                val t = fmtZoom(zoom) + "×"
                canvas.drawText(t, cx, cy - (activeText.ascent() + activeText.descent()) / 2f, activeText)
            } else {
                val t = fmtStop(stops[i])
                canvas.drawText(t, cx, cy - (text.ascent() + text.descent()) / 2f, text)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (stops.isEmpty()) return true
                val i = ((e.x - pad) / (d + gap)).toInt().coerceIn(0, stops.size - 1)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelect?.invoke(stops[i])
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
