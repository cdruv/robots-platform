package com.vadymsidorov.walky.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

data class TcpServerStatus(
    val port: Int? = null,
    /** The TLS listener for Robot Relay over Wi-Fi; null when there is none. */
    val wifiPort: Int? = null,
    val addresses: List<String> = emptyList(),
    /** Admitted clients on both listeners. */
    val clients: Int = 0,
    val error: String? = null,
    val wifiError: String? = null,
)

/**
 * The Wi-Fi listener and the trust behind it, provided by the app so this module never
 * sees TLS classes. Robot Relay learns [fingerprint] and hands over its key over USB.
 */
interface WifiAccess {
    /** A listener on all interfaces whose sockets are already encrypted. */
    fun listen(): ServerSocket
    /** SHA-256 of the certificate [listen] presents, lowercase hex. */
    val fingerprint: String
    fun key(): ByteArray?
    fun storeKey(key: ByteArray)
}

/**
 * Serves telemetry as JSON lines to any TCP client, e.g. `adb forward tcp:7777 tcp:7777`
 * then `nc localhost 7777` on the workstation. New clients first receive the last
 * [replayEvents] events. Each client has a bounded line queue; a client that cannot keep up
 * loses lines, which are counted as drops.
 *
 * Two listeners feed the same session, replay and client list:
 * - loopback [port], plain, for `adb forward`. Its hello also carries `"cert"`, the
 *   [WifiAccess.fingerprint], and it is the only listener that honours
 *   `{"link":"key","key":"<base64, 32 bytes>"}`, which stores the key Wi-Fi clients must present;
 * - with [wifi], [WifiAccess.listen] on all interfaces (TLS). The client speaks first:
 *   `{"link":"auth","key":"<base64>"}` within [AUTH_TIMEOUT_MS]. Until that matches the stored
 *   key nothing is sent to it and nothing it sends is acted on. A wrong or missing key gets
 *   `{"link":"auth","ok":false,"reason":"unpaired"|"denied"}` and the socket is closed. An
 *   admitted client that sends nothing for [WIFI_IDLE_MS] is dropped (Relay pings every 2 s).
 *
 * Lines that are not telemetry carry a `link` key and no `seq`: each admitted client first
 * gets `{"link":"hello","protocol":3,…,"session":"<uuid>","port":<the port it reached>}`
 * (fields from [hello]), and a client line `{"ping":n}` is answered with
 * `{"link":"pong","ping":n}`, queued behind pending telemetry. Every other JSON object a
 * client sends goes to [onLine], on that client's reader thread; [send] is the way back.
 */
