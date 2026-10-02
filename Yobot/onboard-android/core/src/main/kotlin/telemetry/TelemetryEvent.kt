package com.vadymsidorov.yobot.core.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TelemetryEvent(
    val seq: Long,
    val tsWallMs: Long,
    val tsMonoNs: Long,
    val source: String,
    val kind: String,
    val payload: JsonElement,
)
