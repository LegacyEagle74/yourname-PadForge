package com.padforge.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a layout and, depending on the mode, either plays it (multi-touch,
 * sending input) or edits it (drag to move, drag the corner handle to resize,
 * tap a selected control to open its settings).
 */
@SuppressLint("ViewConstructor")
class PadView(context: Context) : View(context) {

    var layout: Layout = Layout()
        set(v) { releaseAll(); field = v; selected = null; updateGestureExclusion(); invalidate() }

    var editMode = false
        set(v) { releaseAll(); field = v; if (!v) selected = null; invalidate() }

    var selected: Element? = null
        set(v) { field = v; onSelectionChanged?.invoke(v); invalidate() }

    /**
     * Overlay mode: this view is a small window showing part of the screen. Layout
     * coordinates are still fractions of the whole screen (screenW x screenH), and
     * (originX, originY) is where this view's top-left corner sits on that screen.
     */
    var screenW = 0f
    var screenH = 0f
    var originX = 0f
    var originY = 0f

    /** Overlay mode: no black background, so the app underneath shows through. */
    var transparent = false

    var onSelectionChanged: ((Element?) -> Unit)? = null
    var onEditElement: ((Element) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density

    // ---------------------------------------------------------------- runtime state
    private class Rt {
        var pointer = -1
        var pressed = false
        var toggled = false
        var ox = 0f; var oy = 0f      // joystick origin (px)
        var kx = 0f; var ky = 0f      // knob position, -1..1, screen orientation (+y down)
        var sx = 0f; var sy = 0f      // output after dead zone
        val dirOn = BooleanArray(4)   // up, down, left, right
        var edgeOn = false
        var lastX = 0f; var lastY = 0f
        var downTime = 0L
        var travel = 0f
    }

    private val rt = HashMap<String, Rt>()
    private fun rtOf(e: Element) = rt.getOrPut(e.id) { Rt() }
    private val owners = HashMap<Int, Element>()

    // ---------------------------------------------------------------- geometry
    private val sw get() = if (screenW > 0f) screenW else width.toFloat()
    private val sh get() = if (screenH > 0f) screenH else height.toFloat()
    private val shortSide get() = min(sw, sh).coerceAtLeast(1f)
    private fun cx(e: Element) = e.x * sw - originX
    private fun cy(e: Element) = e.y * sh - originY
    private fun halfW(e: Element) = e.w * shortSide / 2f
    private fun halfH(e: Element) = (if (e.isRound) e.w else e.h) * shortSide / 2f

    private fun hit(x: Float, y: Float): Element? {
        val els = layout.elements
        for (i in els.indices.reversed()) {
            val e = els[i]
            val slack = if (e.type == ElementType.JOYSTICK) 1.25f else 1.08f
            val dx = x - cx(e)
            val dy = y - cy(e)
            if (e.isRound) {
                val r = halfW(e) * slack
                if (dx * dx + dy * dy <= r * r) return e
            } else if (abs(dx) <= halfW(e) * slack && abs(dy) <= halfH(e) * slack) {
                return e
            }
        }
        return null
    }

    // ================================================================ touch
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) return editTouch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val pid = ev.getPointerId(i)
                val x = ev.getX(i); val y = ev.getY(i)
                val e = hit(x, y) ?: return true
                val r = rtOf(e)
                if (r.pointer >= 0) return true   // already held by another finger
                r.pointer = pid
                owners[pid] = e
                onDown(e, r, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val e = owners[ev.getPointerId(i)] ?: continue
                    onMove(e, rtOf(e), ev.getX(i), ev.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                val e = owners.remove(pid) ?: return true
                onUp(e, rtOf(e))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun buzz() {
        if (layout.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun onDown(e: Element, r: Rt, x: Float, y: Float) {
        when (e.type) {
            ElementType.BUTTON -> {
                buzz()
                r.pressed = true
                if (e.toggle) {
                    r.toggled = !r.toggled
                    if (r.toggled) e.bindings.forEach(Output::press) else e.bindings.forEach(Output::release)
                } else {
                    e.bindings.forEach(Output::press)
                }
            }
            ElementType.JOYSTICK -> {
                if (e.floating) { r.ox = x; r.oy = y } else { r.ox = cx(e); r.oy = cy(e) }
                updateStick(e, r, x, y)
                if (e.stickMode == StickMode.MOUSE) ensureTicking()
            }
            ElementType.TOUCHPAD -> {
                r.lastX = x; r.lastY = y
                r.downTime = System.currentTimeMillis()
                r.travel = 0f
                r.pressed = true
            }
        }
    }

    private fun onMove(e: Element, r: Rt, x: Float, y: Float) {
        when (e.type) {
            ElementType.BUTTON -> {}
            ElementType.JOYSTICK -> updateStick(e, r, x, y)
            ElementType.TOUCHPAD -> {
                val dx = x - r.lastX; val dy = y - r.lastY
                r.lastX = x; r.lastY = y
                r.travel += hypot(dx, dy)
                val k = 3f * e.sensitivity / dp   // ~3 PC pixels per dp of finger travel at 1x
                Output.mouseMove(dx * k, dy * k)
            }
        }
    }

    private fun onUp(e: Element, r: Rt) {
        r.pointer = -1
        when (e.type) {
            ElementType.BUTTON -> {
                r.pressed = false
                if (!e.toggle) e.bindings.forEach(Output::release)
            }
            ElementType.JOYSTICK -> {
                r.kx = 0f; r.ky = 0f
                applyStick(e, r, 0f, 0f, 0f)
            }
            ElementType.TOUCHPAD -> {
                r.pressed = false
                val quick = System.currentTimeMillis() - r.downTime < 220
                if (quick && r.travel < 12 * dp && e.bindings.isNotEmpty()) {
                    buzz()
                    e.bindings.forEach(Output::press)
                    e.bindings.forEach(Output::release)
                }
            }
        }
    }

    private fun updateStick(e: Element, r: Rt, x: Float, y: Float) {
        val radius = halfW(e)
        var vx = (x - r.ox) / radius
        var vy = (y - r.oy) / radius
        val mag = hypot(vx, vy)
        if (mag > 1f) { vx /= mag; vy /= mag }
        r.kx = vx; r.ky = vy
        val m = min(mag, 1f)
        if (m <= e.deadzone || m == 0f) {
            applyStick(e, r, 0f, 0f, m)
        } else {
            val scaled = (m - e.deadzone) / (1f - e.deadzone)
            applyStick(e, r, vx / m * scaled, vy / m * scaled, m)
        }
    }

    /** sx/sy: output after dead zone (+y down). raw: un-dead-zoned magnitude, for the edge binding. */
    private fun applyStick(e: Element, r: Rt, sx: Float, sy: Float, raw: Float) {
        r.sx = sx; r.sy = sy
        when (e.stickMode) {
            StickMode.PAD_LEFT -> Output.stick(0, sx, -sy)
            StickMode.PAD_RIGHT -> Output.stick(1, sx, -sy)
            StickMode.MOUSE -> {}   // handled by the frame ticker
            StickMode.DIRECTIONAL -> {
                val mag = hypot(sx, sy)
                val ux = if (mag > 0f) sx / mag else 0f
                val uy = if (mag > 0f) sy / mag else 0f
                // 0.38 = sin(22.5°): eight equal 45° sectors, diagonals press two directions.
                val want = booleanArrayOf(uy < -0.38f, uy > 0.38f, ux < -0.38f, ux > 0.38f)
                for (d in 0..3) {
                    if (want[d] != r.dirOn[d]) {
                        r.dirOn[d] = want[d]
                        val b = e.dirs[d] ?: continue
                        if (want[d]) Output.press(b) else Output.release(b)
                    }
                }
            }
        }
        val edge = raw >= 0.93f
        if (edge != r.edgeOn) {
            r.edgeOn = edge
            if (edge) { buzz(); e.bindings.forEach(Output::press) } else e.bindings.forEach(Output::release)
        }
    }

    // ---------------------------------------------------------------- mouse ticker
    private val choreographer = Choreographer.getInstance()
    private var ticking = false
    private var lastFrame = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            val dt = if (lastFrame == 0L) 0f else ((t - lastFrame) / 1e9f).coerceAtMost(0.05f)
            lastFrame = t
            var active = false
            for (e in layout.elements) {
                if (e.type != ElementType.JOYSTICK || e.stickMode != StickMode.MOUSE) continue
                val r = rt[e.id] ?: continue
                if (r.pointer < 0) continue
                active = true
                val mag = hypot(r.sx, r.sy)         // squared response: fine aim near the centre
                val speed = 1500f * e.sensitivity * mag * dt
                Output.mouseMove(r.sx * speed, r.sy * speed)
            }
            if (active && !editMode) choreographer.postFrameCallback(this)
            else { ticking = false; lastFrame = 0L }
        }
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        lastFrame = 0L
        choreographer.postFrameCallback(frame)
    }

    /** Lets go of everything: used when pausing, switching modes or losing focus. */
    fun releaseAll() {
        for (e in layout.elements) {
            val r = rt[e.id] ?: continue
            if (e.type == ElementType.BUTTON && (r.pressed || r.toggled)) e.bindings.forEach(Output::release)
            if (e.type == ElementType.JOYSTICK) {
                if (r.edgeOn) e.bindings.forEach(Output::release)
                for (d in 0..3) if (r.dirOn[d]) e.dirs[d]?.let(Output::release)
                if (e.stickMode == StickMode.PAD_LEFT) Output.stick(0, 0f, 0f)
                if (e.stickMode == StickMode.PAD_RIGHT) Output.stick(1, 0f, 0f)
            }
        }
        rt.clear()
        owners.clear()
        invalidate()
    }

    /**
     * Stops Android's edge back-gesture from stealing touches on controls near the
     * screen edges (the system honours up to 200dp per edge).
     */
    fun updateGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT < 29 || width == 0) return
        val rects = layout.elements.map { e ->
            val pad = if (e.type == ElementType.JOYSTICK) 1.25f else 1f
            android.graphics.Rect(
                (cx(e) - halfW(e) * pad).toInt(), (cy(e) - halfH(e) * pad).toInt(),
                (cx(e) + halfW(e) * pad).toInt(), (cy(e) + halfH(e) * pad).toInt())
        }
        systemGestureExclusionRects = rects
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    // ================================================================ edit mode
    private var dragPointer = -1
    private var dragMode = 0           // 1 = move, 2 = resize
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragMoved = false
    private var wasSelected = false
    private val handleR get() = 13 * dp

    private fun handlePos(e: Element) = Pair(cx(e) + halfW(e), cy(e) + halfH(e))

    private fun editTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = ev.x; val y = ev.y
                dragPointer = ev.getPointerId(0)
                downX = x; downY = y; dragMoved = false
                val sel = selected
                if (sel != null) {
                    val (hx, hy) = handlePos(sel)
                    if (hypot(x - hx, y - hy) <= handleR * 2.2f) {
                        dragMode = 2
                        return true
                    }
                }
                val e = hit(x, y)
                if (e == null) {
                    dragMode = 0
                    selected = null
                } else {
                    wasSelected = e === selected
                    selected = e
                    dragMode = 1
                    dragOffX = x - cx(e); dragOffY = y - cy(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(dragPointer)
                if (i < 0) return true
                val x = ev.getX(i); val y = ev.getY(i)
                if (!dragMoved && hypot(x - downX, y - downY) > 8 * dp) dragMoved = true
                val e = selected ?: return true
                if (dragMode == 1 && dragMoved) {
                    val grid = 8 * dp
                    val nx = ((x - dragOffX) / grid).roundToInt() * grid
                    val ny = ((y - dragOffY) / grid).roundToInt() * grid
                    e.x = ((nx + originX) / sw).coerceIn(0f, 1f)
                    e.y = ((ny + originY) / sh).coerceIn(0f, 1f)
                    invalidate()
                } else if (dragMode == 2) {
                    val hw = max(x - cx(e), 4 * dp)
                    val hh = max(y - cy(e), 4 * dp)
                    if (e.isRound) {
                        e.w = (2 * max(hw, hh) / shortSide).coerceIn(MIN_SIZE, MAX_SIZE)
                        e.h = e.w
                    } else {
                        e.w = (2 * hw / shortSide).coerceIn(MIN_SIZE, MAX_SIZE)
                        e.h = (2 * hh / shortSide).coerceIn(MIN_SIZE, MAX_SIZE)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val e = selected
                if (dragMode == 1 && !dragMoved && wasSelected && e != null) onEditElement?.invoke(e)
                if (dragMode != 0 && dragMoved) onLayoutChanged?.invoke()
                if (dragMode == 2) onLayoutChanged?.invoke()
                dragMode = 0
                dragPointer = -1
            }
            MotionEvent.ACTION_CANCEL -> { dragMode = 0; dragPointer = -1 }
        }
        return true
    }

    // ================================================================ drawing
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val grid = Paint().apply { color = 0x14FFFFFF; strokeWidth = 1f }
    private val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    private val rect = RectF()

    private fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        if (!transparent) canvas.drawColor(Color.BLACK)
        if (editMode) {
            val step = 24 * dp
            var gx = 0f
            while (gx < width) { canvas.drawLine(gx, 0f, gx, height.toFloat(), grid); gx += step }
            var gy = 0f
            while (gy < height) { canvas.drawLine(0f, gy, width.toFloat(), gy, grid); gy += step }
        }
        val op = layout.opacity
        stroke.strokeWidth = 2.5f * dp
        for (e in layout.elements) {
            val r = rt[e.id]
            val active = r != null && (r.pressed || r.toggled || r.pointer >= 0)
            val x = cx(e); val y = cy(e)
            val hw = halfW(e); val hh = halfH(e)
            stroke.pathEffect = if (e.type == ElementType.TOUCHPAD) dash else null
            stroke.color = alpha(e.color, op)
            fill.color = alpha(e.color, if (active) 0.55f * op else 0.14f * op)
            when (e.type) {
                ElementType.BUTTON, ElementType.TOUCHPAD -> {
                    if (e.isRound) {
                        canvas.drawCircle(x, y, hw, fill)
                        canvas.drawCircle(x, y, hw, stroke)
                    } else {
                        rect.set(x - hw, y - hh, x + hw, y + hh)
                        val corner = min(hw, hh) * 0.35f
                        canvas.drawRoundRect(rect, corner, corner, fill)
                        canvas.drawRoundRect(rect, corner, corner, stroke)
                    }
                    if (e.type == ElementType.BUTTON && e.toggle && r?.toggled == true) {
                        fill.color = alpha(Color.WHITE, op)
                        canvas.drawCircle(x, y - hh * 0.62f, 3 * dp, fill)
                    }
                    drawLabel(canvas, e.displayLabel(), x, y, hw, hh, alpha(Color.WHITE, op))
                }
                ElementType.JOYSTICK -> {
                    val ox = if (r != null && r.pointer >= 0) r.ox else x
                    val oy = if (r != null && r.pointer >= 0) r.oy else y
                    fill.color = alpha(e.color, 0.10f * op)
                    canvas.drawCircle(ox, oy, hw, fill)
                    canvas.drawCircle(ox, oy, hw, stroke)
                    stroke.color = alpha(e.color, 0.35f * op)
                    canvas.drawCircle(ox, oy, hw * e.deadzone.coerceAtLeast(0.05f), stroke)
                    drawLabel(canvas, e.displayLabel(), ox, oy + hw * 0.72f, hw * 0.6f, hw * 0.16f,
                        alpha(Color.WHITE, 0.6f * op))
                    val kx = ox + (r?.kx ?: 0f) * hw
                    val ky = oy + (r?.ky ?: 0f) * hw
                    fill.color = alpha(e.color, if (active) 0.75f * op else 0.4f * op)
                    canvas.drawCircle(kx, ky, hw * 0.4f, fill)
                    if (r?.edgeOn == true) {
                        stroke.color = alpha(Color.WHITE, op)
                        canvas.drawCircle(ox, oy, hw, stroke)
                    }
                }
            }
        }
        stroke.pathEffect = null
        val sel = selected
        if (editMode && sel != null) {
            val x = cx(sel); val y = cy(sel)
            val hw = halfW(sel); val hh = halfH(sel)
            stroke.color = Color.WHITE
            stroke.strokeWidth = 1.5f * dp
            stroke.pathEffect = dash
            rect.set(x - hw - 4 * dp, y - hh - 4 * dp, x + hw + 4 * dp, y + hh + 4 * dp)
            canvas.drawRect(rect, stroke)
            stroke.pathEffect = null
            val (hx, hy) = handlePos(sel)
            fill.color = Color.WHITE
            canvas.drawCircle(hx, hy, handleR, fill)
            fill.color = Color.BLACK
            canvas.drawCircle(hx, hy, handleR * 0.35f, fill)
        }
        if (layout.elements.isEmpty() && !transparent) {
            text.textSize = 16 * dp
            text.color = 0x99FFFFFF.toInt()
            canvas.drawText(
                if (editMode) "Add controls with the buttons above" else "Empty layout. Tap ⋯ to edit it",
                width / 2f, height / 2f, text)
        }
    }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float, hw: Float, hh: Float, color: Int) {
        if (s.isEmpty()) return
        text.color = color
        text.textSize = min(hh * 0.62f, 26 * dp).coerceAtLeast(8 * dp)
        val maxW = hw * 1.7f
        val w = text.measureText(s)
        if (w > maxW) text.textSize = (text.textSize * maxW / w).coerceAtLeast(7 * dp)
        val fm = text.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text)
    }
}
