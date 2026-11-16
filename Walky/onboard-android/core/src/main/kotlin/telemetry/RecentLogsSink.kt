package com.vadymsidorov.walky.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the newest [capacity] log events ([isLog]) in memory for an on-screen debug view. Oldest first. */
class RecentLogsSink(private val capacity: Int = 300) : TelemetrySink {
    override val name = "recent"

    private val buffer = ArrayDeque<TelemetryEvent>(capacity)
    private val current = MutableStateFlow<List<TelemetryEvent>>(emptyList())
    val logs: StateFlow<List<TelemetryEvent>> = current.asStateFlow()

    /** Clears only the on-screen history; other telemetry sinks keep running. */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            current.value = emptyList()
        }
    }

    override fun write(batch: List<TelemetryEvent>) {
        val logs = batch.filter { it.isLog }
        if (logs.isEmpty()) return
        synchronized(buffer) {
            logs.forEach { buffer.addLast(it) }
            while (buffer.size > capacity) buffer.removeFirst()
            current.value = buffer.toList()
        }
    }
}
