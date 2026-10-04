package com.vadymsidorov.yobot.core.telemetry

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class TcpServerSinkTest {
    private fun event(seq: Long) = TelemetryEvent(seq, 1_000 + seq, seq, "test", "k$seq", JsonPrimitive(seq))

    private fun connect(port: Int): Pair<Socket, BufferedReader> {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        return socket to BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
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

            val (socket, reader) = connect(port!!)
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
        val (socket, reader) = connect(port)
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
                val (newSocket, newReader) = connect(port)
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
