package com.farzanshibu.meowclaw.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.sin
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.farzanshibu.meowclaw.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Agent presence on screen, drawn in an accessibility overlay window (no
 * "display over other apps" permission needed). The window never takes touch
 * or focus, so dispatched gestures and the user's own taps pass through.
 *
 * - Edge glow: a slowly rotating gradient around the screen edge while the agent works.
 * - Cursor: a pointer that glides to each target before the agent acts on it.
 * - Controls: a floating MIC button (always, while the service runs) that gains
 *   a STOP button during a task. It is the one touchable part, so the user can
 *   talk to, steer or stop the agent from inside any app.
 */
class AgentOverlay(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: OverlayView? = null
    private var controls: ControlPill? = null
    private var activeSessions = 0

    init {
        main.post {
            val pill = ControlPill(service, windowManager)
            if (pill.attach()) controls = pill
        }
    }

    /** Shows the glow, cursor and controls; balanced by [end]. Safe from any thread. */
    fun begin() = main.post {
        activeSessions++
        ensureAttached()
        view?.setGlowVisible(true)
    }

    fun end() = main.post {
        activeSessions = max(0, activeSessions - 1)
        if (activeSessions == 0) {
            view?.setGlowVisible(false)
            main.postDelayed({ if (activeSessions == 0) detach() }, 450)
        }
    }

    fun setLabel(text: String?) = main.post { view?.label = text }

    /** Animates the cursor to ([x], [y]) and suspends until it arrives. */
    suspend fun moveCursor(x: Float, y: Float): Unit = onMain { done ->
        controls?.avoid(x, y)
        val v = view ?: return@onMain done()
        v.moveTo(x, y, done)
    }

    /** Moves along a straight path over [durationMs], in step with a drag gesture. */
    suspend fun dragCursor(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Unit = onMain { done ->
        controls?.avoid(x1, y1, x2, y2)
        val v = view ?: return@onMain done()
        v.dragTo(x1, y1, x2, y2, durationMs, done)
    }

    fun click(x: Float, y: Float, long: Boolean = false) = main.post { view?.ripple(x, y, long) }

    /** Hides the overlay for screenshots so models never see the cursor or glow. */
    suspend fun setHidden(hidden: Boolean): Unit = onMain { done ->
        controls?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        val v = view ?: return@onMain done()
        v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        // Let a frame render before the caller captures the screen.
        v.postOnAnimation { v.postOnAnimation { done() } }
    }

    fun dispose() = main.post {
        activeSessions = 0
        detach()
        controls?.detach()
        controls = null
    }

    private suspend fun onMain(block: (done: () -> Unit) -> Unit) {
        val finished = CompletableDeferred<Unit>()
        main.post { block { finished.complete(Unit) } }
        withTimeoutOrNull(3_000) { finished.await() }
    }

    @SuppressLint("RtlHardcoded")
    private fun ensureAttached() {
        if (view != null) return
        val overlayView = OverlayView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "MeowClaw overlay"
        }
        runCatching { windowManager.addView(overlayView, params) }.onSuccess { view = overlayView }
    }

    private fun detach() {
        view?.let { v ->
            v.stopAnimations()
            runCatching { windowManager.removeView(v) }
        }
        view = null
    }
}

/**
 * MIC (and, during a task, STOP) buttons in their own small touchable window on a screen edge.
 * Injected gestures land on whatever window is on top, so the pill hops to
 * the opposite edge whenever the agent is about to touch near it.
 */
@SuppressLint("RtlHardcoded", "SetTextI18n")
private class ControlPill(context: Context, private val windowManager: WindowManager) : LinearLayout(context) {
    private val graph = context.graph
    private val density = resources.displayMetrics.density
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // A fixed width: wrap_content windows can be re-measured to a sliver when STOP appears.
    private val params = WindowManager.LayoutParams(
        (84 * density).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.RIGHT
        x = (8 * density).toInt()
        y = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        title = "MeowClaw controls"
    }
    private val mic: TextView

