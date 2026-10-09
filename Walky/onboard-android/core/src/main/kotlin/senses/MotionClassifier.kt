package com.vadymsidorov.walky.core.senses

import com.vadymsidorov.walky.core.events.Motion
import com.vadymsidorov.walky.core.events.MotionKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns the IMU sample stream into coarse [Motion] percepts, one per change of [MotionKind].
 *
 * - [MotionKind.Shaken] is a deliberate shake, as in "shake to report": the acceleration
 *   swings back and forth, strongly, for at least [SHAKE_MS]. A single punch, bump or drop
 *   is only [MotionKind.Moving].
 * - [MotionKind.Moving] and [MotionKind.Tilted] each start above one threshold and end below
 *   a lower one, so a reading that hovers near a threshold does not flip the kind.
 * - A kind must hold for [SETTLE_MS] before it is reported, except Shaken, which has already
 *   taken its time.
 *
 * [MotionKind.PickedUp] is not detected yet. Thresholds are first guesses: tune them on the robot.
 */
class MotionClassifier {
    // Peak-hold of the acceleration magnitude, so one movement reads as one event, not many.
    private var envelope = 0f
    private var lastMs = 0L
    private var started = false
    private var moving = false
    private var tilted = false

    // The current run of swings: strong accelerations, each roughly opposite to the one before.
    private var swings = 0
    private var swingX = 0f
    private var swingY = 0f
    private var swingZ = 0f
    private var firstSwingMs = 0L
    private var lastSwingMs = 0L

    private var candidate: MotionKind? = null
    private var candidateSinceMs = 0L
    private var reported: MotionKind? = null

    /** Returns a percept when the settled kind changes, otherwise null. */
    fun update(sample: BodyMotion, nowMs: Long): Motion? {
        val magnitude = sample.accelMagnitude
        val seconds = if (started) (nowMs - lastMs) / 1000f else 0f
        started = true
        lastMs = nowMs
        envelope = if (magnitude >= envelope) magnitude
        else envelope + (magnitude - envelope) * (1f - exp(-seconds / RELEASE_SECONDS))
        moving = envelope > if (moving) MOVING_END_ACCEL else MOVING_START_ACCEL

        // Roll is undefined when gravity is almost out of the screen plane (lying flat): report none.
        val inPlane = sqrt(sample.gravityX * sample.gravityX + sample.gravityY * sample.gravityY)
        val roll = if (inPlane < ROLL_MIN_GRAVITY * STANDARD_GRAVITY) 0f else sample.rollDegrees
        val pitch = sample.pitchDegrees
        tilted = max(abs(roll), abs(pitch)) > if (tilted) TILT_END_DEGREES else TILT_START_DEGREES

        val kind = when {
            isShaking(sample, magnitude, nowMs) -> MotionKind.Shaken
            moving -> MotionKind.Moving
            tilted -> MotionKind.Tilted
            else -> MotionKind.Still
        }
        if (kind != candidate) {
            candidate = kind
            candidateSinceMs = nowMs
        }
        if (kind == reported) return null
        if (kind != MotionKind.Shaken && nowMs - candidateSinceMs < SETTLE_MS) return null
        reported = kind
        return Motion(kind, pitch = pitch, roll = roll, magnitude = envelope)
    }

    private fun isShaking(sample: BodyMotion, magnitude: Float, nowMs: Long): Boolean {
        if (swings > 0 && nowMs - lastSwingMs > SHAKE_GAP_MS) swings = 0
        if (magnitude > SHAKE_ACCEL) {
            val reversed = sample.accelX * swingX + sample.accelY * swingY + sample.accelZ * swingZ < 0f
            if (swings == 0 || reversed) {
                if (swings == 0) firstSwingMs = nowMs
                swings++
                lastSwingMs = nowMs
                swingX = sample.accelX
                swingY = sample.accelY
                swingZ = sample.accelZ
            }
        }
        return swings >= SHAKE_SWINGS && nowMs - firstSwingMs >= SHAKE_MS
    }

    private companion object {
        /** Linear acceleration, m/s², that starts and ends Moving. */
        const val MOVING_START_ACCEL = 1.5f
        const val MOVING_END_ACCEL = 0.7f
        /** Roll or pitch that starts and ends Tilted. */
        const val TILT_START_DEGREES = 30f
        const val TILT_END_DEGREES = 25f
        /** In-plane gravity, in g, below which roll is reported as 0. */
        const val ROLL_MIN_GRAVITY = 0.25f
        const val RELEASE_SECONDS = 0.25f
        const val SETTLE_MS = 400L

        /** Acceleration, m/s², that counts as one swing of a shake. */
        const val SHAKE_ACCEL = 8f
        /** A shake is at least this many swings over at least this long... */
        const val SHAKE_SWINGS = 5
        const val SHAKE_MS = 1_000L
        /** ...and it is over when no swing follows within this time. */
        const val SHAKE_GAP_MS = 500L
    }
}
