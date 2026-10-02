package com.vadymsidorov.yobot.core.telemetry

/**
 * Destination for telemetry batches. Each sink runs on its own worker, so a slow or
 * blocking [write] only delays that sink. Exceptions are caught and counted.
 */
interface TelemetrySink {
    val name: String

    fun write(batch: List<TelemetryEvent>)

    /** Events this sink discarded since the last call (e.g. while disconnected). */
    fun takeDropped(): Long = 0

    fun close() {}
}
