package com.vadymsidorov.walky.core.state

import com.vadymsidorov.walky.core.events.UtteranceId
import com.vadymsidorov.walky.core.output.FaceState
import kotlinx.serialization.Serializable

@Serializable
enum class TriggerKind { Heard, Touched, Moved, Idle, Command }

/** Contract for future cognition inputs; no triggers are generated yet. */
@Serializable
data class ThinkTrigger(val kind: TriggerKind, val detail: String? = null)

/**
 * What the active brain holds. Sustained outputs live here and the Executive applies them:
 * [face] is the face target, [listening] whether hearing runs (user mute still wins).
 * [speaking] is the utterance in progress. Add observations and behavior state as their
 * features are implemented.
 */
@Serializable
data class RobotState(
    val face: FaceState = FaceState(),
    val listening: Boolean = true,
    val speaking: UtteranceId? = null,
)
