package com.vadymsidorov.yobot.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class TcpServerStatus(
    val port: Int? = null,
    val addresses: List<String> = emptyList(),
    val clients: Int = 0,
    val error: String? = null,
)

/**
 * Serves telemetry as JSON lines to any TCP client, e.g. `adb forward tcp:7777 tcp:7777`
 * then `nc localhost 7777` on the workstation, or `nc <phone-ip> 7777` over Wi-Fi.
 * New clients first receive the last [replayEvents] events. Each client has a bounded
 * line queue; a client that cannot keep up loses lines, which are counted as drops.
 *
 * Lines that concern one client only carry a `link` key and no `seq`: each client first
 * gets `{"link":"hello","protocol":1,…,"session":"<uuid>","port":7777}` (fields from [hello]),
 * and a client line `{"ping":n}` is answered with `{"link":"pong","ping":n}`, queued behind
 * pending telemetry. `Robot Relay/scripts/relay-phone.sh` wraps all of this for the terminal.
 */
class TcpServerSink(
    private val port: Int = DEFAULT_PORT,
    private val replayEvents: Int = 200,
    private val clientQueueLines: Int = 4096,
    private val hello: () -> JsonObject = { buildJsonObject {} },
) : TelemetrySink {
    override val name = "tcp"

    private val current = MutableStateFlow(TcpServerStatus())
    val status: StateFlow<TcpServerStatus> = current.asStateFlow()

    private val dropped = AtomicLong(0)
    private val replay = ArrayDeque<String>(replayEvents)
    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var closed = false
    private val session = UUID.randomUUID().toString()

    /** Binds the listening socket. Called lazily by [write]; safe to call early. */
    @Synchronized
    fun start() {
        if (server != null || closed) return
        try {
            val socket = ServerSocket(port)
            server = socket
            thread(name = "yobot-telemetry-accept", isDaemon = true) { acceptLoop(socket) }
            publish()
        } catch (e: IOException) {
            publish(error = e.toString())
        }
    }

    @Synchronized
    override fun write(batch: List<TelemetryEvent>) {
        if (closed) return
        start()
        if (batch.isEmpty()) return
        val lines = batch.map { it.toJsonLine() }
        synchronized(replay) {
            lines.forEach { replay.addLast(it) }
            while (replay.size > replayEvents) replay.removeFirst()
        }
        clients.forEach { it.offer(lines) }
    }

    override fun takeDropped(): Long = dropped.getAndSet(0)

    @Synchronized
    override fun close() {
        closed = true
        runCatching { server?.close() }
        clients.forEach { it.close() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            synchronized(this) {
                if (closed) {
                    connection.close()
                    return
                }
                val client = Client(connection)
                client.offer(listOf(helloLine()))
                client.offer(synchronized(replay) { replay.toList() })
                clients += client
                client.start()
                publish()
            }
        }
    }

    private fun helloLine(): String {
        val fields = runCatching(hello).getOrDefault(JsonObject(emptyMap()))
        return buildJsonObject {
            put("link", "hello")
            put("protocol", PROTOCOL)
            fields.forEach { (key, value) -> put(key, value) }
            put("session", session)
            put("port", server?.localPort)
        }.toString()
    }

    private fun publish(error: String? = null) {
        current.value = TcpServerStatus(
            port = server?.localPort,
            addresses = localAddresses(),
            clients = clients.size,
            error = error,
        )
    }

    private inner class Client(private val socket: Socket) {
        private val queue = ArrayBlockingQueue<String>(clientQueueLines)

        fun offer(lines: List<String>) {
            for (line in lines) if (!queue.offer(line)) dropped.incrementAndGet()
        }

        fun start() {
            runCatching { socket.tcpNoDelay = true }
            thread(name = "yobot-telemetry-writer", isDaemon = true) { writeLoop() }
            thread(name = "yobot-telemetry-reader", isDaemon = true) { readUntilClosed() }
        }

        fun close() {
            runCatching { socket.close() }
        }

        private fun writeLoop() {
            val pending = ArrayList<String>(256)
            try {
                val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                while (!socket.isClosed) {
                    val first = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    pending += first
                    queue.drainTo(pending, 255)
                    for (line in pending) {
                        out.write(line)
                        out.write("\n")
                    }
                    pending.clear()
                    out.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                close()
                clients -= this
                publish()
            }
        }

        /**
         * Answers `{"ping":n}` lines and ignores anything else, including lines over
         * [MAX_CLIENT_LINE_BYTES]. A read returning EOF is the fastest disconnect signal.
         */
        private fun readUntilClosed() {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val line = ByteArrayOutputStream(MAX_CLIENT_LINE_BYTES)
                var oversized = false
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    if (byte == '\n'.code) {
                        if (!oversized) answer(line.toString(Charsets.UTF_8.name()))
                        line.reset()
                        oversized = false
                    } else if (line.size() < MAX_CLIENT_LINE_BYTES) {
                        line.write(byte)
                    } else {
                        oversized = true
                    }
                }
            } catch (_: IOException) {
            }
            close()
        }

        private fun answer(line: String) {
            val ping = runCatching {
                (YobotJson.parseToJsonElement(line).jsonObject["ping"] as? JsonPrimitive)?.longOrNull
            }.getOrNull() ?: return
            queue.offer(buildJsonObject {
                put("link", "pong")
                put("ping", ping)
            }.toString())
        }
    }

    companion object {
        const val DEFAULT_PORT = 7777
        const val PROTOCOL = 1
        const val MAX_CLIENT_LINE_BYTES = 1024

        /** Non-loopback IPv4 addresses of interfaces that are up, e.g. the Wi-Fi address. */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
