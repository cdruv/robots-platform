package com.vadymsidorov.walky.core.senses

import kotlinx.serialization.Serializable

@Serializable
enum class HearingState { Stopped, NoPermission, Starting, Listening, Speech, Muted, Error }

/** Live view of the recognizer, for the debug overlay and `/hearing/status`; percepts still flow through [Sense.start]. */
@Serializable
data class HearingStatus(
    val state: HearingState = HearingState.Stopped,
    val muted: Boolean = false,
    val engine: String = "",
    val partial: String = "",
    val lastFinal: String = "",
    val lastError: String? = null,
    /** The recognizer's input level; -2 while not listening. */
    val rmsDb: Float = -2f,
    val sessions: Int = 0,
)
