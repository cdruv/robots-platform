package com.vadymsidorov.yobot.core.telemetry

/** Display filters never suppress diagnostics indicating lost events or failures. */
data class DebugLogFilter(
    val partials: Boolean = false,
    val motion: Boolean = true,
    val speech: Boolean = true,
    val system: Boolean = true,
) {
    fun includes(event: TelemetryEvent): Boolean = when {
        event.kind in setOf("log.warn", "log.error", "dropped", "SpeechFailed") -> true
        event.presentation().partial -> partials
        event.kind == "Motion" -> motion
        event.kind in setOf("HeardUtterance", "SpeechStarted", "SpeechFinished", "SayText") -> speech
        else -> system
    }
}
