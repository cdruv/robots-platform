package com.vadymsidorov.yobot.core.senses

import com.vadymsidorov.yobot.core.events.Motion
import com.vadymsidorov.yobot.core.events.MotionKind
import com.vadymsidorov.yobot.core.senses.BodyMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionClassifierTest {
    private val classifier = MotionClassifier()
    private var nowMs = 1_000L

    /** Feeds [sample] at 50 Hz for [ms] and returns the percepts reported. */
    private fun percepts(sample: BodyMotion, ms: Long): List<Motion> = buildList {
        repeat((ms / 20).toInt()) {
            classifier.update(sample, nowMs)?.let { add(it) }
            nowMs += 20
        }
    }

    private fun feed(sample: BodyMotion, ms: Long): List<MotionKind> = percepts(sample, ms).map { it.kind }

    /** Swings the body left and right, [swingMs] each way, for [ms]. */
    private fun shake(ms: Long, swingMs: Long = 160): List<MotionKind> = buildList {
        repeat((ms / swingMs).toInt()) { addAll(feed(BodyMotion(accelX = if (it % 2 == 0) 15f else -15f), swingMs)) }
    }

    @Test
    fun restingUprightReportsStillOnce() {
        assertEquals(listOf(MotionKind.Still), feed(BodyMotion(), 2_000))
    }

    @Test
    fun sustainedShakeIsMovingThenShakenThenSettlesBackToStill() {
        feed(BodyMotion(), 1_000)
        assertEquals(listOf(MotionKind.Moving, MotionKind.Shaken), shake(2_000))
        assertEquals(listOf(MotionKind.Still), feed(BodyMotion(), 5_000))
    }

    @Test
    fun shortShakeIsOnlyMoving() {
        feed(BodyMotion(), 1_000)
        assertEquals(listOf(MotionKind.Moving, MotionKind.Still), shake(640) + feed(BodyMotion(), 5_000))
    }

    @Test
    fun punchIsOnlyMoving() {
        feed(BodyMotion(), 1_000)
        val punch = feed(BodyMotion(accelX = 30f), 60) + feed(BodyMotion(accelX = -30f), 60)
        assertEquals(listOf(MotionKind.Moving, MotionKind.Still), punch + feed(BodyMotion(), 5_000))
    }

    @Test
    fun strongPushOneWayIsOnlyMoving() {
        feed(BodyMotion(), 1_000)
        assertEquals(listOf(MotionKind.Moving), feed(BodyMotion(accelY = 15f), 3_000))
    }

    @Test
    fun briefBumpIsIgnored() {
        feed(BodyMotion(), 1_000)
        assertEquals(emptyList<MotionKind>(), feed(BodyMotion(accelY = 2f), 40) + feed(BodyMotion(), 2_000))
    }

    @Test
    fun rollingOntoASideReportsTilted() {
        feed(BodyMotion(), 1_000)
        val onLeftSide = BodyMotion(gravityX = 9.8f, gravityY = 0f)
        assertEquals(listOf(MotionKind.Tilted), feed(onLeftSide, 1_000))
        assertEquals(90f, onLeftSide.rollDegrees, 0.01f)
    }

    @Test
    fun tremorWhileHeldTiltedDoesNotFlipToMoving() {
        val leaningBack = BodyMotion(gravityY = 7.5f, gravityZ = 6.3f)
        val kinds = buildList {
            repeat(50) {
                addAll(feed(leaningBack.copy(accelX = 0.6f), 100))
                addAll(feed(leaningBack.copy(accelX = 1.3f), 100))
            }
        }
        assertEquals(listOf(MotionKind.Tilted), kinds)
    }

    @Test
    fun wobbleAroundTheTiltThresholdDoesNotFlip() {
        fun rolled(degrees: Double) = Math.toRadians(degrees).let {
            BodyMotion(gravityX = (9.8 * Math.sin(it)).toFloat(), gravityY = (9.8 * Math.cos(it)).toFloat())
        }
        val kinds = feed(rolled(32.0), 1_000) + buildList {
            repeat(5) { addAll(feed(rolled(28.0), 600) + feed(rolled(32.0), 600)) }
        }
        assertEquals(listOf(MotionKind.Tilted), kinds)
    }

    @Test
    fun lyingFlatReportsNoRoll() {
        // Gravity almost entirely out of the screen: what is left in the plane is noise.
        val flat = percepts(BodyMotion(gravityX = 0.1f, gravityY = -0.7f, gravityZ = 9.78f), 1_000).single()
        assertEquals(MotionKind.Tilted, flat.kind)
        assertEquals(0f, flat.roll, 0f)
        assertEquals(85.9f, flat.pitch, 0.5f)
    }
}
