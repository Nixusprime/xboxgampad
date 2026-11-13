package com.xboxpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.xboxpad.STOP"
        const val PREFS = "xboxpad"
        private const val CHANNEL = "xboxpad"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private enum class Kind { BUTTON, PILL, STICK, DPAD }

    /**
     * One on-screen control. w/h are sizes in units of the screen's short side.
     * land/port = [anchorFx, anchorFy, offsetX, offsetY]: anchor is a fraction of the screen,
     * offset is in units of the short side (so clusters like A/B/X/Y keep their shape).
     */
    private class Spec(
        val id: String,
        val kind: Kind,
        val label: String,
        val color: Int,
        val w: Float,
        val h: Float,
        val land: FloatArray,
        val port: FloatArray
    )

    private class Slot(val view: PadView, val lp: LayoutParams)

    private val green = 0xFF5DC21E.toInt()
    private val red = 0xFFE53935.toInt()
    private val blue = 0xFF1E88E5.toInt()
    private val yellow = 0xFFFDD835.toInt()
    private val grey = 0xFFBDBDBD.toInt()

    private val specs: List<Spec> by lazy {
        listOf(
            Spec("LS", Kind.STICK, "", grey, 0.42f, 0.42f, floatArrayOf(0.14f, 0.66f, 0f, 0f), floatArrayOf(0.24f, 0.76f, 0f, 0f)),
            Spec("DPAD", Kind.DPAD, "", grey, 0.32f, 0.32f, floatArrayOf(0.31f, 0.82f, 0f, 0f), floatArrayOf(0.24f, 0.90f, 0f, 0f)),
            Spec("RS", Kind.STICK, "", grey, 0.38f, 0.38f, floatArrayOf(0.70f, 0.80f, 0f, 0f), floatArrayOf(0.76f, 0.91f, 0f, 0f)),
            Spec("Y", Kind.BUTTON, "Y", yellow, 0.16f, 0.16f, floatArrayOf(0.88f, 0.52f, 0f, -0.17f), floatArrayOf(0.76f, 0.76f, 0f, -0.14f)),
            Spec("X", Kind.BUTTON, "X", blue, 0.16f, 0.16f, floatArrayOf(0.88f, 0.52f, -0.17f, 0f), floatArrayOf(0.76f, 0.76f, -0.14f, 0f)),
            Spec("B", Kind.BUTTON, "B", red, 0.16f, 0.16f, floatArrayOf(0.88f, 0.52f, 0.17f, 0f), floatArrayOf(0.76f, 0.76f, 0.14f, 0f)),
            Spec("A", Kind.BUTTON, "A", green, 0.16f, 0.16f, floatArrayOf(0.88f, 0.52f, 0f, 0.17f), floatArrayOf(0.76f, 0.76f, 0f, 0.14f)),
            Spec("LT", Kind.PILL, "LT", grey, 0.24f, 0.11f, floatArrayOf(0.10f, 0.08f, 0f, 0f), floatArrayOf(0.14f, 0.58f, 0f, 0f)),
            Spec("LB", Kind.PILL, "LB", grey, 0.24f, 0.11f, floatArrayOf(0.10f, 0.21f, 0f, 0f), floatArrayOf(0.14f, 0.645f, 0f, 0f)),
            Spec("RT", Kind.PILL, "RT", grey, 0.24f, 0.11f, floatArrayOf(0.90f, 0.08f, 0f, 0f), floatArrayOf(0.86f, 0.58f, 0f, 0f)),
            Spec("RB", Kind.PILL, "RB", grey, 0.24f, 0.11f, floatArrayOf(0.90f, 0.21f, 0f, 0f), floatArrayOf(0.86f, 0.645f, 0f, 0f)),
            Spec("BACK", Kind.PILL, "BACK", grey, 0.20f, 0.09f, floatArrayOf(0.42f, 0.09f, 0f, 0f), floatArrayOf(0.36f, 0.60f, 0f, 0f)),
            Spec("GUIDE", Kind.BUTTON, "X", green, 0.12f, 0.12f, floatArrayOf(0.50f, 0.09f, 0f, 0f), floatArrayOf(0.50f, 0.60f, 0f, 0f)),
            Spec("START", Kind.PILL, "START", grey, 0.20f, 0.09f, floatArrayOf(0.58f, 0.09f, 0f, 0f), floatArrayOf(0.64f, 0.60f, 0f, 0f)),
            Spec("L3", Kind.BUTTON, "L3", grey, 0.10f, 0.10f, floatArrayOf(0.27f, 0.45f, 0f, 0f), floatArrayOf(0.46f, 0.74f, 0f, 0f)),
            Spec("R3", Kind.BUTTON, "R3", grey, 0.10f, 0.10f, floatArrayOf(0.60f, 0.62f, 0f, 0f), floatArrayOf(0.54f, 0.84f, 0f, 0f))
        )
    }

    private val keyCodes = mapOf(
        "A" to GamepadBackend.BTN_A,
        "B" to GamepadBackend.BTN_B,
        "X" to GamepadBackend.BTN_X,
        "Y" to GamepadBackend.BTN_Y,
        "LB" to GamepadBackend.BTN_LB,
        "RB" to GamepadBackend.BTN_RB,
        "BACK" to GamepadBackend.BTN_BACK,
        "START" to GamepadBackend.BTN_START,
        "GUIDE" to GamepadBackend.BTN_GUIDE,
        "L3" to GamepadBackend.BTN_L3,
        "R3" to GamepadBackend.BTN_R3
    )

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: BubbleView? = null
    private var bubbleLp: LayoutParams? = null
    private val slots = mutableListOf<Slot>()
    private var padShown = false
    private var editing = false

    private var lsX = 0
    private var lsY = 0
    private var rsX = 0
    private var rsY = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "scale" || key == "alpha" || key == "layout_rev") {
            if (padShown) rebuildPad()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!running) {
            running = true
            GamepadBackend.start()
            addBubble()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hidePad()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        GamepadBackend.stop()
        running = false
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Wait a moment for the new screen size to settle, then re-place everything.
        handler.postDelayed({
            placeBubble()
            if (padShown) rebuildPad()
        }, 350)
    }

    // ---------------------------------------------------------------- foreground notification

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "XboxPad", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            stopIntent
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("XboxPad is running")
            .setContentText("Tap the floating X to show or hide the controller")
            .setOngoing(true)
            .addAction(action)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun baseParams(w: Int, h: Int): LayoutParams {
        val lp = LayoutParams(
            w, h,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCH_MODAL or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun orient(w: Int, h: Int) = if (w > h) "l" else "p"

    // ---------------------------------------------------------------- bubble

    private fun addBubble() {
        val v = BubbleView(this)
        val size = (52 * resources.displayMetrics.density).roundToInt()
        val lp = baseParams(size, size)
        bubble = v
        bubbleLp = lp
        v.onTap = { onBubbleTap() }
        v.onLongPress = { onBubbleLongPress() }
        v.onDrag = { dx, dy ->
            val (w, h) = screenSize()
            lp.x = (lp.x + dx).roundToInt().coerceIn(0, max(0, w - size))
            lp.y = (lp.y + dy).roundToInt().coerceIn(0, max(0, h - size))
            runCatching { wm.updateViewLayout(v, lp) }
        }
        v.onDragEnd = {
            val (w, h) = screenSize()
            val o = orient(w, h)
            prefs.edit()
                .putFloat("bubble_${o}_x", lp.x.toFloat() / w)
                .putFloat("bubble_${o}_y", lp.y.toFloat() / h)
                .apply()
        }
        placeBubble()
        runCatching { wm.addView(v, lp) }
    }

    private fun placeBubble() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        val (w, h) = screenSize()
        val o = orient(w, h)
        val size = lp.width
        val fx = prefs.getFloat("bubble_${o}_x", 0.93f)
        val fy = prefs.getFloat("bubble_${o}_y", 0.40f)
        lp.x = (fx * w).roundToInt().coerceIn(0, max(0, w - size))
        lp.y = (fy * h).roundToInt().coerceIn(0, max(0, h - size))
        if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun updateBubble() {
        bubble?.active = padShown
        bubble?.editing = editing
    }

    private fun onBubbleTap() {
        if (editing) {
            editing = false
            slots.forEach { it.view.editMode = false }
            updateBubble()
            toast("Layout saved")
            return
        }
        if (padShown) {
            hidePad()
        } else {
            showPad(requireReady = true)
        }
    }

    private fun onBubbleLongPress() {
        editing = !editing
        if (editing) {
            if (!padShown) showPad(requireReady = false)
            slots.forEach { it.view.editMode = true }
            toast("Drag controls to move them. Tap the X when done.")
        } else {
            slots.forEach { it.view.editMode = false }
            toast("Layout saved")
        }
        updateBubble()
    }

    // ---------------------------------------------------------------- pad

    private fun showPad(requireReady: Boolean) {
        if (padShown) return
        if (requireReady && GamepadBackend.state != GamepadBackend.State.READY) {
            when (GamepadBackend.state) {
                GamepadBackend.State.STARTING -> toast("Controller is starting, try again in a second")
                GamepadBackend.State.ERROR -> {
                    toast("Controller failed: " + GamepadBackend.log.lineSequence().lastOrNull().orEmpty() + ". Retrying...")
                    GamepadBackend.start()
                }
                else -> {
                    toast("Controller is not running")
                    GamepadBackend.start()
                }
            }
            return
        }
        padShown = true

        val (w, h) = screenSize()
        val land = w > h
        val o = orient(w, h)
        val u = min(w, h).toFloat()
        val scale = prefs.getInt("scale", 100) / 100f
        val alpha = prefs.getInt("alpha", 70) / 100f

        for (s in specs) {
            val view = createView(s)
            view.padAlpha = alpha
            view.editMode = editing

            val pw = max(1, (s.w * u * scale).roundToInt())
            val ph = max(1, (s.h * u * scale).roundToInt())
            val key = "pos_${o}_${s.id}"

            val cx: Float
            val cy: Float
            if (prefs.contains(key + "_x")) {
                cx = prefs.getFloat(key + "_x", 0.5f) * w
                cy = prefs.getFloat(key + "_y", 0.5f) * h
            } else {
                val a = if (land) s.land else s.port
                cx = a[0] * w + a[2] * u * scale
                cy = a[1] * h + a[3] * u * scale
            }

            val lp = baseParams(pw, ph)
            lp.x = (cx - pw / 2f).roundToInt().coerceIn(0, max(0, w - pw))
            lp.y = (cy - ph / 2f).roundToInt().coerceIn(0, max(0, h - ph))

            view.onDrag = { dx, dy ->
                lp.x = (lp.x + dx).roundToInt().coerceIn(0, max(0, w - pw))
                lp.y = (lp.y + dy).roundToInt().coerceIn(0, max(0, h - ph))
                runCatching { wm.updateViewLayout(view, lp) }
            }
            view.onDragEnd = {
                prefs.edit()
                    .putFloat(key + "_x", (lp.x + pw / 2f) / w)
                    .putFloat(key + "_y", (lp.y + ph / 2f) / h)
                    .apply()
            }

            runCatching { wm.addView(view, lp) }.onSuccess { slots.add(Slot(view, lp)) }
        }
        updateBubble()
    }

    private fun hidePad() {
        slots.forEach {
            it.view.releaseAll()
            runCatching { wm.removeView(it.view) }
        }
        slots.clear()
        padShown = false
        updateBubble()
    }

    private fun rebuildPad() {
        hidePad()
        showPad(requireReady = false)
    }

    private fun createView(s: Spec): PadView = when (s.kind) {
        Kind.BUTTON, Kind.PILL ->
            ButtonView(this, s.label, s.color, s.kind == Kind.PILL) { down -> onButton(s.id, down) }
        Kind.STICK ->
            StickView(this, s.color) { x, y -> onStick(s.id, x, y) }
        Kind.DPAD ->
            DPadView(this) { hx, hy -> GamepadBackend.axes2(GamepadBackend.ABS_HAT_X, hx, GamepadBackend.ABS_HAT_Y, hy) }
    }

    private fun onButton(id: String, down: Boolean) {
        when (id) {
            "LT" -> GamepadBackend.axis(GamepadBackend.ABS_LT, if (down) 255 else 0)
            "RT" -> GamepadBackend.axis(GamepadBackend.ABS_RT, if (down) 255 else 0)
            else -> keyCodes[id]?.let { GamepadBackend.key(it, down) }
        }
    }

    private fun onStick(id: String, x: Float, y: Float) {
        val ix = (x * 32767f).roundToInt()
        val iy = (y * 32767f).roundToInt()
        if (id == "LS") {
            if (ix != lsX || iy != lsY) {
                lsX = ix
                lsY = iy
                GamepadBackend.axes2(GamepadBackend.ABS_LX, ix, GamepadBackend.ABS_LY, iy)
            }
        } else {
            if (ix != rsX || iy != rsY) {
                rsX = ix
                rsY = iy
                GamepadBackend.axes2(GamepadBackend.ABS_RX, ix, GamepadBackend.ABS_RY, iy)
            }
        }
    }
}
