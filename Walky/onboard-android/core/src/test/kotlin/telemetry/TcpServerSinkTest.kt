package com.vadymsidorov.walky.core.telemetry

import com.vadymsidorov.walky.core.telemetry.mcap.Mcap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TcpServerSinkTest {
    private val catalog = Topics.catalog
    private val eventsId = catalog.id(Topics.Events)

    private fun event(seq: Long) = TelemetryEvent(seq, 1_000 + seq, seq, "test", "k$seq", JsonPrimitive(seq))

    private fun eventRecord(seq: Long) = StreamRecord(
        eventsId, Topics.Events, seq,
        Mcap.message(eventsId, seq, seq, seq, WalkyJson.encodeToString(TelemetryEvent.serializer(), event(seq)).toByteArray()),
    )

    private fun <T> record(topic: Topic<T>, sequence: Long, value: T): StreamRecord {
        val id = catalog.id(topic)
        return StreamRecord(id, topic, sequence, Mcap.message(id, sequence, sequence, sequence, topic.encode(value)), topic.keyframe?.invoke(value) ?: false)
    }

    private fun Message.event() = WalkyJson.decodeFromString(TelemetryEvent.serializer(), text)

    /** A client of the MCAP stream. */
    private inner class Client(val socket: Socket) : AutoCloseable {
        val stream by lazy { McapStream(socket.getInputStream()) }
        lateinit var hello: Map<String, String>

        /** Header, the hello Metadata, then a Schema and a Channel for every topic. */
        fun readPreamble(): Client {
            val header = stream.next()
            assertEquals(0x01, header.opcode)
            val metadata = stream.next()
            assertEquals(0x0C, metadata.opcode)
            val r = metadata.reader()
            assertEquals("hello", r.string())
            hello = r.stringMap()
            val schemas = List(catalog.topics.size) { stream.next() }
            assertEquals(List(catalog.topics.size) { 0x03 }, schemas.map { it.opcode })
            val channels = List(catalog.topics.size) { stream.next() }
            assertEquals(List(catalog.topics.size) { 0x04 }, channels.map { it.opcode })
            val topics = channels.map { it.reader().run { u16(); u16(); string() } }
            assertEquals(catalog.topics.map { it.name }, topics)
            return this
        }

        fun message(): Message = stream.next().message()

        fun send(line: String) {
            socket.getOutputStream().apply {
                write((line + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
        }

        fun assertNothingMore() {
            socket.soTimeout = 200
            assertTrue(runCatching { socket.getInputStream().read() }.exceptionOrNull() is SocketTimeoutException)
            socket.soTimeout = 5_000
        }

        override fun close() = socket.close()
    }

    private fun connect(port: Int) = Client(Socket("127.0.0.1", port).apply { soTimeout = 5_000 })

    private fun awaitClients(sink: TcpServerSink, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.status.value.clients != count && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(count, sink.status.value.clients)
    }

    @Test
    fun streamsMcapAndReplaysRecentEventsToNewClients() {
        val sink = TcpServerSink(port = 0, replayEvents = 2)
        try {
            listOf(1L, 2L, 3L).forEach { sink.write(eventRecord(it)) }
            val port = sink.status.value.port
            assertNotNull(port)

            val client = connect(port!!).readPreamble()
            client.use {
                awaitClients(sink, 1)
                assertEquals(event(2), client.message().event())
                assertEquals(event(3), client.message().event())
                sink.write(eventRecord(4))
                val live = client.message()
                assertEquals(event(4), live.event())
                assertEquals(eventsId to 4L, live.channelId to live.logTime)
            }
            awaitClients(sink, 0)
            assertEquals(0L, sink.takeDropped())
        } finally {
            sink.close()
        }
    }

    @Test
    fun preambleThenReplayedEventsThenTheLatestOfEachLatchedTopic() {
        val sink = TcpServerSink(port = 0, hello = { mapOf("device" to "Test Phone") }, session = "s-1")
        try {
            sink.write(record(Topics.BodyLink, 1, BodyLinkMessage("connecting")))
            sink.write(eventRecord(1))
            sink.write(record(Topics.BodyLink, 2, BodyLinkMessage("connected", rttMs = 12)))
            sink.write(record(Topics.ImuAccel, 1, ImuBatch(3, longArrayOf(1), floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(9.8f))))
            val port = sink.status.value.port!!
            connect(port).use { client ->
                client.readPreamble()
                assertEquals(
                    mapOf("protocol" to "4", "device" to "Test Phone", "session" to "s-1", "port" to port.toString()),
                    client.hello,
                )
                assertEquals(event(1), client.message().event())
                val latched = client.message()
                assertEquals(catalog.id(Topics.BodyLink), latched.channelId)
                assertEquals("""{"phase":"connected","rttMs":12}""", latched.text)
                // The IMU batch is neither an event nor latched.
                client.assertNothingMore()
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun closingSessionDisconnectsClientsAndAllowsFreshSessionOnSamePort() {
        val first = TcpServerSink(port = 0)
        first.start()
        val port = first.status.value.port!!
        val client = connect(port).readPreamble()
        try {
            awaitClients(first, 1)
            first.close()
            assertNull(client.stream.nextOrNull())
            // A late telemetry write must not reopen the closed session.
            first.write(eventRecord(1))
            val next = TcpServerSink(port = port)
            try {
                next.start()
                assertEquals(null, next.status.value.error)
                connect(port).use { newClient ->
                    newClient.readPreamble()
                    awaitClients(next, 1)
                    next.write(eventRecord(2))
                    assertEquals(event(2), newClient.message().event())
                }
            } finally {
                next.close()
            }
        } finally {
            client.close()
            first.close()
        }
    }

    @Test
    fun pingIsAnsweredOnThePongChannelToTheSenderOnly() {
        val sink = TcpServerSink(port = 0)
        try {
            sink.start()
            val port = sink.status.value.port!!
            connect(port).readPreamble().use { pinger ->
                connect(port).readPreamble().use { other ->
                    awaitClients(sink, 2)
                    pinger.send("""{"ping":42}""")
                    val pong = pinger.message()
                    assertEquals(catalog.id(Topics.Pong), pong.channelId)
                    assertEquals("""{"ping":42}""", pong.text)

                    // The other client gets the next event, not the pong.
                    sink.write(eventRecord(7))
                    assertEquals(event(7), other.message().event())
                    assertEquals(event(7), pinger.message().event())
                }
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun otherClientLinesGoToOnLine() {
        val received = LinkedBlockingQueue<JsonObject>()
        val sink = TcpServerSink(port = 0, onLine = { received += it })
        try {
            sink.start()
            connect(sink.status.value.port!!).readPreamble().use { client ->
                awaitClients(sink, 1)
                client.send("""{"ping":1}""")
                client.send("""{"link":"body","legs":[1,2]}""")
                client.send("[1,2]")
                assertEquals(catalog.id(Topics.Pong), client.message().channelId)
                assertEquals(json("""{"link":"body","legs":[1,2]}"""), received.poll(5, TimeUnit.SECONDS))
            }
            assertEquals(null, received.poll())
        } finally {
            sink.close()
        }
    }

    @Test
    fun junkAndOversizedInputIsIgnoredAndTheClientStaysConnected() {
        val sink = TcpServerSink(port = 0)
        try {
            sink.start()
            connect(sink.status.value.port!!).readPreamble().use { client ->
                awaitClients(sink, 1)
                client.send("not json")
                client.send("""{"ping":"x"}""")
                client.send("[1,2]")
                // Over the 1 KB cap: ignored even though it would parse as a ping.
                client.send("""{"ping":1,"pad":"${"x".repeat(TcpServerSink.MAX_CLIENT_LINE_BYTES)}"}""")
                client.send("""{"ping":2}""")
                assertEquals("""{"ping":2}""", client.message().text)
                assertEquals(1, sink.status.value.clients)

                sink.write(eventRecord(3))
                assertEquals(event(3), client.message().event())
                client.assertNothingMore()
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun underBackpressureVideoIsDroppedUntilAKeyframeWhileEventsSurvive() {
        val keyframeRequests = AtomicInteger()
        val sink = TcpServerSink(port = 0, clientQueueBytes = 256 shl 10, requestKeyframe = { keyframeRequests.incrementAndGet() })
        try {
            sink.start()
            val socket = Socket().apply {
                receiveBufferSize = 4096
                soTimeout = 5_000
                connect(InetSocketAddress("127.0.0.1", sink.status.value.port!!))
            }
            Client(socket).use { client ->
                awaitClients(sink, 1)
                val frame = ByteArray(32 shl 10)
                val keyframes = (0L until 300L step 30).toSet()
                // A delta frame before any keyframe: a new client cannot decode it.
                sink.write(record(Topics.CameraVideo, 1_000, VideoFrame(0, frame, keyframe = false, frameId = "cam")))
                for (i in 0L until 300L) {
                    sink.write(record(Topics.CameraVideo, i, VideoFrame(i, frame, keyframe = i in keyframes, frameId = "cam")))
                    sink.write(eventRecord(i + 1))
                }
                // Only now does the client start reading.
                client.readPreamble()
                val events = mutableListOf<Long>()
                val video = mutableListOf<Long>()
                while (events.lastOrNull() != 300L) {
                    val message = client.message()
                    when (message.channelId) {
                        eventsId -> events += message.event().seq
                        catalog.id(Topics.CameraVideo) -> video += message.sequence
                    }
                }
                assertEquals((1L..300L).toList(), events)
                assertTrue("some video was dropped: ${video.size}", video.size < 300)
                assertTrue("the first frame is a keyframe: $video", video.first() in keyframes)
                video.zipWithNext().filter { (a, b) -> b != a + 1 }.forEach { (_, resumed) ->
                    assertTrue("video resumes at a keyframe, not $resumed", resumed in keyframes)
                }
                assertTrue(sink.takeDropped() >= 300 - video.size)
                assertTrue("joining and skipping ask for keyframes", keyframeRequests.get() >= 2)
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun aNewClientStartsVideoAtAKeyframeWithoutCountingTheFramesBeforeAsDrops() {
        val sink = TcpServerSink(port = 0)
        try {
            sink.start()
            connect(sink.status.value.port!!).readPreamble().use { client ->
                awaitClients(sink, 1)
                val frame = ByteArray(100)
                listOf(false, false, true, false).forEachIndexed { i, keyframe ->
                    sink.write(record(Topics.CameraVideo, i.toLong(), VideoFrame(i.toLong(), frame, keyframe, "cam")))
                }
                assertEquals(listOf(2L, 3L), List(2) { client.message().sequence })
                assertEquals(0L, sink.takeDropped())
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun bindFailureIsReportedNotThrown() {
        val first = TcpServerSink(port = 0)
        try {
            first.start()
            val second = TcpServerSink(port = first.status.value.port!!)
            second.write(eventRecord(1))
            assertTrue(second.status.value.error.orEmpty(), second.status.value.error != null)
            second.close()
        } finally {
            first.close()
        }
    }

    // Wi-Fi listener: a plain loopback socket stands in for the app's TLS one.

    private class FakeWifi(var stored: ByteArray? = null) : WifiAccess {
        override fun listen() = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        override val fingerprint = "ab12"
        override fun key() = stored
        override fun storeKey(key: ByteArray) {
            stored = key
        }
    }

    private val relayKey = ByteArray(TcpServerSink.KEY_BYTES) { it.toByte() }
    private fun auth(key: ByteArray) = """{"link":"auth","key":"${Base64.getEncoder().encodeToString(key)}"}"""
    private fun json(line: String): JsonObject = WalkyJson.parseToJsonElement(line).jsonObject
    private fun Socket.lines() = BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))

    @Test
    fun wifiClientGetsNothingUntilItPresentsTheKeyThenTheFullStream() {
        val received = LinkedBlockingQueue<JsonObject>()
        val sink = TcpServerSink(port = 0, replayEvents = 1, onLine = { received += it }, wifi = FakeWifi(relayKey))
        try {
            sink.write(eventRecord(1))
            val wifiPort = sink.status.value.wifiPort!!
            connect(wifiPort).use { early ->
                early.assertNothingMore()
                // A leg command in place of the key is refused, as a JSON line, not acted on.
                early.send("""{"link":"body","legs":[10,20]}""")
                val reader = early.socket.lines()
                assertEquals("denied", json(reader.readLine())["reason"].toString().trim('"'))
                assertEquals(null, reader.readLine())
            }
            assertEquals(0, sink.status.value.clients)

            connect(wifiPort).use { client ->
                client.send(auth(relayKey))
                client.readPreamble()
                assertEquals(wifiPort.toString(), client.hello["port"])
                assertFalse(client.hello.containsKey("cert"))
                assertEquals(event(1), client.message().event())
                awaitClients(sink, 1)
                sink.write(eventRecord(2))
                assertEquals(event(2), client.message().event())

                client.send("""{"link":"body","limp":true}""")
                assertEquals(json("""{"link":"body","limp":true}"""), received.poll(5, TimeUnit.SECONDS))
            }
            // The leg command sent before admission never arrived.
            assertEquals(null, received.poll())
        } finally {
            sink.close()
        }
    }

    @Test
    fun wifiClientWithAWrongKeyOrNoStoredKeyIsRefusedWithAJsonLineAndClosed() {
        for ((stored, reason) in listOf(relayKey to "denied", null to "unpaired")) {
            val sink = TcpServerSink(port = 0, wifi = FakeWifi(stored))
            try {
                sink.start()
                connect(sink.status.value.wifiPort!!).use { client ->
                    client.send(auth(ByteArray(TcpServerSink.KEY_BYTES) { 7 }))
                    val reader = client.socket.lines()
                    assertEquals(json("""{"link":"auth","ok":false,"reason":"$reason"}"""), json(reader.readLine()))
                    assertEquals(null, reader.readLine())
                }
                assertEquals(0, sink.status.value.clients)
            } finally {
                sink.close()
            }
        }
    }

    @Test
    fun keyIsStoredFromLoopbackOnlyAndOnlyTheLoopbackHelloCarriesTheCertificate() {
        val wifi = FakeWifi()
        val received = LinkedBlockingQueue<JsonObject>()
        val sink = TcpServerSink(port = 0, onLine = { received += it }, wifi = wifi)
        try {
            sink.start()
            connect(sink.status.value.port!!).readPreamble().use { usb ->
                assertEquals("ab12", usb.hello["cert"])
                usb.send("""{"link":"key","key":"${Base64.getEncoder().encodeToString(relayKey)}"}""")
                usb.send("""{"ping":1}""")
                assertEquals(catalog.id(Topics.Pong), usb.message().channelId)
                assertTrue(relayKey.contentEquals(wifi.stored))
            }

            connect(sink.status.value.wifiPort!!).use { remote ->
                remote.send(auth(relayKey))
                remote.readPreamble()
                val other = ByteArray(TcpServerSink.KEY_BYTES) { 9 }
                remote.send("""{"link":"key","key":"${Base64.getEncoder().encodeToString(other)}"}""")
                remote.send("""{"ping":2}""")
                assertEquals(catalog.id(Topics.Pong), remote.message().channelId)
                assertTrue(relayKey.contentEquals(wifi.stored))
            }
            // Neither key line is a body command.
            assertEquals(null, received.poll())
        } finally {
            sink.close()
        }
    }

    @Test
    fun wifiClientThatNeverAuthenticatesIsClosed() {
        val sink = TcpServerSink(port = 0, wifi = FakeWifi(relayKey))
        try {
            sink.start()
            connect(sink.status.value.wifiPort!!).use { client ->
                client.socket.soTimeout = TcpServerSink.AUTH_TIMEOUT_MS + 2_000
                assertEquals(-1, client.socket.getInputStream().read())
            }
        } finally {
            sink.close()
        }
    }
}
