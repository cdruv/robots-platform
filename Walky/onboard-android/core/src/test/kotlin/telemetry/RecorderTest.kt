package com.vadymsidorov.walky.core.telemetry

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTest {
    private val log = FakeTelemetry()

    private fun telemetry(recorder: Recorder, mono: () -> Long) = DefaultTelemetry(
        sinks = emptyList(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        streams = listOf(recorder),
        monoNs = mono,
    )

    @Test
    fun aClosedRecorderLeavesACompleteIndexedFile() {
        val out = ByteArrayOutputStream()
        val recorder = Recorder({ out }, mapOf("session" to "s-1", "clock" to "boottime"), log = { log.logger("rec") })
        var now = 1_000L
        val telemetry = telemetry(recorder) { now }
        recorder.start()
        val face = telemetry.channel(Topics.FaceStateTopic)
        face.write(500, com.vadymsidorov.walky.core.output.FaceState())
        now = 2_000
        telemetry.emit("test", "ping")
        recorder.close()
        assertTrue(recorder.join(5_000))

        val records = records(out.toByteArray())
        assertEquals(0x02, records.last().second.opcode)
        val metadata = records.single { it.second.opcode == 0x0C }.second.reader()
        assertEquals("session", metadata.string())
        assertEquals(mapOf("session" to "s-1", "clock" to "boottime"), metadata.stringMap())

        val channels = records.filter { it.second.opcode == 0x04 }.map { it.second.reader().run { u16() to (u16() to string()) } }
        assertEquals(Topics.all.map { it.name }, channels.take(Topics.all.size).map { it.second.second })

        val stats = records.single { it.second.opcode == 0x0B }.second.reader()
        assertEquals(2L, stats.u64())
        stats.u16(); stats.u32(); stats.u32(); stats.u32(); stats.u32()
        assertEquals("message times are the log times given", 500L to 2_000L, stats.u64() to stats.u64())
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun overflowIsCountedAndLoggedAsAnErrorNotDroppedSilently() = runTest {
        // Never started, so nothing drains the queue.
        val recorder = Recorder({ ByteArrayOutputStream() }, emptyMap(), log = { log.logger("rec") }, budgetBytes = 1_000)
        val events = mutableListOf<TelemetryEvent>()
        val sink = object : TelemetrySink {
            override val name = "events"
            override fun write(batch: List<TelemetryEvent>) { events += batch }
        }
        val telemetry = DefaultTelemetry(
            sinks = listOf(sink),
            scope = backgroundScope,
            streams = listOf(recorder),
            dispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.start() }
        repeat(50) { telemetry.emit("test", "e$it") }
        advanceTimeBy(1_500)
        runCurrent()
        val report = events.single { it.kind == "dropped" }
        val lost = (report.payload as kotlinx.serialization.json.JsonObject)["sinks"].toString()
        assertTrue(lost, lost.contains("\"recording\""))
        val error = events.single { it.kind == "log.error" }
        assertTrue(error.presentation().message, error.presentation().message.startsWith("recording lost"))
    }
}
