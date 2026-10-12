package com.padforge.app

import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TCP link to the PC companion. Events are queued from the UI thread and written
 * by a background thread. Mouse movement and stick positions are coalesced so a
 * slow Wi-Fi moment never builds up a backlog of stale motion.
 */
object Connection {
    enum class State { OFF, CONNECTING, CONNECTED }

    const val DISCOVERY_PORT = 47801

    @Volatile var state = State.OFF
        private set
    @Volatile var serverName = ""
        private set
    @Volatile var latencyMs = -1L
        private set
    @Volatile var lastError = ""
        private set

    /** Called on the main thread whenever state or latency changes. */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Any>()
    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var generation = 0
    @Volatile private var denied = false
    @Volatile private var welcomed = false

    // Coalesced state (guarded by lock).
    private var accX = 0f
    private var accY = 0f
    private var mousePending = false
    private val sticks = floatArrayOf(0f, 0f, 0f, 0f)
    private val stickPending = booleanArrayOf(false, false)

    private object MouseToken
    private class StickToken(val index: Int)
    private val stickTokens = arrayOf(StickToken(0), StickToken(1))

    /** Learns the PC's Tailscale address from the companion, so away-from-home just works. */
    @Volatile private var store: Store? = null

    /** Connect using the saved home address and Tailscale address, whichever answers. */
    fun start(store: Store) {
        this.store = store
        start(listOf(store.host, store.remoteHost), store.port, store.pairingCode)
    }

    fun start(host: String, port: Int, code: String) = start(listOf(host), port, code)

    fun start(hosts: List<String>, port: Int, code: String) {
        stop()
        val candidates = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (candidates.isEmpty()) {
            lastError = "No PC address set"
            notifyListener()
            return
        }
        if (code.isBlank()) {
            lastError = "Enter the pairing code shown on your PC"
            notifyListener()
            return
        }
        val gen = ++generation
        worker = Thread({ runLoop(gen, candidates, port, Store.normalizeCode(code)) }, "padforge-net").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        // The companion releases everything this phone held as soon as the socket closes.
        generation++
        worker?.interrupt()
        worker = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        queue.clear()
        setState(State.OFF)
    }

    fun send(line: String) {
        if (state == State.CONNECTED) queue.offer(line)
    }

    fun mouseMove(dx: Float, dy: Float) {
        if (state != State.CONNECTED) return
        synchronized(lock) {
            accX += dx; accY += dy
            if (!mousePending) { mousePending = true; queue.offer(MouseToken) }
        }
    }

    /** side 0 = left, 1 = right; x/y in -1..1 with +y up. */
    fun stick(side: Int, x: Float, y: Float) {
        if (state != State.CONNECTED) return
        synchronized(lock) {
            sticks[side * 2] = x; sticks[side * 2 + 1] = y
            if (!stickPending[side]) { stickPending[side] = true; queue.offer(stickTokens[side]) }
        }
    }

    private enum class Result { FAILED, ENDED, DENIED, STOPPED }

    /** Index into the candidate list of the address that worked last, tried first next time. */
    @Volatile private var preferred = 0