class TcpServerSink(
    private val port: Int = DEFAULT_PORT,
    private val replayEvents: Int = 200,
    private val clientQueueLines: Int = 4096,
    private val hello: () -> JsonObject = { buildJsonObject {} },
    private val onLine: (JsonObject) -> Unit = {},
    private val wifi: WifiAccess? = null,
) : TelemetrySink {
    override val name = "tcp"

    private val current = MutableStateFlow(TcpServerStatus())
    val status: StateFlow<TcpServerStatus> = current.asStateFlow()

    private val dropped = AtomicLong(0)
    private val replay = ArrayDeque<String>(replayEvents)
    private val clients = CopyOnWriteArrayList<Client>()
    /** Wi-Fi sockets not admitted yet, so [close] can end them too. */
    private val unadmitted = ConcurrentHashMap.newKeySet<Socket>()
    /** Ends sockets still unadmitted [AUTH_TIMEOUT_MS] after accept, however slowly they send. */
    private val authDeadlines by lazy { Timer("walky-telemetry-auth-deadline", true) }
    private var server: ServerSocket? = null
    private var wifiServer: ServerSocket? = null
    private var wifiError: String? = null
    @Volatile private var closed = false
    private val session = UUID.randomUUID().toString()

    /** Binds the listening sockets. Called lazily by [write]; safe to call early. */
    @Synchronized
    fun start() {
        if (server != null || closed) return
        try {
            val socket = ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))
            server = socket
            thread(name = "walky-telemetry-accept", isDaemon = true) { acceptLoop(socket) }
        } catch (e: IOException) {
            publish(error = e.toString())
            return
        }
        if (wifi != null) {
            try {
                val socket = wifi.listen()
                wifiServer = socket
                thread(name = "walky-telemetry-accept-wifi", isDaemon = true) { acceptWifiLoop(socket) }
            } catch (e: Exception) {
                // Keystore and TLS setup failures surface here too; USB keeps working.
                wifiError = e.toString()
            }
        }
        publish()
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

    /** Queues [line] for the clients connected now. It is not telemetry: no `seq`, never replayed. */
    fun send(line: JsonObject) {
        if (closed) return
        val text = listOf(line.toString())
        clients.forEach { it.offer(text) }
    }

    override fun takeDropped(): Long = dropped.getAndSet(0)

    @Synchronized
    override fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { wifiServer?.close() }
        unadmitted.forEach { runCatching { it.close() } }
        if (wifiServer != null) authDeadlines.cancel()
        clients.forEach { it.close() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            admit(connection, BufferedInputStream(connection.getInputStream()), remote = false)
        }
    }

    private fun acceptWifiLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            unadmitted += connection
            if (closed) {
                runCatching { connection.close() }
                continue
            }
            // Throws once close() has cancelled the timer; the connection is closed with the rest.
            runCatching {
                authDeadlines.schedule(AUTH_TIMEOUT_MS.toLong()) {
                    if (connection in unadmitted) runCatching { connection.close() }
                }
            }
            // The TLS handshake runs on the first read, so it must not hold up the accept loop.
            thread(name = "walky-telemetry-auth", isDaemon = true) { authenticate(connection) }
        }
    }

    private fun authenticate(connection: Socket) {
        try {
            connection.soTimeout = AUTH_TIMEOUT_MS
            val input = BufferedInputStream(connection.getInputStream())
            val first = readClientLine(input) ?: return connection.close()
            val auth = runCatching { WalkyJson.parseToJsonElement(first) as? JsonObject }.getOrNull()
            val offered = auth?.takeIf { it.text("link") == "auth" }?.text("key")?.let(::decodeKey)
            val stored = wifi?.key()
            if (stored == null || offered == null || !MessageDigest.isEqual(stored, offered)) {
                val refusal = buildJsonObject {
                    put("link", "auth")
                    put("ok", false)
                    put("reason", if (stored == null) "unpaired" else "denied")
                }
                connection.getOutputStream().apply {
                    write((refusal.toString() + "\n").toByteArray(Charsets.UTF_8))
                    flush()
                }
                connection.close()
                return
            }
            connection.soTimeout = WIFI_IDLE_MS
            admit(connection, input, remote = true)
        } catch (_: IOException) {
            runCatching { connection.close() }
        } finally {
            unadmitted -= connection
        }
    }

    /** Joins [connection] to the stream: hello, then the replay, then live lines, none skipped or repeated. */
    private fun admit(connection: Socket, input: InputStream, remote: Boolean) {
        synchronized(this) {
            if (closed) {
                runCatching { connection.close() }
                return
            }
            val client = Client(connection, input, remote)
            client.offer(listOf(helloLine(remote)))
            client.offer(synchronized(replay) { replay.toList() })
            clients += client
            client.start()
            publish()
        }
    }

    private fun helloLine(remote: Boolean): String {
        val fields = runCatching(hello).getOrDefault(JsonObject(emptyMap()))
        return buildJsonObject {
            put("link", "hello")
            put("protocol", PROTOCOL)
            fields.forEach { (key, value) -> put(key, value) }
            put("session", session)
            put("port", (if (remote) wifiServer else server)?.localPort)
            if (!remote && wifi != null && wifiServer != null) put("cert", wifi.fingerprint)
        }.toString()
    }

    private fun publish(error: String? = null) {
        current.value = TcpServerStatus(
            port = server?.localPort,
            wifiPort = wifiServer?.localPort,
            addresses = localAddresses(),
            clients = clients.size,
            error = error,
            wifiError = wifiError,
        )
    }

    /** [remote]: admitted on the Wi-Fi listener. */
    private inner class Client(private val socket: Socket, private val input: InputStream, private val remote: Boolean) {
        private val queue = ArrayBlockingQueue<String>(clientQueueLines)

        fun offer(lines: List<String>) {
            for (line in lines) if (!queue.offer(line)) dropped.incrementAndGet()
        }

        fun start() {
            runCatching { socket.tcpNoDelay = true }
            thread(name = "walky-telemetry-writer", isDaemon = true) { writeLoop() }
            thread(name = "walky-telemetry-reader", isDaemon = true) { readUntilClosed() }
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

        /** A read returning EOF, or timing out on a Wi-Fi client, is the fastest disconnect signal. */
        private fun readUntilClosed() {
            try {
                while (true) answer(readClientLine(input) ?: break)
            } catch (_: IOException) {
            }
            close()
        }

        private fun answer(line: String) {
            val fields = runCatching { WalkyJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
            when {
                "ping" in fields -> {
                    val ping = (fields["ping"] as? JsonPrimitive)?.longOrNull ?: return
                    queue.offer(buildJsonObject {
                        put("link", "pong")
                        put("ping", ping)
                    }.toString())
                }
                fields.text("link") == "key" -> if (!remote) fields.text("key")?.let(::decodeKey)?.let { wifi?.storeKey(it) }
                fields.text("link") == "auth" -> {}
                else -> runCatching { onLine(fields) }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 7777
        const val PROTOCOL = 3
        const val MAX_CLIENT_LINE_BYTES = 1024
        const val KEY_BYTES = 32
        const val AUTH_TIMEOUT_MS = 5_000
        const val WIFI_IDLE_MS = 10_000

        /**
         * The next line, or null at EOF. Lines over [MAX_CLIENT_LINE_BYTES] are skipped whole.
         * Reads byte by byte from a buffered stream, so bytes after the line stay unread.
         */
        private fun readClientLine(input: InputStream): String? {
            val line = ByteArrayOutputStream(MAX_CLIENT_LINE_BYTES)
            var oversized = false
            while (true) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) {
                    if (!oversized) return line.toString(Charsets.UTF_8.name())
                    line.reset()
                    oversized = false
                } else if (line.size() < MAX_CLIENT_LINE_BYTES) {
                    line.write(byte)
                } else {
                    oversized = true
                }
            }
        }

        private fun decodeKey(text: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { it.size == KEY_BYTES }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

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
