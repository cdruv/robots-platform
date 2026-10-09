package com.vadymsidorov.walky.core.events

import com.vadymsidorov.walky.core.cognition.Intent
import com.vadymsidorov.walky.core.output.Expression
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias UtteranceId = String
typealias RequestId = Long

/**
 * Everything the Executive reacts to. All events are serializable so the same objects
 * can become telemetry payloads and replay input in future iterations.
 */
@Serializable
sealed interface ExecutiveEvent

/** Something a sense perceived. */
@Serializable
sealed interface Percept : ExecutiveEvent

@Serializable
@SerialName("HeardUtterance")
data class HeardUtterance(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float? = null,
) : Percept

@Serializable
enum class MotionKind { Still, Moving, Shaken, PickedUp, Tilted }

/** Body motion summary. Angles in degrees, magnitude in m/s² of linear acceleration. */
@Serializable
@SerialName("Motion")
data class Motion(
    val kind: MotionKind,
    val pitch: Float,
    val roll: Float,
    val magnitude: Float,
) : Percept

/** Camera summary; frames themselves stay on the device. Scores are 0..1. */
@Serializable
@SerialName("VisionSummary")
data class VisionSummary(
    val motionScore: Float,
    val brightness: Float,
    val fps: Float,
    val frameId: Long,
) : Percept

@Serializable
enum class TouchKind { Tap, LongPress, Stroke }

@Serializable
enum class TouchRegion { LeftEye, RightEye, Mouth, Forehead, Other }

/** Touch on the face. [x] and [y] are normalized to 0..1 of the screen. */
@Serializable
@SerialName("Touch")
data class Touch(
    val kind: TouchKind,
    val x: Float,
    val y: Float,
    val region: TouchRegion,
) : Percept

/** [thermalStatus] uses Android's PowerManager.THERMAL_STATUS_* scale (0 = none). */
@Serializable
@SerialName("SystemStatus")
data class SystemStatus(
    val batteryPct: Int,
    val charging: Boolean,
    val thermalStatus: Int,
) : Percept

/** Reports from outputs about actions that take time. */
@Serializable
sealed interface OutputFeedback : ExecutiveEvent

@Serializable
@SerialName("SpeechStarted")
data class SpeechStarted(val utteranceId: UtteranceId) : OutputFeedback

@Serializable
@SerialName("SpeechFinished")
data class SpeechFinished(val utteranceId: UtteranceId) : OutputFeedback

@Serializable
@SerialName("SpeechFailed")
data class SpeechFailed(val utteranceId: UtteranceId, val reason: String) : OutputFeedback

/** Outcome of one inference round trip: exactly one of [intent] or [error] is set. */
@Serializable
@SerialName("ThoughtResult")
data class ThoughtResult(
    val requestId: RequestId,
    val intent: Intent? = null,
    val error: String? = null,
) : ExecutiveEvent {
    val result: Result<Intent>
        get() = intent?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(error ?: "no intent"))

    companion object {
        fun of(requestId: RequestId, result: Result<Intent>) = ThoughtResult(
            requestId = requestId,
            intent = result.getOrNull(),
            error = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
        )
    }
}

/** Direct instructions, for tests and the future debug overlay. */
@Serializable
sealed interface Command : ExecutiveEvent

@Serializable
@SerialName("ThinkNow")
data class ThinkNow(val reason: String) : Command

@Serializable
@SerialName("SayText")
data class SayText(val text: String) : Command

@Serializable
@SerialName("SetExpression")
data class SetExpression(val expression: Expression) : Command