    private fun runLoop(gen: Int, hosts: List<String>, port: Int, code: String) {
        preferred = preferred.coerceIn(0, hosts.size - 1)
        while (gen == generation) {
            setState(State.CONNECTING)
            val order = listOf(preferred) + hosts.indices.filter { it != preferred }
            val failures = LinkedHashMap<String, String>()
            for (i in order) {
                val host = hosts[i]
                // A home address either answers fast or not at all; Tailscale may need a moment.
                val timeout = if (isTailscale(host) || hosts.size == 1) 6000 else 2500
                when (session(gen, host, port, code, timeout) { msg -> failures[host] = msg }) {
                    Result.STOPPED -> return
                    Result.DENIED -> {
                        generation++   // the same wrong code would just be refused again
                        setState(State.OFF)
                        return
                    }
                    Result.ENDED -> { preferred = i; break }   // was connected; start over from this address
                    Result.FAILED -> {}
                }
            }
            if (gen != generation) return
            if (failures.size == hosts.size) lastError = diagnose(hosts, failures, port)
            latencyMs = -1
            setState(State.CONNECTING)
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return }
        }
    }

    /** One connection attempt. Returns once it fails, is refused, or (after connecting) drops. */
    private fun session(gen: Int, host: String, port: Int, code: String, timeoutMs: Int,
                        onFail: (String) -> Unit): Result {
        val s = Socket()
        var connected = false
        try {
            s.tcpNoDelay = true
            denied = false
            welcomed = false
            s.connect(InetSocketAddress(host, port), timeoutMs)
            socket = s
            val out = BufferedOutputStream(s.getOutputStream())
            out.write("HELLO 2 $code\n".toByteArray()); out.flush()
            startReader(s, gen)
            // Wait for the PC to accept the pairing code before reporting "connected".
            val deadline = System.currentTimeMillis() + 6000
            while (!welcomed && !denied && gen == generation && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            if (gen != generation) return Result.STOPPED
            if (denied) return Result.DENIED
            if (!welcomed) throw SocketTimeoutException()
            synchronized(lock) {
                queue.clear(); accX = 0f; accY = 0f; mousePending = false
                stickPending[0] = false; stickPending[1] = false
            }
            lastError = ""
            connectedVia = host
            connected = true
            setState(State.CONNECTED)
            var lastPing = 0L
            while (gen == generation) {
                val now = System.currentTimeMillis()
                if (now - lastPing >= 1000) {
                    out.write("PING $now\n".toByteArray()); out.flush()
                    lastPing = now
                }
                val first = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                write(out, first)
                while (true) write(out, queue.poll() ?: break)
                out.flush()
            }
            return Result.STOPPED
        } catch (e: InterruptedException) {
            return Result.STOPPED
        } catch (e: Exception) {
            if (gen != generation) return Result.STOPPED
            if (denied) return Result.DENIED
            if (connected) {
                lastError = "Connection to your PC dropped"
                return Result.ENDED
            }
            onFail(when (e) {
                is SocketTimeoutException -> "no answer"
                is java.net.ConnectException -> e.message ?: "refused"
                else -> e.message ?: e.javaClass.simpleName
            })
            return Result.FAILED
        } finally {
            try { s.close() } catch (_: Exception) {}
            if (socket === s) socket = null
        }
    }

    /** Turns "every address failed" into advice the user can act on. */
    private fun diagnose(hosts: List<String>, failures: Map<String, String>, port: Int): String {
        val remote = hosts.firstOrNull { isTailscale(it) }
        val home = hosts.firstOrNull { !isTailscale(it) }
        return when {
            remote == null ->
                "Can't reach your PC at $home. Away from home? Set up Tailscale on the PC and this phone, " +
                    "then connect once at home so PadForge learns the PC's Tailscale address."
            !phoneOnTailscale() ->
                "Can't reach your PC" + (if (home != null) " at $home" else "") +
                    ". Away from home? Turn on Tailscale on this phone."
            else ->
                "Tailscale is on, but your PC isn't answering at $remote (${failures[remote]}). " +
                    "Check the PC is awake with Tailscale and PadForge running, " +
                    "and run setup_firewall.bat on it once."
        }
    }

    /** Which address the current connection uses ("" when not connected). */
    @Volatile var connectedVia = ""
        private set

    fun isTailscale(host: String): Boolean {
        val p = host.split(".").mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }

    /** True if this phone currently has a Tailscale (100.64.0.0/10) address, i.e. Tailscale is on. */
    fun phoneOnTailscale(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            ni.isUp && ni.inetAddresses.toList().any { a -> isTailscale(a.hostAddress ?: "") }
        }
    } catch (_: Exception) { false }

    private fun write(out: BufferedOutputStream, item: Any) {
        val line: String? = when (item) {
            is String -> item
            MouseToken -> synchronized(lock) {
                val ix = accX.toInt(); val iy = accY.toInt()
                accX -= ix; accY -= iy
                mousePending = false
                if (ix != 0 || iy != 0) "MM $ix $iy" else null
            }
            is StickToken -> synchronized(lock) {
                stickPending[item.index] = false
                String.format(Locale.US, "GS %s %.3f %.3f",
                    if (item.index == 0) "L" else "R", sticks[item.index * 2], sticks[item.index * 2 + 1])
            }
            else -> null
        }
        if (line != null) out.write((line + "\n").toByteArray())
    }

    private fun startReader(s: Socket, gen: Int) {
        Thread({
            try {
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (gen == generation) {
                    val line = r.readLine() ?: break
                    val p = line.trim().split(" ")
                    when (p[0]) {
                        "WELCOME" -> {
                            serverName = p.getOrElse(2) { "PC" }
                            // Newer companions add "ts=<Tailscale address>"; remember it for away-from-home.
                            p.firstOrNull { it.startsWith("ts=") }?.substring(3)?.let { ts ->
                                if (isTailscale(ts)) store?.remoteHost = ts
                            }
                            welcomed = true
                            notifyListener()
                        }
                        "DENIED" -> {
                            denied = true
                            lastError = if (p.getOrNull(1) == "old-app") "Update the PC companion"
                                else "Wrong pairing code (check the PC companion window)"
                        }
                        "PONG" -> p.getOrNull(1)?.toLongOrNull()?.let {
                            latencyMs = System.currentTimeMillis() - it
                            notifyListener()
                        }
                    }
                }
            } catch (_: Exception) {
            }
            // Reader ended: make the writer notice by closing the socket.
            try { s.close() } catch (_: Exception) {}
        }, "padforge-reader").apply { isDaemon = true; start() }
    }

    private fun setState(s: State) {
        state = s
        notifyListener()
    }

    private fun notifyListener() {
        main.post { listener?.invoke() }
    }

    fun statusText(): String = when (state) {
        State.OFF -> if (lastError.isNotEmpty()) lastError else "Not connected"
        State.CONNECTING -> if (lastError.isNotEmpty()) "$lastError (retrying…)" else "Connecting…"
        State.CONNECTED -> "Connected to $serverName" +
            (if (isTailscale(connectedVia)) " via Tailscale" else "") +
            if (latencyMs >= 0) " · ${latencyMs} ms" else ""
    }

    data class Found(val address: String, val port: Int, val name: String, val tailscale: String = "")

    /** Broadcasts on the local network and collects companions that answer. Blocking. */
    fun discover(timeoutMs: Int = 1500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 200
            val msg = "PADFORGE_DISCOVER".toByteArray()
            val targets = mutableSetOf<InetAddress>()
            try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    if (ni.isUp && !ni.isLoopback) ni.interfaceAddresses.forEach { ia ->
                        ia.broadcast?.let { targets.add(it) }
                    }
                }
            } catch (_: Exception) {}
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (t in targets) try {
                        sock.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT))
                    } catch (_: Exception) {}
                    nextSend = System.currentTimeMillis() + 500
                }
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val p = String(pkt.data, 0, pkt.length).trim().split(" ")
                    if (p.size >= 2 && p[0] == "PADFORGE_SERVER") {
                        val addr = pkt.address.hostAddress ?: continue
                        val ts = p.getOrElse(3) { "" }.takeIf { isTailscale(it) } ?: ""
                        found[addr] = Found(addr, p[1].toIntOrNull() ?: 47800, p.getOrElse(2) { addr }, ts)
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return found.values.toList()
    }
}

/** Turns bindings into protocol lines. */
object Output {
    fun press(b: Binding) {
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
        when (b.kind) {
            BindKind.KEY -> Connection.send("K ${b.code} 0")
            BindKind.MOUSE -> Connection.send("MB ${b.code} 0")
            BindKind.WHEEL -> {}
            BindKind.PAD -> Connection.send("GB ${b.code} 0")
            BindKind.TRIGGER -> Connection.send("GT ${b.code[0]} 0")
        }
    }
}