    init {
        orientation = VERTICAL
        val pad = (6 * density).toInt()
        setPadding(pad, pad, pad, pad)
        background = box(Color.parseColor("#FFFDF5"), 14f)
        elevation = 6 * density
        val stop = button("STOP", Color.parseColor("#FF5A5F")) { graph.controller.cancel() }
        val gap = Space(context)
        addView(stop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(gap, LayoutParams(1, pad))
        scope.launch {
            graph.controller.ui.map { it.busy }.distinctUntilChanged().collect { busy ->
                stop.visibility = if (busy) View.VISIBLE else View.GONE
                gap.visibility = stop.visibility
            }
        }
        mic = button("MIC", Color.WHITE) {
            // Starting the mic needs the permission, granted from the app.
            if (graph.handsFree.hasPermission()) graph.handsFree.toggle() else graph.toaster.show("Allow the microphone in MeowClaw first")
        }
        addView(mic, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scope.launch {
            graph.handsFree.active.collect { on ->
                mic.text = if (on) "MIC ON" else "MIC"
                mic.background = box(if (on) Color.parseColor("#7CFFB2") else Color.WHITE, 10f)
            }
        }
    }

    fun attach(): Boolean = runCatching { windowManager.addView(this, params) }.isSuccess

    fun detach() {
        scope.cancel()
        runCatching { windowManager.removeView(this) }
    }

    /** Moves to the other edge if any point is within reach of the pill. */
    fun avoid(vararg xy: Float) {
        if (!isAttachedToWindow || width == 0) return
        val loc = IntArray(2).also(::getLocationOnScreen)
        val margin = 48 * density
        val bounds = RectF(loc[0] - margin, loc[1] - margin, loc[0] + width + margin, loc[1] + height + margin)
        val hit = xy.toList().chunked(2).any { (x, y) -> bounds.contains(x, y) }
        if (!hit) return
        params.gravity = Gravity.TOP or (if ((params.gravity and Gravity.RIGHT) == Gravity.RIGHT) Gravity.LEFT else Gravity.RIGHT)
        runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun button(label: String, fill: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textSize = 12f
        setTextColor(Color.parseColor("#111111"))
        gravity = Gravity.CENTER
        val h = (10 * density).toInt()
        setPadding(h, h, h, h)
        background = box(fill, 10f)
        setOnClickListener { onClick() }
    }

    private fun box(fill: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * density
        setStroke((2.5f * density).toInt(), Color.parseColor("#111111"))
    }
}

private class OverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Iridescent hues, like light through glass. First and last match so sweeps loop seamlessly. */
    private val hues = intArrayOf(
        Color.parseColor("#4F8CFF"), Color.parseColor("#9B6BFF"), Color.parseColor("#FF5FA8"),
        Color.parseColor("#FFB35C"), Color.parseColor("#3DDCFF"), Color.parseColor("#4F8CFF"),
    )

    // ─── Edge glow ───────────────────────────────────────────────
    // The blurred edge shape is rendered once into an alpha mask; each frame
    // only tints it with two counter-rotating sweeps, which keeps it cheap.
    private var glowMask: Bitmap? = null
    private var sweepA: SweepGradient? = null
    private var sweepB: SweepGradient? = null
    private val sweepMatrix = Matrix()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaintB = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var glowAlpha = 0f
    private var phase = 0f

    // ─── Cursor ──────────────────────────────────────────────────
    private var cursor = PointF(-1f, -1f)
    private var cursorVisible = false
    private var moving = false
    private var pressScale = 1f
    private val pointer = Path().apply {
        // Tail-less pointer (tip at 0,0); corners rounded by the paints' CornerPathEffect.
        moveTo(0f, 0f)
        lineTo(dp(1.4f), dp(21f))
        lineTo(dp(6.8f), dp(15.6f))
        lineTo(dp(14.8f), dp(14.6f))
        close()
    }
    private val rounded = CornerPathEffect(dp(2.6f))
    private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        pathEffect = rounded
        shader = LinearGradient(0f, 0f, dp(14f), dp(20f), intArrayOf(hues[0], hues[1], hues[2]), null, Shader.TileMode.CLAMP)
    }
    private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        pathEffect = rounded
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val pointerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        pathEffect = rounded
        color = Color.argb(90, 0, 0, 0)
        maskFilter = BlurMaskFilter(dp(4f), BlurMaskFilter.Blur.NORMAL)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class TrailPoint(val x: Float, val y: Float, val time: Long)
    private val trail = ArrayDeque<TrailPoint>()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hues[1] }

