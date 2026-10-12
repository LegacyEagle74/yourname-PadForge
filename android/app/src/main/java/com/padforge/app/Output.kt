package com.padforge.app

import android.content.Context

/**
 * Where control presses go: the PC companion over the network ([Connection]) or
 * straight into the app on this phone via Shizuku ([LocalInjector]).
 */
object Output {
    @Volatile var local = false
        private set

    /** Status listener for whichever output is active (called on the main thread). */
    var listener: (() -> Unit)?
        get() = if (local) LocalInjector.listener else Connection.listener
        set(v) { Connection.listener = v; LocalInjector.listener = v }

    val state: Connection.State get() = if (local) LocalInjector.state else Connection.state
    fun statusText(): String = if (local) LocalInjector.statusText() else Connection.statusText()

    fun start(ctx: Context, store: Store) {
        stop()
        local = store.phoneMode
        if (local) LocalInjector.start(ctx, store.mouseMode) else Connection.start(store)
    }

    fun stop() {
        Connection.stop()
        LocalInjector.stop()
    }

    fun press(b: Binding) {
        if (local) { LocalInjector.press(b); return }
        when (b.kind) {
            BindKind.KEY -> Connection.send("K ${b.code} 1")
            BindKind.MOUSE -> Connection.send("MB ${b.code} 1")
            BindKind.WHEEL -> Connection.send(when (b.code) {
                "Up" -> "MW 1"; "Down" -> "MW -1"; "Left" -> "MH -1"; else -> "MH 1"
            })
            BindKind.PAD -> Connection.send("GB ${b.code} 1")
            BindKind.TRIGGER -> Connection.send("GT ${b.code[0]} 1")
        }
    }

    fun release(b: Binding) {
        if (local) { LocalInjector.release(b); return }
        when (b.kind) {
            BindKind.KEY -> Connection.send("K ${b.code} 0")
            BindKind.MOUSE -> Connection.send("MB ${b.code} 0")
            BindKind.WHEEL -> {}
            BindKind.PAD -> Connection.send("GB ${b.code} 0")
            BindKind.TRIGGER -> Connection.send("GT ${b.code[0]} 0")
        }
    }

    /** Relative mouse movement in "PC pixels". */
    fun mouseMove(dx: Float, dy: Float) {
        if (local) LocalInjector.mouseMove(dx, dy) else Connection.mouseMove(dx, dy)
    }

    /** side 0 = left, 1 = right; x/y in -1..1 with +y up. */
    fun stick(side: Int, x: Float, y: Float) {
        if (local) LocalInjector.stick(side, x, y) else Connection.stick(side, x, y)
    }
}
