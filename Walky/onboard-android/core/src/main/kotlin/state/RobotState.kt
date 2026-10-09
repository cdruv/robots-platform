package com.vadymsidorov.walky.core.state

import com.vadymsidorov.walky.core.output.FaceState
import kotlinx.serialization.Serializable

@Serializable
enum class TriggerKind { Heard, Touched, Moved, Idle, Command }

/** Contract for future cognition inputs; no triggers are generated yet. */
@Serializable
data class ThinkTrigger(val kind: TriggerKind, val detail: String? = null)

/** Minimal state. Add observations and behavior state as their features are implemented. */
@Serializable
data class RobotState(val face: FaceState = FaceState())
