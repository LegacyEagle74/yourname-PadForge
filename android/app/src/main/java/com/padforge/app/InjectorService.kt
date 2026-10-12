package com.padforge.app

import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in a separate process started by Shizuku with shell (ADB) permissions,
 * which include INJECT_EVENTS. It turns simple commands from the app into real
 * Android key and mouse events delivered to whatever app is in front (Parsec).
 *
 * The wire format is hand-rolled Parcel calls (see [Cmd]) so no AIDL is needed.
 * Never referenced directly by the app: Shizuku loads it by class name.
 */
class InjectorService() : Binder() {

    @Suppress("unused")
    constructor(context: Context) : this()

    object Cmd {
        const val KEY = 1           // int keyCode, int scanCode, int down
        const val MOVE = 2          // float dx, float dy
        const val BUTTON = 3        // int MotionEvent.BUTTON_*, int down
        const val SCROLL = 4        // float vertical, float horizontal
        const val PAD_KEY = 5       // int keyCode, int down
        const val PAD_AXES = 6      // float lx, ly, rx, ry, lt, rt
        const val RELEASE_ALL = 7
        const val CONFIG = 8        // int mouseMode (0 auto, 1 relative, 2 pointer), int screenW, int screenH
        const val PING = 9          // reply: int ok, String error
        const val EXIT = 16777115   // Shizuku's "destroy" transaction
    }

    private var injectError = ""
    private val injector: Any?
    private val injectMethod: Method?
    private val setActionButton: Method? =
        try { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }

    init {
        var im: Any? = null
        var m: Method? = null
        try {
            // Android 14 moved the injector to InputManagerGlobal; older versions use InputManager.
            im = try {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
            } catch (_: Throwable) {
                Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
            }
            m = im!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            injectError = "Can't access Android's input injector: $t"
        }
        injector = im
        injectMethod = m
    }

    /** 0 = auto (follow whether the app has captured the pointer), 1 = relative, 2 = pointer. */
    @Volatile private var mouseMode = 0
    /** Auto mode: does the app on screen currently have pointer capture (e.g. Parsec in a game)? */
    @Volatile private var captured = false
    @Volatile private var lastActivity = 0L
    private val relative get() = mouseMode == 1 || (mouseMode == 0 && captured)
    private var screenW = 1920
    private var screenH = 1080
    private var px = 960f
    private var py = 540f
    private var buttons = 0
    private var mouseDown = 0L
    private var meta = 0
    private val heldKeys = LinkedHashMap<Int, Int>()   // keyCode -> scanCode
    private val heldPad = LinkedHashSet<Int>()
    private val axes = FloatArray(6)

    private fun inject(ev: InputEvent) {
        try {
            injectMethod?.invoke(injector, ev, 0)   // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (t: Throwable) {
            injectError = (t.cause ?: t).toString()
        }
    }

    // ------------------------------------------------------------------ keyboard

    private fun metaFor(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
        else -> 0
    }

    private fun recomputeMeta() {
        meta = 0
        for (k in heldKeys.keys) meta = meta or metaFor(k)
    }

