package com.padforge.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a layout's controls on top of other apps (e.g. Parsec) and sends their
 * input to the PC companion. Every control lives in its own small window, so
 * touches anywhere else on the screen go straight to the app underneath.
 */
class OverlayService : Service() {

    companion object {
        private const val ACTION_START = "com.padforge.app.overlay.START"
        private const val ACTION_TOGGLE = "com.padforge.app.overlay.TOGGLE"
        private const val ACTION_STOP = "com.padforge.app.overlay.STOP"
        private const val EXTRA_LAYOUT = "layout"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1

        const val PARSEC_PACKAGE = "tv.parsec.client"

        @Volatile var running = false
            private set

        fun canDraw(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        fun start(ctx: Context, layoutId: String) {
            val i = Intent(ctx, OverlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_LAYOUT, layoutId)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var store: Store
    private lateinit var wm: WindowManager
    private lateinit var uiContext: Context
    private val main = Handler(Looper.getMainLooper())
    private var layout: Layout? = null
    private val controls = mutableListOf<Pair<PadView, WindowManager.LayoutParams>>()
    private var handle: HandleView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var hidden = false
    private var screenW = 0
    private var screenH = 0
    private var lastState: Connection.State? = null
    private var lastNotified = 0L
    private val connectionListener: () -> Unit = { onConnectionChanged() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        store = Store(this)
        uiContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createWindowContext(display, overlayType(), null)
        } else this
        wm = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_START -> {
                val l = store.get(intent.getStringExtra(EXTRA_LAYOUT) ?: "")
                if (l == null || !canDraw(this)) { stopSelf(); return START_NOT_STICKY }
                layout = l
                hidden = false
                buildWindows()
                Connection.listener = connectionListener
                Connection.start(store)
                updateNotification()
            }
            ACTION_TOGGLE -> setHidden(!hidden)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeWindows()
        // If the full-screen pad has already taken over the connection, leave it alone.
        if (Connection.listener === connectionListener) {
            Connection.listener = null
            Connection.stop()
        }
        running = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation or a fold/unfold changes the screen size: lay the controls out again.
        val (w, h) = realScreenSize()
        if (layout != null && (w != screenW || h != screenH)) buildWindows()
    }

    // ------------------------------------------------------------ windows

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun baseParams(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun realScreenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val p = Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        return p.x to p.y
    }

    private fun buildWindows() {
        removeWindows()
        val l = layout ?: return
        val (w, h) = realScreenSize()
        screenW = w; screenH = h
        val shortSide = min(w, h).toFloat()
        val dp = resources.displayMetrics.density

        // Later windows sit on top: buttons above sticks above touchpads, so a big
        // stick or touchpad area can never cover a button.
        val order = l.elements.sortedBy {
            when (it.type) { ElementType.TOUCHPAD -> 0; ElementType.JOYSTICK -> 1; ElementType.BUTTON -> 2 }
        }
        for (e in order) {
            // Window size: the control plus room for joystick knobs (and the
            // whole ring of a floating stick, which follows the thumb).
            val hw = e.w * shortSide / 2f
            val hh = (if (e.isRound) e.w else e.h) * shortSide / 2f
            val reach = when {
                e.type != ElementType.JOYSTICK -> 1.1f
                e.floating -> 1.9f
                else -> 1.45f
            }
            val halfW = (hw * reach + 6 * dp).roundToInt()
            val halfH = ((if (e.type == ElementType.JOYSTICK) hw else hh) * reach + 6 * dp).roundToInt()
            val cx = e.x * w
            val cy = e.y * h
            val left = (cx - halfW).roundToInt()
            val top = (cy - halfH).roundToInt()

            val pad = PadView(uiContext).apply {
                screenW = w.toFloat(); screenH = h.toFloat()
                originX = left.toFloat(); originY = top.toFloat()
                transparent = true
                layout = Layout(id = l.id, name = l.name, elements = mutableListOf(e),
                    opacity = l.opacity, haptics = l.haptics)
            }
            val lp = baseParams(halfW * 2, halfH * 2, left, top)
            wm.addView(pad, lp)
            controls += pad to lp
        }

        val size = (44 * dp).roundToInt()
        val hv = HandleView(uiContext)
        val hp = baseParams(size, size,
            (store.handleX * w - size / 2f).roundToInt().coerceIn(0, w - size),
            (store.handleY * h).roundToInt().coerceIn(0, h - size))
        wm.addView(hv, hp)
        handle = hv
        handleParams = hp
        applyHidden()
    }

    private fun removeWindows() {
        for ((v, _) in controls) {
            v.releaseAll()
            if (v.isAttachedToWindow) try { wm.removeView(v) } catch (_: Exception) {}
        }
        controls.clear()
        handle?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        handle = null
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        applyHidden()
        updateNotification()
    }

    private fun applyHidden() {
        // Hidden controls are taken off the screen entirely: Android 12+ blocks touches
        // that pass through an invisible-but-present overlay, which would freeze Parsec.
        for ((v, lp) in controls) {
            try {
                if (hidden && v.isAttachedToWindow) {
                    v.releaseAll()
                    wm.removeView(v)
                } else if (!hidden && !v.isAttachedToWindow) {
                    wm.addView(v, lp)
                }
            } catch (_: Exception) {}
        }
        // Keep the handle on top of re-added controls.
        val hv = handle
        val hp = handleParams
        if (!hidden && hv != null && hp != null && hv.isAttachedToWindow) {
            try { wm.removeView(hv); wm.addView(hv, hp) } catch (_: Exception) {}
        }
        handle?.invalidate()
    }

    private fun onConnectionChanged() {
        handle?.invalidate()
        // Refresh the notification when the state changes, and every 10 s for the latency.
        val now = System.currentTimeMillis()
        if (Connection.state != lastState || now - lastNotified > 10_000) {
            lastState = Connection.state
            lastNotified = now
            updateNotification()
        }
    }

    // ------------------------------------------------------------ notification

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Controller overlay",
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        fun service(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, OverlayService::class.java).setAction(action), immutable or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java),
            immutable or PendingIntent.FLAG_UPDATE_CURRENT)
        val edit = PendingIntent.getActivity(this, 11, Intent(this, PadActivity::class.java)
            .putExtra(PadActivity.EXTRA_LAYOUT, layout?.id ?: "")
            .putExtra(PadActivity.EXTRA_EDIT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable or PendingIntent.FLAG_UPDATE_CURRENT)

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        @Suppress("DEPRECATION")
        return b.setSmallIcon(R.drawable.ic_stat_pad)
            .setContentTitle("PadForge overlay: ${layout?.name ?: ""}")
            .setContentText(Connection.statusText() + if (hidden) " · controls hidden" else "")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (hidden) "Show controls" else "Hide controls",
                service(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Edit layout", edit).build())
            .addAction(Notification.Action.Builder(null, "Stop", service(ACTION_STOP, 2)).build())
            .build()
    }

    // ------------------------------------------------------------ handle

    /**
     * Small floating button. Tap: hide/show the controls. Drag: move it.
     * Long-press: stop the overlay.
     */
    @SuppressLint("ViewConstructor")
    private inner class HandleView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(context, "PadForge overlay stopped", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            paint.style = Paint.Style.FILL
            paint.color = if (hidden) 0x66000000 else 0xAA000000.toInt()
            c.drawCircle(r, r, r - 1, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.09f
            paint.color = if (hidden) 0x88FFFFFF.toInt() else Color.WHITE
            // Four little "buttons" in a diamond, like a controller's face buttons.
            val d = r * 0.32f
            val br = r * 0.13f
            c.drawCircle(r, r - d, br, paint); c.drawCircle(r, r + d, br, paint)
            c.drawCircle(r - d, r, br, paint); c.drawCircle(r + d, r, br, paint)
            paint.style = Paint.Style.FILL
            paint.color = when (Connection.state) {
                Connection.State.CONNECTED -> 0xFF66BB6A.toInt()
                Connection.State.CONNECTING -> 0xFFFFCA28.toInt()
                Connection.State.OFF -> 0xFFEF5350.toInt()
            }
            c.drawCircle(r * 1.62f, r * 0.38f, r * 0.2f, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val lp = handleParams ?: return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = (startX + dx).roundToInt().coerceIn(0, screenW - width)
                        lp.y = (startY + dy).roundToInt().coerceIn(0, screenH - height)
                        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (longPressed) return true
                    if (dragging) {
                        store.handleX = (lp.x + width / 2f) / screenW
                        store.handleY = lp.y.toFloat() / screenH
                    } else if (Connection.state != Connection.State.CONNECTED) {
                        Toast.makeText(context, Connection.statusText(), Toast.LENGTH_LONG).show()
                        if (Connection.state == Connection.State.OFF) Connection.start(store)
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        setHidden(!hidden)
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }
}
