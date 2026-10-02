package com.vadymsidorov.yobot.core.telemetry

import com.vadymsidorov.yobot.core.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryTest {
    private class FakeSink(override val name: String, private val fail: Boolean = false) : TelemetrySink {
        val batches = mutableListOf<List<TelemetryEvent>>()
        val events get() = batches.flatten()
        override fun write(batch: List<TelemetryEvent>) {
            if (fail) error("sink down")
            batches += batch
        }
    }

    private fun TestScope.telemetry(vararg sinks: TelemetrySink, queueCapacity: Int = 2048) = Telemetry(
        sinks = sinks.toList(),
        scope = backgroundScope,
        clock = FakeClock(),
        dispatcher = StandardTestDispatcher(testScheduler),
        queueCapacity = queueCapacity,
    ).also { it.start() }

    @Test
    fun batchesBySize() = runTest {
        val sink = FakeSink("a")
        val telemetry = telemetry(sink)
        repeat(250) { telemetry.emit("test", "e$it") }
        runCurrent()
        assertEquals(listOf(100, 100), sink.batches.map { it.size })
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(100, 100, 50), sink.batches.map { it.size })
        assertEquals((1L..250L).toList(), sink.events.map { it.seq })
    }

    @Test
    fun batchesByTime() = runTest {
        val sink = FakeSink("a")
        val telemetry = telemetry(sink)
        repeat(5) { telemetry.emit("test", "e") }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(sink.batches.isEmpty())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(5), sink.batches.map { it.size })
    }

    @Test
    fun failingSinkDoesNotAffectOthers() = runTest {
        val good = FakeSink("good")
        val telemetry = telemetry(FakeSink("bad", fail = true), good)
        repeat(3) { telemetry.emit("test", "e") }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(3, good.events.size)

        advanceTimeBy(1_500)
        runCurrent()
        val report = good.events.last { it.kind == "dropped" }.payload as JsonObject
        assertEquals(3, (report["sinks"] as JsonObject)["bad"]!!.jsonPrimitive.int)
    }

    @Test
    fun queueOverflowDropsOldestAndIsReported() = runTest {
        val sink = FakeSink("a")
        val telemetry = telemetry(sink, queueCapacity = 10)
        repeat(25) { telemetry.emit("test", "e$it") }
        advanceTimeBy(300)
        runCurrent()
        assertEquals((15 until 25).map { "e$it" }, sink.events.map { it.kind })

        advanceTimeBy(1_500)
        runCurrent()
        val report = sink.events.single { it.kind == "dropped" }
        assertEquals(Telemetry.SOURCE, report.source)
        assertEquals(JsonPrimitive(15L), (report.payload as JsonObject)["queue"])
    }

    @Test
    fun loggerEmitsLevelledEvents() = runTest {
        val sink = FakeSink("a")
        val telemetry = telemetry(sink)
        telemetry.logger("brain").warn("careful", IllegalStateException("x"))
        advanceTimeBy(300)
        runCurrent()
        val event = sink.events.single()
        assertEquals("brain" to "log.warn", event.source to event.kind)
        assertTrue(event.toJsonLine().contains("\"msg\":\"careful\""))
    }
}
