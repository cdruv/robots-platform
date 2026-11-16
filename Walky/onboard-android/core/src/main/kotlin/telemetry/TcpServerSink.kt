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
import com.vadymsidorov.walky.core.telemetry.mcap.Mcap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
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
 * Serves telemetry live to TCP clients, e.g. `adb forward tcp:7777 tcp:7777` then
 * `nc localhost 7777 | mcap cat --json` on the workstation. Live is best-effort; the
 * [Recorder] keeps the lossless copy.
 *
 * Two listeners feed the same session, replay and client list:
 * - loopback [port], plain, for `adb forward`. Its hello also carries `cert`, the
 *   [WifiAccess.fingerprint], and it is the only listener that honours
 *   `{"link":"key","key":"<base64, 32 bytes>"}`, which stores the key Wi-Fi clients must present;
 * - with [wifi], [WifiAccess.listen] on all interfaces (TLS). The client speaks first:
 *   `{"link":"auth","key":"<base64>"}` within [AUTH_TIMEOUT_MS]. Until that matches the stored
 *   key nothing is sent to it and nothing it sends is acted on. A wrong or missing key gets the
 *   JSON line `{"link":"auth","ok":false,"reason":"unpaired"|"denied"}` and the socket is
 *   closed. An admitted client that sends nothing for [WIFI_IDLE_MS] is dropped (Relay pings
 *   every 2 s).
 *
 * Protocol 4. Clients send JSON lines (at most [MAX_CLIENT_LINE_BYTES]). An admitted client
 * receives an unindexed MCAP stream: magic, Header, a Metadata record `hello` (`protocol`,
 * `session`, `port`, `cert` on USB only, and the fields from [hello]), every Schema and
 * Channel of [catalog], then the replay (the last [replayEvents] `/events` messages and the
 * latest message of each latched topic, by channel id), then live messages, none skipped or
 * repeated. `{"ping":n}` is answered with `{"ping":n}` on `/link/pong`, to that client only and
 * queued behind pending messages. Every other JSON object a client sends goes to [onLine], on
 * that client's reader thread.
 *
 * Each client has a queue of [clientQueueBytes]. Over that, queued video goes first and the
 * client skips video until the next keyframe; then the oldest messages go. A client starts out
 * skipping video too, and each skip asks for a keyframe through [requestKeyframe]. Every
 * message lost is counted as a drop.
 */
