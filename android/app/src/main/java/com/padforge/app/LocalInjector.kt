package com.padforge.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.Display
import android.view.MotionEvent
import rikka.shizuku.Shizuku

/**
 * Phone-only mode: sends key, mouse and controller events into the app on screen
 * (e.g. Parsec) through [InjectorService], which Shizuku runs with ADB permissions.
 */
object LocalInjector {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 4242

    @Volatile var state = Connection.State.OFF
        private set
    @Volatile var lastError = ""
        private set
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binder: IBinder? = null
    private var bound = false
    /** 0 = auto, 1 = game (relative), 2 = pointer. */
    private var mouseMode = 0
    private var density = 1f
    private var screenW = 1920
    private var screenH = 1080
    private val axes = FloatArray(6)

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildInfo.PACKAGE, InjectorService::class.java.name))
            .daemon(false)
            .processNameSuffix("injector")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || !service.pingBinder()) {
                fail("The input service didn't start. Restart Shizuku and try again.")
                return
            }
            binder = service
            call(InjectorService.Cmd.CONFIG, oneway = false) {
                it.writeInt(mouseMode); it.writeInt(screenW); it.writeInt(screenH)
            }
            val (ok, err) = ping()
            if (!ok) {
                fail("Android refused input injection: $err")
                return
            }
            lastError = ""
            setState(Connection.State.CONNECTED)
            main.removeCallbacks(healthCheck)
            main.postDelayed(healthCheck, 3000)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            fail("The input service stopped. Was Shizuku stopped?")
        }
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        binder = null
        bound = false
        fail("Shizuku stopped. Open Shizuku and start it again (needed after every phone restart).")
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST) pendingPermission?.invoke(result == PackageManager.PERMISSION_GRANTED)
        pendingPermission = null
    }
    private var pendingPermission: ((Boolean) -> Unit)? = null

    /** Every few seconds: is the service alive, and did any injection fail? */
    private val healthCheck = object : Runnable {
        override fun run() {
            if (state != Connection.State.CONNECTED) return
            val (ok, err) = ping()
            if (!ok) {
                fail("The input service stopped. Was Shizuku stopped?")
                return
            }
            if (err.isNotEmpty()) {
                lastError = "Input error: $err"
                notifyListener()
            }
            main.postDelayed(this, 3000)
        }
    }

    // ------------------------------------------------------------------ status helpers

    enum class Setup { NOT_INSTALLED, NOT_RUNNING, OLD_VERSION, NEEDS_PERMISSION, READY }

    fun setupState(ctx: Context): Setup {
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!running) {
            val installed = try { ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true } catch (_: Exception) { false }
            return if (installed) Setup.NOT_RUNNING else Setup.NOT_INSTALLED
        }
        if (Shizuku.isPreV11()) return Setup.OLD_VERSION
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Setup.READY
            else Setup.NEEDS_PERMISSION
    }

    /** Shows Shizuku's permission dialog if needed; calls back with whether PadForge may use it. */
    fun ensurePermission(onResult: (Boolean) -> Unit) {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) { onResult(false); return }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { onResult(true); return }
            Shizuku.removeRequestPermissionResultListener(permissionResult)
            Shizuku.addRequestPermissionResultListener(permissionResult)
            pendingPermission = onResult
            Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (t: Throwable) {
            onResult(false)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun start(ctx: Context, mouse: Int) {
        stop()
        mouseMode = mouse
        density = ctx.resources.displayMetrics.density
        try {
            val p = Point()
            @Suppress("DEPRECATION")
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
            // Landscape: the overlay is used over a landscape game stream.
            screenW = maxOf(p.x, p.y); screenH = minOf(p.x, p.y)
        } catch (_: Throwable) {}
        when (setupState(ctx)) {
            Setup.NOT_INSTALLED -> return fail("Shizuku isn't installed. Install it, then start it (see PadForge's home screen).")
            Setup.NOT_RUNNING -> return fail("Shizuku isn't running. Open Shizuku and start it (needed after every phone restart).")
            Setup.OLD_VERSION -> return fail("Shizuku is too old. Update it from the Play Store.")
            Setup.NEEDS_PERMISSION -> return fail("PadForge doesn't have Shizuku permission yet. Start it from PadForge's home screen.")
            Setup.READY -> {}
        }
        setState(Connection.State.CONNECTING)
        try {
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.bindUserService(args, connection)
            bound = true
        } catch (t: Throwable) {
            fail("Couldn't start the input service: ${t.message ?: t}")
        }
    }

    fun stop() {
        main.removeCallbacks(healthCheck)
        if (binder != null) call(InjectorService.Cmd.RELEASE_ALL)
        if (bound) {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) {}
            bound = false
        }
        binder = null
        axes.fill(0f)
        if (state != Connection.State.OFF) setState(Connection.State.OFF)
    }

    private fun fail(msg: String) {
        lastError = msg
        binder = null
        setState(Connection.State.OFF)
    }

    private fun setState(s: Connection.State) {
        state = s
        notifyListener()
    }

    private fun notifyListener() {
        main.post { listener?.invoke() }
    }

    fun statusText(): String = when (state) {
        Connection.State.OFF -> lastError.ifEmpty { "Not started" }
        Connection.State.CONNECTING -> "Starting input service…"
        Connection.State.CONNECTED -> if (lastError.isNotEmpty()) lastError
            else "Sending input on this phone (mouse: ${MOUSE_MODES[mouseMode]})"
    }

    // ------------------------------------------------------------------ calls

    private inline fun call(code: Int, oneway: Boolean = true, write: (Parcel) -> Unit = {}) {
        val b = binder ?: return
        val data = Parcel.obtain()
        try {
            write(data)
            b.transact(code, data, null, if (oneway) IBinder.FLAG_ONEWAY else 0)
        } catch (t: Throwable) {
            // Dead service: the health check / binder-dead listener reports it.
        } finally {
            data.recycle()
        }
    }

    private fun ping(): Pair<Boolean, String> {
        val b = binder ?: return false to "not connected"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            b.transact(InjectorService.Cmd.PING, data, reply, 0)
            (reply.readInt() == 1) to (reply.readString() ?: "")
        } catch (t: Throwable) {
            false to (t.message ?: t.toString())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun key(name: String, down: Boolean) {
        val (code, scan) = KeyNames.ANDROID_KEYS[name] ?: return
        call(InjectorService.Cmd.KEY) { it.writeInt(code); it.writeInt(scan); it.writeInt(if (down) 1 else 0) }
    }

    private fun mouseButton(name: String, down: Boolean) {
        val b = when (name) {
            "Left" -> MotionEvent.BUTTON_PRIMARY
            "Right" -> MotionEvent.BUTTON_SECONDARY
            "Middle" -> MotionEvent.BUTTON_TERTIARY
            else -> return   // Back/Forward mouse buttons would act as Android's Back: not sent
        }
        call(InjectorService.Cmd.BUTTON) { it.writeInt(b); it.writeInt(if (down) 1 else 0) }
    }

    private fun padKey(name: String, down: Boolean) {
        val code = KeyNames.ANDROID_PAD[name] ?: return
        call(InjectorService.Cmd.PAD_KEY) { it.writeInt(code); it.writeInt(if (down) 1 else 0) }
    }

    private fun sendAxes() = call(InjectorService.Cmd.PAD_AXES) { p -> axes.forEach { p.writeFloat(it) } }

    fun press(b: Binding) {
        when (b.kind) {
            BindKind.KEY -> key(b.code, true)
            BindKind.MOUSE -> mouseButton(b.code, true)
            BindKind.WHEEL -> call(InjectorService.Cmd.SCROLL) {
                when (b.code) {
                    "Up" -> { it.writeFloat(1f); it.writeFloat(0f) }
                    "Down" -> { it.writeFloat(-1f); it.writeFloat(0f) }
                    "Left" -> { it.writeFloat(0f); it.writeFloat(-1f) }
                    else -> { it.writeFloat(0f); it.writeFloat(1f) }
                }
            }
            BindKind.PAD -> padKey(b.code, true)
            BindKind.TRIGGER -> { axes[if (b.code == "LT") 4 else 5] = 1f; sendAxes() }
        }
    }

    fun release(b: Binding) {
        when (b.kind) {
            BindKind.KEY -> key(b.code, false)
            BindKind.MOUSE -> mouseButton(b.code, false)
            BindKind.WHEEL -> {}
            BindKind.PAD -> padKey(b.code, false)
            BindKind.TRIGGER -> { axes[if (b.code == "LT") 4 else 5] = 0f; sendAxes() }
        }
    }

    /** dx/dy are in "PC pixels" (what the companion would move the PC mouse by). */
    fun mouseMove(dx: Float, dy: Float) {
        if (state != Connection.State.CONNECTED) return
        // A visible pointer should follow the finger, not move 3x as far. In auto mode the
        // service decides per event; pointer-sized steps are a fair middle ground there.
        val k = when (mouseMode) { 1 -> 1f; 2 -> density / 3f; else -> 1f }
        call(InjectorService.Cmd.MOVE) { it.writeFloat(dx * k); it.writeFloat(dy * k) }
    }

    fun stick(side: Int, x: Float, y: Float) {
        if (state != Connection.State.CONNECTED) return
        // Android joystick axes: +y is down.
        axes[side * 2] = x; axes[side * 2 + 1] = -y
        sendAxes()
    }

    val MOUSE_MODES = arrayOf("auto", "game", "pointer")

    /** Lets other files reach the package name without a Context. */
    object BuildInfo { const val PACKAGE = "com.padforge.app" }

    /** Opens Shizuku if installed, otherwise its Play Store page. */
    fun openShizuku(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (_: Exception) {}
    }
}
