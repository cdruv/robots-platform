package com.vadymsidorov.yobot.core.telemetry

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException

class TcpServerSinkTest {
    private fun event(seq: Long) = TelemetryEvent(seq, 1_000 + seq, seq, "test", "k$seq", JsonPrimitive(seq))

    private fun connect(port: Int): Pair<Socket, BufferedReader> {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        return socket to BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    }

    /** Connects and consumes the hello line every client receives first. */
    private fun connectPastHello(port: Int): Pair<Socket, BufferedReader> {
        val (socket, reader) = connect(port)
        assertEquals("hello", json(reader.readLine())["link"]?.jsonPrimitive?.content)
        return socket to reader
    }

    private fun json(line: String): JsonObject = YobotJson.parseToJsonElement(line).jsonObject

    private fun Socket.send(line: String) {
        getOutputStream().apply {
            write((line + "\n").toByteArray(Charsets.UTF_8))
            flush()
        }
    }

    private fun awaitClients(sink: TcpServerSink, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.status.value.clients != count && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(count, sink.status.value.clients)
    }

    @Test
    fun streamsJsonLinesAndReplaysRecentEventsToNewClients() {
        val sink = TcpServerSink(port = 0, replayEvents = 2)
        try {
            sink.write(listOf(event(1), event(2), event(3)))
            val port = sink.status.value.port
            assertNotNull(port)

            val (socket, reader) = connectPastHello(port!!)
            awaitClients(sink, 1)
            assertEquals(event(2), YobotJson.decodeFromString(TelemetryEvent.serializer(), reader.readLine()))
            assertEquals(event(3), YobotJson.decodeFromString(TelemetryEvent.serializer(), reader.readLine()))

            sink.write(listOf(event(4)))
            assertEquals(event(4), YobotJson.decodeFromString(TelemetryEvent.serializer(), reader.readLine()))

            socket.close()
            awaitClients(sink, 0)
            assertEquals(0L, sink.takeDropped())
        } finally {
            sink.close()
        }
    }

    @Test
    fun closingSessionDisconnectsClientsAndAllowsFreshSessionOnSamePort() {
        val first = TcpServerSink(port = 0)
        first.start()
        val port = first.status.value.port!!
        val (socket, reader) = connectPastHello(port)
        try {
            awaitClients(first, 1)
            first.close()
            assertEquals(null, reader.readLine())
            // A late telemetry worker must not reopen the closed session.
            first.write(listOf(event(1)))
            val next = TcpServerSink(port = port)
            try {
                next.start()
                assertEquals(null, next.status.value.error)
                val (newSocket, newReader) = connectPastHello(port)
                newSocket.use {
                    awaitClients(next, 1)
                    next.write(listOf(event(2)))
                    assertEquals(event(2), YobotJson.decodeFromString(TelemetryEvent.serializer(), newReader.readLine()))
                }
            } finally {
                next.close()
            }
        } finally {
            socket.close()
            first.close()
        }
    }

    @Test
    fun helloIsTheFirstLineBeforeTheReplay() {
        val sink = TcpServerSink(port = 0, hello = { buildJsonObject { put("device", "Test Phone") } })
        try {
            sink.write(listOf(event(1)))
            val port = sink.status.value.port!!
            val (socket, reader) = connect(port)
            socket.use {
                val hello = json(reader.readLine())
                assertEquals("hello", hello["link"]?.jsonPrimitive?.content)
                assertEquals(TcpServerSink.PROTOCOL.toString(), hello["protocol"]?.jsonPrimitive?.content)
                assertEquals("Test Phone", hello["device"]?.jsonPrimitive?.content)
                assertEquals(port.toString(), hello["port"]?.jsonPrimitive?.content)
                assertFalse(hello.containsKey("seq"))
                val session = hello["session"]?.jsonPrimitive?.content
                assertTrue(!session.isNullOrEmpty())
                assertEquals(event(1), YobotJson.decodeFromString(TelemetryEvent.serializer(), reader.readLine()))

                // Every client of one sink sees the same session.
                val (other, otherReader) = connect(port)
                other.use { assertEquals(session, json(otherReader.readLine())["session"]?.jsonPrimitive?.content) }
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun pingIsAnsweredToTheSenderOnly() {
        val sink = TcpServerSink(port = 0)
        try {
            sink.start()
            val port = sink.status.value.port!!
            val (pinger, pingerReader) = connectPastHello(port)
            val (other, otherReader) = connectPastHello(port)
            pinger.use {
                other.use {
                    awaitClients(sink, 2)
                    pinger.send("""{"ping":42}""")
                    val pong = json(pingerReader.readLine())
                    assertEquals("pong", pong["link"]?.jsonPrimitive?.content)
                    assertEquals("42", pong["ping"]?.jsonPrimitive?.content)

                    // The other client gets the next event, not the pong.
                    sink.write(listOf(event(7)))
                    assertEquals(event(7), YobotJson.decodeFromString(TelemetryEvent.serializer(), otherReader.readLine()))
                    assertEquals(event(7), YobotJson.decodeFromString(TelemetryEvent.serializer(), pingerReader.readLine()))
                }
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun junkAndOversizedInputIsIgnoredAndTheClientStaysConnected() {
        val sink = TcpServerSink(port = 0)
        try {
            sink.start()
            val port = sink.status.value.port!!
            val (socket, reader) = connectPastHello(port)
            socket.use {
                awaitClients(sink, 1)
                socket.send("not json")
                socket.send("""{"ping":"x"}""")
                socket.send("[1,2]")
                // Over the 1 KB cap: ignored even though it would parse as a ping.
                socket.send("""{"ping":1,"pad":"${"x".repeat(TcpServerSink.MAX_CLIENT_LINE_BYTES)}"}""")
                socket.send("""{"ping":2}""")
                assertEquals("2", json(reader.readLine())["ping"]?.jsonPrimitive?.content)
                assertEquals(1, sink.status.value.clients)

                sink.write(listOf(event(3)))
                assertEquals(event(3), YobotJson.decodeFromString(TelemetryEvent.serializer(), reader.readLine()))
                socket.soTimeout = 200
                assertTrue(runCatching { reader.readLine() }.exceptionOrNull() is SocketTimeoutException)
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
            val taken = first.status.value.port!!
            val second = TcpServerSink(port = taken)
            second.write(listOf(TelemetryEvent(1, 0, 0, "s", "k", JsonNull)))
            assertTrue(second.status.value.error.orEmpty(), second.status.value.error != null)
            second.close()
        } finally {
            first.close()
        }
    }
}
