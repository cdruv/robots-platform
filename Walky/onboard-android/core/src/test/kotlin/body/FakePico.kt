package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.telemetry.WalkyJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A Pico on loopback. State lines are written the way MicroPython's json.dumps writes them.
 * With [heartbeat] it sends state like the firmware: 10 times a second and after every line
 * that is not a leg target. Without it, only when the test calls [state].
 */
class FakePico(private val heartbeat: Boolean = true) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val lines = LinkedBlockingQueue<JsonObject>()
    @Volatile private var client: Socket? = null
    @Volatile var connections = 0
        private set
    @Volatile var echo: Long? = null
    private val started = System.nanoTime()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                client = socket
                connections++
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    fun connect(): Socket = Socket(server.inetAddress, server.localPort)

    private fun serve(socket: Socket) {
        if (heartbeat) thread(isDaemon = true) {
            while (!socket.isClosed) {
                state()
                Thread.sleep(100)
            }
        }
        runCatching {
            socket.getInputStream().bufferedReader().forEachLine { text ->
                val line = WalkyJson.parseToJsonElement(text).jsonObject
                echo = line["n"]?.toString()?.toLong()
                lines += line
                if (heartbeat && line.type != "legs") state()
            }
        }
    }

    fun state(mode: String = "limp", trim: String = "[0, 0]", saved: String = "[0, 0]", stored: Boolean = false,
              error: String = "null", version: Int = 1) {
        // up_ms moves with every line, as on the board, so consecutive states are never equal.
        write("""{"t": "state", "v": $version, "board": "e66118604b", "mode": "$mode", "target": [12.5, -30], """ +
            """"pulses": ${if (mode == "active") "[1638, 1830]" else "null"}, "trim": $trim, "saved": $saved, """ +
            """"stored": $stored, "slew": 360, "n": $echo, "error": $error, "up_ms": ${(System.nanoTime() - started) / 1_000_000}}""")
    }

    fun write(line: String) {
        val socket = client ?: return
        runCatching {
            synchronized(socket) {
                socket.getOutputStream().write((line + "\n").toByteArray())
                socket.getOutputStream().flush()
            }
        }
    }

    /** The next line that is not a ping, or null after [timeoutMs]. */
    fun next(timeoutMs: Long = 3_000): JsonObject? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: return null
            if (line.type != "ping") return line
        }
    }

    fun dropClient() {
        client?.close()
    }

    override fun close() {
        server.close()
        dropClient()
    }

    private val JsonObject.type get() = this["t"]?.toString()?.trim('"')
}

fun <T> eventually(timeoutMs: Long = 3_000, what: String = "condition", probe: () -> T?): T {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        probe()?.let { if (it != false) return it }
        if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for $what")
        Thread.sleep(10)
    }
}
