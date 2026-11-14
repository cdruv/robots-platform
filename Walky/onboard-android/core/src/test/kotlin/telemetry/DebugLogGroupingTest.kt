package com.vadymsidorov.walky.core.telemetry

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogGroupingTest {
    private fun event(seq: Long, seconds: Long, kind: String = "Motion") =
        TelemetryEvent(seq, 0, seconds * 1_000_000_000L, "test", kind, JsonPrimitive(seq))

    @Test fun eachMatchingActionRestartsTheInclusiveWindow() {
        val a = event(1, 2)
        val b = event(2, 12)
        val c = event(3, 27)
        val warning = event(4, 40, "log.warn")
        val d = event(5, 43)
        val rows = groupDebugEvents(listOf(a, b, c, warning, d), setOf("Motion"), 15)
        assertEquals(listOf(listOf(d), listOf(warning), listOf(a, b, c)), rows)
    }

    @Test fun selectedKindsHaveIndependentGroupsAndRespectConfiguredWindow() {
        val a = event(1, 0, "SpeechStarted")
        val b = event(2, 1)
        val c = event(3, 2, "SpeechStarted")
        val d = event(4, 7)
        val input = listOf(a, b, c, d)
        assertEquals(listOf(listOf(d), listOf(a, c), listOf(b)),
            groupDebugEvents(input, setOf("Motion", "SpeechStarted"), 5))
        assertEquals(input.reversed().map { listOf(it) }, groupDebugEvents(input, emptySet(), 15))
    }
}