    private fun key(code: Int, scan: Int, down: Boolean) {
        if (down) heldKeys[code] = scan else heldKeys.remove(code)
        recomputeMeta()
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, scan, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD))
    }

    // ------------------------------------------------------------------ mouse

    private fun mouseEvent(action: Int, x: Float, y: Float, relX: Float, relY: Float,
                           actionButton: Int = 0, vscroll: Float = 0f, hscroll: Float = 0f): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val c = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (buttons != 0) 1f else 0f
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, relX)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, relY)
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
            if (hscroll != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
        }
        val source = if (relative) InputDevice.SOURCE_MOUSE_RELATIVE else InputDevice.SOURCE_MOUSE
        val ev = MotionEvent.obtain(if (buttons != 0) mouseDown else now, now, action, 1, arrayOf(props), arrayOf(c),
            meta, buttons, 1f, 1f, 0, 0, source, 0)
        if (actionButton != 0) try { setActionButton?.invoke(ev, actionButton) } catch (_: Throwable) {}
        return ev
    }

    /**
     * Auto mouse mode: Parsec captures the pointer while a game hides the cursor, and then only
     * relative motion works; otherwise relative motion would turn into arrow-key presses. Android
     * has no API for "is the pointer captured", but `dumpsys input` (shell can run it) reports it.
     */
    private val captureWatcher = Thread({
        while (true) {
            try {
                if (mouseMode == 0 && SystemClock.uptimeMillis() - lastActivity < 5000) captured = queryCapture()
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                return@Thread
            } catch (_: Throwable) {
            }
        }
    }, "capture-watch").apply { isDaemon = true; start() }

    private fun queryCapture(): Boolean {
        val p = ProcessBuilder("dumpsys", "input").redirectErrorStream(true).start()
        var result: Boolean? = null
        p.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                val compact = line.replace(" ", "").lowercase()
                when {
                    // Android 10-14 InputDispatcher: "Current Window with Pointer Capture: <name|None>"
                    compact.startsWith("currentwindowwithpointercapture:") ->
                        result = !compact.endsWith(":none")
                    result == null && compact.startsWith("pointercapturerequested:") ->
                        result = compact.endsWith("true")
                    result == null && compact.contains("pointercapture") &&
                        (compact.contains("enable=true") || compact.contains("enabled=true")) -> result = true
                }
            }
        }
        p.waitFor()
        return result ?: false
    }

    private fun move(dx: Float, dy: Float) {
        lastActivity = SystemClock.uptimeMillis()
        if (relative) {
            // Captured-pointer apps (Parsec in games) read the deltas as x/y.
            inject(mouseEvent(MotionEvent.ACTION_MOVE, dx, dy, dx, dy))
        } else {
            px = (px + dx).coerceIn(0f, screenW - 1f)
            py = (py + dy).coerceIn(0f, screenH - 1f)
            inject(mouseEvent(if (buttons != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE, px, py, dx, dy))
        }
    }

    private fun x() = if (relative) 0f else px
    private fun y() = if (relative) 0f else py

    private fun button(b: Int, down: Boolean) {
        lastActivity = SystemClock.uptimeMillis()
        if (down) {
            if (buttons and b != 0) return
            val first = buttons == 0
            buttons = buttons or b
            if (first) {
                mouseDown = SystemClock.uptimeMillis()
                inject(mouseEvent(MotionEvent.ACTION_DOWN, x(), y(), 0f, 0f))
            }
            inject(mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, x(), y(), 0f, 0f, actionButton = b))
        } else {
            if (buttons and b == 0) return
            buttons = buttons and b.inv()
            inject(mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, x(), y(), 0f, 0f, actionButton = b))
            if (buttons == 0) inject(mouseEvent(MotionEvent.ACTION_UP, x(), y(), 0f, 0f))
        }
    }

    private fun scroll(v: Float, h: Float) {
        inject(mouseEvent(MotionEvent.ACTION_SCROLL, x(), y(), 0f, 0f, vscroll = v, hscroll = h))
    }

    // ------------------------------------------------------------------ gamepad (experimental)

    private fun padKey(code: Int, down: Boolean) {
        if (down) heldPad.add(code) else heldPad.remove(code)
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0,
            0, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK))
    }

    private fun padAxes() {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
        val c = MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, axes[0]); setAxisValue(MotionEvent.AXIS_Y, axes[1])
            setAxisValue(MotionEvent.AXIS_Z, axes[2]); setAxisValue(MotionEvent.AXIS_RZ, axes[3])
            setAxisValue(MotionEvent.AXIS_LTRIGGER, axes[4]); setAxisValue(MotionEvent.AXIS_RTRIGGER, axes[5])
            setAxisValue(MotionEvent.AXIS_BRAKE, axes[4]); setAxisValue(MotionEvent.AXIS_GAS, axes[5])
        }
        inject(MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, arrayOf(props), arrayOf(c),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_JOYSTICK, 0))
    }

    private fun releaseAll() {
        for ((code, scan) in heldKeys.entries.toList()) key(code, scan, false)
        for (b in listOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY)) {
            if (buttons and b != 0) button(b, false)
        }
        for (code in heldPad.toList()) padKey(code, false)
        if (axes.any { it != 0f }) { axes.fill(0f); padAxes() }
    }

    // ------------------------------------------------------------------ binder

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            when (code) {
                Cmd.KEY -> key(data.readInt(), data.readInt(), data.readInt() != 0)
                Cmd.MOVE -> move(data.readFloat(), data.readFloat())
                Cmd.BUTTON -> button(data.readInt(), data.readInt() != 0)
                Cmd.SCROLL -> scroll(data.readFloat(), data.readFloat())
                Cmd.PAD_KEY -> padKey(data.readInt(), data.readInt() != 0)
                Cmd.PAD_AXES -> { for (i in 0 until 6) axes[i] = data.readFloat(); padAxes() }
                Cmd.RELEASE_ALL -> releaseAll()
                Cmd.CONFIG -> {
                    mouseMode = data.readInt().coerceIn(0, 2)
                    lastActivity = SystemClock.uptimeMillis()
                    screenW = data.readInt().coerceAtLeast(1)
                    screenH = data.readInt().coerceAtLeast(1)
                    px = screenW / 2f; py = screenH / 2f
                }
                Cmd.PING -> {
                    // available flag + the latest injection error (cleared once reported)
                    reply?.writeInt(if (injectMethod != null) 1 else 0)
                    reply?.writeString(injectError)
                    if (injectMethod != null) injectError = ""
                }
                Cmd.EXIT -> { releaseAll(); exitProcess(0) }
                else -> return super.onTransact(code, data, reply, flags)
            }
        } catch (t: Throwable) {
            injectError = t.toString()
        }
        return true
    }
}
