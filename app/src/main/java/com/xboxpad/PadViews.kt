package com.xboxpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Base class for every on-screen control. In edit mode it just drags its own window around. */
abstract class PadView(ctx: Context) : View(ctx) {

    var editMode: Boolean = false
        set(value) {
            field = value
            if (value) releaseAll()
            invalidate()
        }

    var padAlpha: Float = 0.7f
        set(value) {
            field = value
            invalidate()
        }

    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private var lastRawX = 0f
    private var lastRawY = 0f

    protected val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFA000.toInt()
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    protected fun tint(color: Int, factor: Float = 1f): Int {
        val a = (255f * padAlpha * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    protected fun drawEditFrame(c: Canvas) {
        if (editMode) {
            c.drawRect(2f, 2f, width - 2f, height - 2f, editPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    onDrag?.invoke(e.rawX - lastRawX, e.rawY - lastRawY)
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDragEnd?.invoke()
            }
            return true
        }
        return handleTouch(e)
    }

    protected abstract fun handleTouch(e: MotionEvent): Boolean

    /** Called when the pad is hidden or edit mode starts: let go of everything. */
    open fun releaseAll() {}
}

/** Round or pill-shaped button (A/B/X/Y, bumpers, triggers, Back/Start/Guide, L3/R3). */
class ButtonView(
    ctx: Context,
    private val label: String,
    private val accent: Int,
    private val pill: Boolean,
    private val onChange: (Boolean) -> Unit
) : PadView(ctx) {

    private var down = false
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun press(value: Boolean) {
        if (down == value) return
        down = value
        if (value) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onChange(value)
        invalidate()
    }

    override fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        return true
    }

    override fun releaseAll() {
        press(false)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        rect.set(4f, 4f, w - 4f, h - 4f)

        fill.color = if (down) tint(accent, 0.9f) else tint(0xFF1C1C1C.toInt(), 0.9f)
        ring.color = tint(accent, 1f)
        ring.strokeWidth = s * 0.05f
        text.color = if (down) Color.BLACK else accent
        text.textSize = s * (if (pill) 0.42f else 0.5f)

        if (pill) {
            val r = s / 2f
            c.drawRoundRect(rect, r, r, fill)
            c.drawRoundRect(rect, r, r, ring)
        } else {
            c.drawOval(rect, fill)
            c.drawOval(rect, ring)
        }
        val ty = h / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(label, w / 2f, ty, text)
        drawEditFrame(c)
    }
}

/** 8-way D-pad. Reports hat X/Y as -1, 0 or 1. */
class DPadView(
    ctx: Context,
    private val onChange: (Int, Int) -> Unit
) : PadView(ctx) {

    private var hx = 0
    private var hy = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun set(x: Int, y: Int) {
        if (x == hx && y == hy) return
        val wasIdle = hx == 0 && hy == 0
        hx = x
        hy = y
        if (wasIdle) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onChange(x, y)
        invalidate()
    }

    override fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = e.x - width / 2f
                val dy = e.y - height / 2f
                val dead = width * 0.10f
                val ax = abs(dx)
                val ay = abs(dy)
                val nx = if (ax > dead && ax > ay * 0.5f) (if (dx > 0) 1 else -1) else 0
                val ny = if (ay > dead && ay > ax * 0.5f) (if (dy > 0) 1 else -1) else 0
                set(nx, ny)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(0, 0)
        }
        return true
    }

    override fun releaseAll() {
        set(0, 0)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val t = min(w, h) / 3f
        val corner = t * 0.3f
        val white = 0xFFFFFFFF.toInt()

        fill.color = tint(0xFF1C1C1C.toInt(), 0.9f)
        ring.color = tint(white, 0.8f)
        ring.strokeWidth = t * 0.06f
        lit.color = tint(white, 0.55f)
        arrow.color = tint(white, 1f)

        // horizontal + vertical bars
        rect.set(2f, cy - t / 2f, w - 2f, cy + t / 2f)
        c.drawRoundRect(rect, corner, corner, fill)
        c.drawRoundRect(rect, corner, corner, ring)
        rect.set(cx - t / 2f, 2f, cx + t / 2f, h - 2f)
        c.drawRoundRect(rect, corner, corner, fill)
        c.drawRoundRect(rect, corner, corner, ring)

        // lit segments
        if (hx < 0) { rect.set(2f, cy - t / 2f, cx, cy + t / 2f); c.drawRoundRect(rect, corner, corner, lit) }
        if (hx > 0) { rect.set(cx, cy - t / 2f, w - 2f, cy + t / 2f); c.drawRoundRect(rect, corner, corner, lit) }
        if (hy < 0) { rect.set(cx - t / 2f, 2f, cx + t / 2f, cy); c.drawRoundRect(rect, corner, corner, lit) }
        if (hy > 0) { rect.set(cx - t / 2f, cy, cx + t / 2f, h - 2f); c.drawRoundRect(rect, corner, corner, lit) }

        // arrows
        val a = t * 0.28f
        val off = w / 2f - t * 0.45f
        triangle(c, cx, cy - off, 0f, -a)
        triangle(c, cx, cy + off, 0f, a)
        triangle(c, cx - off, cy, -a, 0f)
        triangle(c, cx + off, cy, a, 0f)

        drawEditFrame(c)
    }

    /** Small arrow head at (x,y) pointing in direction (dx,dy). */
    private fun triangle(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        path.reset()
        if (dx == 0f) {
            path.moveTo(x, y + dy)
            path.lineTo(x - abs(dy), y - dy * 0.6f)
            path.lineTo(x + abs(dy), y - dy * 0.6f)
        } else {
            path.moveTo(x + dx, y)
            path.lineTo(x - dx * 0.6f, y - abs(dx))
            path.lineTo(x - dx * 0.6f, y + abs(dx))
        }
        path.close()
        c.drawPath(path, arrow)
    }
}

