package com.vadymsidorov.yobot.core.telemetry

import kotlinx.serialization.json.Json

/** The one JSON configuration used for telemetry, prompts and responses. */
val YobotJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
    classDiscriminator = "type"
}

fun TelemetryEvent.toJsonLine(): String = YobotJson.encodeToString(TelemetryEvent.serializer(), this)
