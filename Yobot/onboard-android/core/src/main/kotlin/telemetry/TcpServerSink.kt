package com.vadymsidorov.yobot.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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
 */
class TcpServerSink(
    private val port: Int = DEFAULT_PORT,
    private val replayEvents: Int = 200,
    private val clientQueueLines: Int = 4096,
) : TelemetrySink {
    override val name = "tcp"

    private val current = MutableStateFlow(TcpServerStatus())
    val status: StateFlow<TcpServerStatus> = current.asStateFlow()

    private val dropped = AtomicLong(0)
    private val replay = ArrayDeque<String>(replayEvents)
    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var closed = false

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
                client.offer(synchronized(replay) { replay.toList() })
                clients += client
                client.start()
                publish()
            }
        }
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

        /** Clients send nothing; a read returning EOF is the fastest disconnect signal. */
        private fun readUntilClosed() {
            try {
                val input = socket.getInputStream()
                while (input.read() >= 0) Unit
            } catch (_: IOException) {
            }
            close()
        }
    }

    companion object {
        const val DEFAULT_PORT = 7777

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