    private data class Ripple(val x: Float, val y: Float, val long: Boolean, var progress: Float = 0f)
    private val ripples = mutableListOf<Ripple>()
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rippleFill = Paint(Paint.ANTI_ALIAS_FLAG)

    // ─── Label ───────────────────────────────────────────────────
    var label: String? = null
        set(value) {
            field = value
            invalidate()
        }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(222, 16, 16, 22) }
    private val labelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(40, 255, 255, 255)
    }
    private val labelDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBox = RectF()

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 8_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    private var fade: ValueAnimator? = null
    private var move: ValueAnimator? = null
    private var press: ValueAnimator? = null

    fun setGlowVisible(visible: Boolean) {
        if (visible && !loop.isStarted) loop.start()
        fade?.cancel()
        fade = ValueAnimator.ofFloat(glowAlpha, if (visible) 1f else 0f).apply {
            duration = if (visible) 600 else 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { if (!visible) loop.cancel() }
            start()
        }
        if (!visible) {
            cursorVisible = false
            trail.clear()
        }
    }

    fun moveTo(x: Float, y: Float, done: () -> Unit) {
        move?.cancel()
        if (!cursorVisible || cursor.x < 0) {
            // First appearance: start from the screen centre so the motion reads.
            cursor.set(width / 2f, height / 2f)
            cursorVisible = true
        }
        val from = PointF(cursor.x, cursor.y)
        val distance = hypot(x - from.x, y - from.y)
        move = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (220 + distance / density * 0.9f).toLong().coerceIn(220, 700)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                step(from.x + (x - from.x) * t, from.y + (y - from.y) * t)
            }
            doOnEnd { moving = false; done() }
            moving = true
            start()
        }
    }

    fun dragTo(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, done: () -> Unit) {
        move?.cancel()
        cursorVisible = true
        cursor.set(x1, y1)
        move = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                step(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
            }
            doOnEnd { moving = false; done() }
            moving = true
            start()
        }
    }

    private fun step(x: Float, y: Float) {
        cursor.set(x, y)
        trail.addLast(TrailPoint(x, y, SystemClock.uptimeMillis()))
        while (trail.size > 24) trail.removeFirst()
        invalidate()
    }

    fun ripple(x: Float, y: Float, long: Boolean) {
        val ripple = Ripple(x, y, long)
        ripples += ripple
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (long) 950 else 520
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                ripple.progress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { ripples.remove(ripple); invalidate() }
            start()
        }
        // The pointer dips like a finger pressing.
        press?.cancel()
        press = ValueAnimator.ofFloat(1f, 0.8f, 1f).apply {
            duration = if (long) 700 else 240
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimations() {
        loop.cancel()
        fade?.cancel()
        move?.cancel()
        press?.cancel()
        glowMask?.recycle()
        glowMask = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        sweepA = SweepGradient(w / 2f, h / 2f, hues, null)
        sweepB = SweepGradient(w / 2f, h / 2f, hues.reversedArray(), null)
        glowPaint.shader = sweepA
        glowPaintB.shader = sweepB
        buildGlowMask(w, h)
    }

    /** Light spilling in from the screen edge: a wide soft bloom, a mid haze and a thin bright rim. */
    private fun buildGlowMask(w: Int, h: Int) {
        glowMask?.recycle()
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val radius = cornerRadius()
        val edge = RectF(0f, 0f, w.toFloat(), h.toFloat())
        listOf(Triple(40f, 26f, 0.32f), Triple(14f, 8f, 0.55f), Triple(4.5f, 1.2f, 1f)).forEach { (stroke, blur, alpha) ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                // Centred on the edge, so only the inner half is on screen.
                strokeWidth = dp(stroke)
                this.alpha = (alpha * 255).toInt()
                maskFilter = BlurMaskFilter(dp(blur), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(edge, radius, radius, paint)
        }
        glowMask = mask
    }

    override fun onDraw(canvas: Canvas) {
        drawGlow(canvas)
        drawRipples(canvas)
        if (cursorVisible && glowAlpha > 0f) {
            drawTrail(canvas)
            drawCursor(canvas)
        }
    }

    private fun drawGlow(canvas: Canvas) {
        val mask = glowMask ?: return
        if (glowAlpha <= 0f) return
        val angle = phase * 360f
        // Gentle breathing so the light feels alive rather than spinning.
        val breath = 0.8f + 0.2f * sin(phase * 2 * PI.toFloat() * 3)
        sweepMatrix.setRotate(angle, width / 2f, height / 2f)
        sweepA?.setLocalMatrix(sweepMatrix)
        glowPaint.alpha = (255 * glowAlpha * breath).toInt()
        canvas.drawBitmap(mask, 0f, 0f, glowPaint)
        sweepMatrix.setRotate(-angle * 1.6f + 90f, width / 2f, height / 2f)
        sweepB?.setLocalMatrix(sweepMatrix)
        glowPaintB.alpha = (90 * glowAlpha * (1.8f - breath)).toInt().coerceIn(0, 255)
        canvas.drawBitmap(mask, 0f, 0f, glowPaintB)
    }

    private fun drawRipples(canvas: Canvas) {
        ripples.toList().forEach { r ->
            val t = r.progress
            val color = if (r.long) hues[2] else hues[1]
            val max = dp(if (r.long) 44f else 34f)
            // Soft filled pulse, then an expanding ring.
            rippleFill.color = color
            rippleFill.alpha = ((1f - t) * 70).toInt()
            canvas.drawCircle(r.x, r.y, dp(10f) + max * 0.4f * t, rippleFill)
            ripplePaint.color = color
            ripplePaint.strokeWidth = dp(3f) * (1f - t) + dp(0.5f)
            ripplePaint.alpha = ((1f - t) * 230).toInt()
            canvas.drawCircle(r.x, r.y, dp(8f) + max * t, ripplePaint)
        }
    }

    private fun drawTrail(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        while (trail.isNotEmpty() && now - trail.first().time > TRAIL_MS) trail.removeFirst()
        if (trail.isEmpty()) return
        trail.forEach { p ->
            val life = 1f - (now - p.time).toFloat() / TRAIL_MS
            trailPaint.alpha = (life * life * 120).toInt()
            canvas.drawCircle(p.x, p.y, dp(1.5f) + dp(3.5f) * life, trailPaint)
        }
        // Keep drawing until the trail has faded out.
        if (!moving) postInvalidateOnAnimation()
    }

    private fun drawCursor(canvas: Canvas) {
        // Soft halo under the tip; brighter while moving.
        val haloRadius = dp(if (moving) 26f else 20f) * (0.92f + 0.08f * sin(phase * 2 * PI.toFloat() * 6))
        halo.shader = RadialGradient(
            cursor.x, cursor.y, haloRadius,
            intArrayOf(Color.argb((100 * glowAlpha).toInt(), 155, 107, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cursor.x, cursor.y, haloRadius, halo)

        canvas.save()
        canvas.translate(cursor.x, cursor.y)
        canvas.scale(pressScale, pressScale)
        canvas.save()
        canvas.translate(dp(1f), dp(2.5f))
        canvas.drawPath(pointer, pointerShadow)
        canvas.restore()
        canvas.drawPath(pointer, pointerFill)
        canvas.drawPath(pointer, pointerStroke)
        canvas.restore()

        val text = label ?: return
        val padH = dp(10f)
        val dot = dp(6f)
        val textWidth = labelText.measureText(text)
        val boxWidth = padH + dot + dp(7f) + textWidth + padH
        val boxHeight = dp(26f)
        val left = min(cursor.x + dp(20f), width - boxWidth - dp(6f))
        val top = min(cursor.y + dp(22f), height - boxHeight - dp(6f))
        labelBox.set(left, top, left + boxWidth, top + boxHeight)
        val r = boxHeight / 2f
        canvas.drawRoundRect(labelBox, r, r, labelBg)
        canvas.drawRoundRect(labelBox, r, r, labelBorder)
        val cy = labelBox.centerY()
        labelDot.shader = LinearGradient(left + padH, cy - dot, left + padH + dot, cy + dot, hues[0], hues[2], Shader.TileMode.CLAMP)
        canvas.drawCircle(left + padH + dot / 2f, cy, dot / 2f, labelDot)
        canvas.drawText(text, left + padH + dot + dp(7f), cy - (labelText.ascent() + labelText.descent()) / 2f, labelText)
    }

    private fun cornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { return it.radius.toFloat() }
        }
        return dp(28f)
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var fired = false
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!fired) { fired = true; action() }
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (!fired) { fired = true; action() }
            }
        })
    }

    companion object {
        private const val TRAIL_MS = 260L
    }
}