/** Analog stick. Reports x/y in -1..1 (screen-down = positive y). */
class StickView(
    ctx: Context,
    private val accent: Int,
    private val onChange: (Float, Float) -> Unit
) : PadView(ctx) {

    private var kx = 0f
    private var ky = 0f
    private var active = false
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun radius() = min(width, height) / 2f - 6f
    private fun knobRadius() = radius() * 0.42f
    private fun travel() = max(1f, radius() - knobRadius() * 0.5f)

    private fun update(x: Float, y: Float) {
        var dx = x - width / 2f
        var dy = y - height / 2f
        val t = travel()
        val len = hypot(dx, dy)
        if (len > t) {
            dx = dx / len * t
            dy = dy / len * t
        }
        kx = dx / t
        ky = dy / t
        onChange(kx, ky)
        invalidate()
    }

    override fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() {
        active = false
        kx = 0f
        ky = 0f
        onChange(0f, 0f)
        invalidate()
    }

    override fun releaseAll() {
        if (active || kx != 0f || ky != 0f) release()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        val kr = knobRadius()
        val white = 0xFFFFFFFF.toInt()

        basePaint.color = tint(0xFF1C1C1C.toInt(), 0.6f)
        ring.color = tint(white, 0.7f)
        ring.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r, basePaint)
        c.drawCircle(cx, cy, r, ring)

        knob.color = tint(if (active) 0xFF5A5A5A.toInt() else 0xFF343434.toInt(), 1f)
        knobRing.color = tint(accent, 1f)
        knobRing.strokeWidth = kr * 0.12f
        val px = cx + kx * travel()
        val py = cy + ky * travel()
        c.drawCircle(px, py, kr, knob)
        c.drawCircle(px, py, kr, knobRing)

        drawEditFrame(c)
    }
}

/** The small floating bubble. Tap = show/hide pad, long-press = edit layout, drag = move. */
class BubbleView(ctx: Context) : View(ctx) {

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var editing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var longFired = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF5DC21E.toInt()
    }

    private val longRunnable = Runnable {
        if (!moved) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastX = downX
                lastY = downY
                moved = false
                longFired = false
                postDelayed(longRunnable, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    moved = true
                    removeCallbacks(longRunnable)
                }
                if (moved) onDrag?.invoke(e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRunnable)
                if (moved) onDragEnd?.invoke() else if (!longFired) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRunnable)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f - 3f
        fill.color = 0xE6101010.toInt()
        ring.color = when {
            editing -> 0xFFFFA000.toInt()
            active -> 0xFF5DC21E.toInt()
            else -> 0xFF8A8A8A.toInt()
        }
        ring.strokeWidth = r * 0.12f
        c.drawCircle(w / 2f, h / 2f, r, fill)
        c.drawCircle(w / 2f, h / 2f, r, ring)
        text.textSize = r * 1.15f
        c.drawText("X", w / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text)
    }
}
