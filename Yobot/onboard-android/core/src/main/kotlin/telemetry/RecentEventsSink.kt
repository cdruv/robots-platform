package com.vadymsidorov.yobot.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the newest [capacity] events in memory for an on-screen debug view. Oldest first. */
class RecentEventsSink(private val capacity: Int = 300) : TelemetrySink {
    override val name = "recent"

    private val buffer = ArrayDeque<TelemetryEvent>(capacity)
    private val current = MutableStateFlow<List<TelemetryEvent>>(emptyList())
    val events: StateFlow<List<TelemetryEvent>> = current.asStateFlow()

    /** Clears only the on-screen history; other telemetry sinks keep running. */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            current.value = emptyList()
        }
    }

    override fun write(batch: List<TelemetryEvent>) {
        synchronized(buffer) {
            batch.forEach { buffer.addLast(it) }
            while (buffer.size > capacity) buffer.removeFirst()
            current.value = buffer.toList()
        }
    }
}
