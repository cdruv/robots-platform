package com.vadymsidorov.walky.core.telemetry

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Display-only summaries; original events remain available for inspection and recording. */
data class EventPresentation(val label: String, val message: String, val partial: Boolean = false)

fun TelemetryEvent.presentation(): EventPresentation {
    val fields = payload as? JsonObject
    fun text(key: String) = (fields?.get(key) as? JsonPrimitive)?.contentOrNull
    val partial = kind == "HeardUtterance" &&
        (fields?.get("isFinal") as? JsonPrimitive)?.booleanOrNull == false
    val label = when (kind) {
        "log.info" -> "INFO"
        "log.warn", "dropped" -> "WARN"
        "log.error" -> "ERROR"
        "HeardUtterance" -> if (partial) "PARTIAL" else "HEARD"
        else -> kind
    }
    val message = when (kind) {
        "log.info", "log.warn", "log.error" -> listOfNotNull(text("msg"), text("error"))
            .joinToString(" — ").ifEmpty { payload.toString() }
        "HeardUtterance" -> text("text")?.let { "“$it”" } ?: payload.toString()
        "dropped" -> "Dropped events: queue=${text("queue") ?: "?"}, sinks=${fields?.get("sinks") ?: "?"}"
        else -> payload.toString()
    }
    return EventPresentation(label, message.replace(Regex("[\\r\\n\\t]+"), " "), partial)
}