class TcpServerSink(
    private val port: Int = DEFAULT_PORT,
    private val replayEvents: Int = 200,
    private val clientQueueBytes: Long = 8L shl 20,
    private val hello: () -> Map<String, String> = { emptyMap() },
    private val onLine: (JsonObject) -> Unit = {},
    private val wifi: WifiAccess? = null,
    private val catalog: Catalog = Topics.catalog,
    private val session: String = UUID.randomUUID().toString(),
    private val requestKeyframe: () -> Unit = {},
    private val monoNs: () -> Long = System::nanoTime,
) : StreamSink {
    override val name = "tcp"

    private val current = MutableStateFlow(TcpServerStatus())
    val status: StateFlow<TcpServerStatus> = current.asStateFlow()

    private val dropped = AtomicLong(0)
    private val pongChannel = catalog.id(Topics.Pong)
    /** Guards the replay and the client list together, so a joining client misses nothing. */
    private val stream = Any()
    private val replay = ArrayDeque<StreamRecord>(replayEvents)
    private val latched = TreeMap<Int, StreamRecord>()
    private val clients = CopyOnWriteArrayList<Client>()
    /** Wi-Fi sockets not admitted yet, so [close] can end them too. */
    private val unadmitted = ConcurrentHashMap.newKeySet<Socket>()
    /** Ends sockets still unadmitted [AUTH_TIMEOUT_MS] after accept, however slowly they send. */
    private val authDeadlines by lazy { Timer("walky-telemetry-auth-deadline", true) }
    private var server: ServerSocket? = null
    private var wifiServer: ServerSocket? = null
    private var wifiError: String? = null
    @Volatile private var started = false
    @Volatile private var closed = false

    /** Binds the listening sockets. Called lazily by [write]; safe to call early. */
    @Synchronized
    fun start() {
        if (started || closed) return
        started = true
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

    override fun write(record: StreamRecord) {
        if (closed) return
        if (!started) start()
        synchronized(stream) {
            when {
                record.topic === Topics.Events -> {
                    replay.addLast(record)
                    while (replay.size > replayEvents) replay.removeFirst()
                }
                record.topic.latched -> latched[record.channelId] = record
            }
            clients.forEach { it.offer(record) }
        }
    }

    override fun takeDropped(): Long = dropped.getAndSet(0)

    @Synchronized
    fun close() {
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

    /** Joins [connection] to the stream: preamble, then the replay, then live messages, none skipped or repeated. */
    private fun admit(connection: Socket, input: InputStream, remote: Boolean) {
        val client = synchronized(this) {
            if (closed) {
                runCatching { connection.close() }
                return
            }
            val client = Client(connection, input, remote)
            client.offerPreamble(preamble(remote))
            synchronized(stream) {
                replay.forEach(client::offer)
                latched.values.forEach(client::offer)
                clients += client
            }
            client
        }
        client.start()
        publish()
        runCatching(requestKeyframe)
    }

    private fun preamble(remote: Boolean): ByteArray {
        val fields = runCatching(hello).getOrDefault(emptyMap())
        val hello = LinkedHashMap<String, String>()
        hello["protocol"] = PROTOCOL.toString()
        hello.putAll(fields)
        hello["session"] = session
        (if (remote) wifiServer else server)?.localPort?.let { hello["port"] = it.toString() }
        if (!remote && wifi != null && wifiServer != null) hello["cert"] = wifi.fingerprint
        val out = ByteArrayOutputStream()
        out.write(Mcap.MAGIC)
        out.write(Mcap.header(profile = "", library = "walky"))
        out.write(Mcap.metadata("hello", hello))
        catalog.schemaRecords.forEach(out::write)
        catalog.channelRecords.forEach(out::write)
        return out.toByteArray()
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
        private val lock = Object()
        private val queue = ArrayDeque<Outgoing>()
        private var queuedBytes = 0L
        /** Video is skipped until a keyframe: at the start, and after video was dropped. */
        private var skippingVideo = true
        /** Only frames skipped after a drop are lost; before the first keyframe there was nothing to show. */
        private var sentVideo = false

        /** Never dropped, and not counted against the budget. */
        fun offerPreamble(bytes: ByteArray) = synchronized(lock) {
            queue.addLast(Outgoing(bytes, video = false, droppable = false))
        }

        fun offer(record: StreamRecord) {
            var wantKeyframe = false
            synchronized(lock) {
                val video = record.topic.isVideo
                if (video && skippingVideo) {
                    if (!record.keyframe) {
                        if (sentVideo) dropped.incrementAndGet()
                        return
                    }
                    skippingVideo = false
                }
                if (video) sentVideo = true
                queue.addLast(Outgoing(record.bytes, video))
                queuedBytes += record.bytes.size
                while (queuedBytes > clientQueueBytes) {
                    if (queue.any { it.video }) {
                        wantKeyframe = true
                        skippingVideo = true
                        val before = queue.size
                        queue.removeAll { it.video && it.discard() }
                        dropped.addAndGet((before - queue.size).toLong())
                    } else {
                        val oldest = queue.firstOrNull { it.droppable } ?: break
                        queue.remove(oldest)
                        oldest.discard()
                        dropped.incrementAndGet()
                    }
                }
                lock.notifyAll()
            }
            if (wantKeyframe) runCatching(requestKeyframe)
        }

        private fun Outgoing.discard(): Boolean {
            queuedBytes -= bytes.size
            return true
        }

        fun start() {
            runCatching { socket.tcpNoDelay = true }
            thread(name = "walky-telemetry-writer", isDaemon = true) { writeLoop() }
            thread(name = "walky-telemetry-reader", isDaemon = true) { readUntilClosed() }
        }

        fun close() {
            runCatching { socket.close() }
            synchronized(lock) { lock.notifyAll() }
        }

        private fun writeLoop() {
            val pending = ArrayList<Outgoing>(256)
            try {
                val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)
                while (!socket.isClosed) {
                    synchronized(lock) {
                        if (queue.isEmpty()) lock.wait(500)
                        pending.addAll(queue)
                        queue.clear()
                        queuedBytes = 0
                    }
                    if (pending.isEmpty()) continue
                    pending.forEach { out.write(it.bytes) }
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
                    val now = monoNs()
                    val data = WalkyJson.encodeToString(PongMessage.serializer(), PongMessage(ping)).toByteArray(Charsets.UTF_8)
                    offer(StreamRecord(pongChannel, Topics.Pong, now, Mcap.message(pongChannel, ping and 0xFFFF_FFFFL, now, now, data)))
                }
                fields.text("link") == "key" -> if (!remote) fields.text("key")?.let(::decodeKey)?.let { wifi?.storeKey(it) }
                fields.text("link") == "auth" -> {}
                else -> runCatching { onLine(fields) }
            }
        }
    }

    private class Outgoing(val bytes: ByteArray, val video: Boolean, val droppable: Boolean = true)

    companion object {
        const val DEFAULT_PORT = 7777
        const val PROTOCOL = 4
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
