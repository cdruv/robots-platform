package com.vadymsidorov.yobot.core.telemetry

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogFilterTest {
    private fun event(kind: String, seq: Long = 1) = TelemetryEvent(seq, 0, 0, "test", kind, JsonNull)

    @Test fun categoriesCanBeHiddenIndependently() {
        assertFalse(DebugLogFilter(motion = false).includes(event("Motion")))
        assertTrue(DebugLogFilter(motion = false).includes(event("HeardUtterance")))
        assertFalse(DebugLogFilter(speech = false).includes(event("HeardUtterance")))
        assertFalse(DebugLogFilter(speech = false).includes(event("SpeechStarted")))
        assertFalse(DebugLogFilter(system = false).includes(event("log.info")))
        assertTrue(DebugLogFilter(system = false).includes(event("Motion")))
    }

    @Test fun partialsHaveAnIndependentOptIn() {
        val partial = event("HeardUtterance").copy(payload = buildJsonObject { put("isFinal", false) })
        assertFalse(DebugLogFilter().includes(partial))
        assertTrue(DebugLogFilter(partials = true, speech = false).includes(partial))
    }

    @Test fun diagnosticsStayVisibleWithEveryFilterOff() {
        val filter = DebugLogFilter(false, false, false, false)
        listOf("log.warn", "log.error", "dropped", "SpeechFailed").forEach {
            assertTrue(filter.includes(event(it)))
        }
    }

    @Test fun clearRemovesHiddenHistoryAndAllowsFreshEvents() {
        val sink = RecentEventsSink(2)
        sink.write(listOf(event("Motion", 1), event("HeardUtterance", 2)))
        sink.clear()
        assertTrue(sink.events.value.isEmpty())
        sink.write(listOf(event("log.info", 3)))
        assertEquals(listOf(3L), sink.events.value.map { it.seq })
        sink.write(listOf(event("log.info", 4), event("log.info", 5)))
        assertEquals(listOf(4L, 5L), sink.events.value.map { it.seq })
    }
}
