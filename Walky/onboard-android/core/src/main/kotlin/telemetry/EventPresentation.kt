package com.vadymsidorov.walky.core.telemetry

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Display-only summary of a log event; the original event remains available for inspection. */
data class LogPresentation(val label: String, val message: String)

fun TelemetryEvent.presentation(): LogPresentation {
    val fields = payload as? JsonObject
    fun text(key: String) = (fields?.get(key) as? JsonPrimitive)?.contentOrNull
    val label = kind.removePrefix("log.").uppercase()
    val message = listOfNotNull(text("msg"), text("error")).joinToString(" — ").ifEmpty { payload.toString() }
    return LogPresentation(label, message.replace(Regex("[\\r\\n\\t]+"), " "))
}
