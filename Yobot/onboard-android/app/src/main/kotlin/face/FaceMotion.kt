package com.vadymsidorov.yobot.face

import com.vadymsidorov.yobot.core.senses.BodyMotion
import com.vadymsidorov.yobot.core.senses.STANDARD_GRAVITY
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A value that chases its target like a mass on a damped spring: it lags, overshoots and settles. */
private class Spring(private val frequency: Float, private val damping: Float) {
    var value = 0f
        private set
    private var velocity = 0f

    fun step(target: Float, dt: Float) {
        velocity += (frequency * frequency * (target - value) - 2f * damping * frequency * velocity) * dt
        value += velocity * dt
    }
}

/**
 * Everything on the face that moves with the body: the liquid, and the eyes, pupils and mouth,
 * which sag toward gravity, swing against acceleration, and turn to stay level with the
 * horizon. Driven by the IMU reflex and stepped at a fixed rate from the draw loop.
 *
 * The offsets are in design dp along the axes of the face after it has turned by [roll].
 */
internal class FaceMotion {
    val liquid = FaceLiquid()

    /** Degrees to turn the eyes and mouth, clockwise, so they stay level. */
    var roll = 0f
        private set
    var eyeX = 0f
        private set
    var eyeY = 0f
        private set
    var mouthX = 0f
        private set
    var mouthY = 0f
        private set

    private val rollSpring = Spring(FaceSpec.LEVEL_FREQUENCY, FaceSpec.LEVEL_DAMPING)
    private val eyeSpringX = Spring(FaceSpec.EYE_SWAY_FREQUENCY, FaceSpec.SWAY_DAMPING)
    private val eyeSpringY = Spring(FaceSpec.EYE_SWAY_FREQUENCY, FaceSpec.SWAY_DAMPING)
    private val mouthSpringX = Spring(FaceSpec.MOUTH_SWAY_FREQUENCY, FaceSpec.SWAY_DAMPING)
    private val mouthSpringY = Spring(FaceSpec.MOUTH_SWAY_FREQUENCY, FaceSpec.SWAY_DAMPING)

    private var lastSeconds = Double.NaN
    private var pending = 0f

    /** Catches the simulation up to [seconds] on the face clock. [screenHeight] is in design dp. */
    fun advance(seconds: Double, body: BodyMotion, screenHeight: Float) {
        val elapsed = if (lastSeconds.isNaN()) 0f else (seconds - lastSeconds).toFloat()
        lastSeconds = seconds
        // A long gap (app in the background) is skipped, not simulated.
        pending += elapsed.coerceIn(0f, FaceSpec.MOTION_MAX_FRAME_SECONDS)
        if (pending < FaceSpec.MOTION_STEP_SECONDS) return

        // Force on things inside the body, in g, along screen axes (x right, y down). The sensor
        // reads the reaction to gravity plus the body's acceleration; contents are pulled the
        // opposite way. Device y points up the screen, so only x changes sign.
        val downX = -body.gravityX / STANDARD_GRAVITY
        val downY = body.gravityY / STANDARD_GRAVITY
        val kickX = -body.accelX / STANDARD_GRAVITY
        val kickY = body.accelY / STANDARD_GRAVITY
        val pullX = downX + kickX * FaceSpec.MOTION_ACCEL_GAIN
        val pullY = downY + kickY * FaceSpec.MOTION_ACCEL_GAIN

        // Roll means nothing when the screen lies flat, so levelling fades out as gravity leaves the screen plane.
        val inPlane = sqrt(downX * downX + downY * downY)
        val levelWeight = ((inPlane - FaceSpec.LEVEL_MIN_GRAVITY) / FaceSpec.LEVEL_MIN_GRAVITY).coerceIn(0f, 1f)
        val rollTarget = (body.rollDegrees * levelWeight).coerceIn(-FaceSpec.LEVEL_MAX_DEGREES, FaceSpec.LEVEL_MAX_DEGREES)
        // Sway is measured from the upright rest pose, where the designed layout applies.
        val swayX = pullX.coerceIn(-FaceSpec.SWAY_MAX_G, FaceSpec.SWAY_MAX_G)
        val swayY = (pullY - 1f).coerceIn(-FaceSpec.SWAY_MAX_G, FaceSpec.SWAY_MAX_G)

        while (pending >= FaceSpec.MOTION_STEP_SECONDS) {
            pending -= FaceSpec.MOTION_STEP_SECONDS
            val dt = FaceSpec.MOTION_STEP_SECONDS
            liquid.step(
                downX, downY, kickX, kickY,
                body.rotationX, body.rotationY, body.rotationZ,
                screenHeight, dt,
            )
            rollSpring.step(rollTarget, dt)
            eyeSpringX.step(swayX * FaceSpec.EYE_SWAY, dt)
            eyeSpringY.step(swayY * FaceSpec.EYE_SWAY, dt)
            mouthSpringX.step(swayX * FaceSpec.MOUTH_SWAY, dt)
            mouthSpringY.step(swayY * FaceSpec.MOUTH_SWAY, dt)
        }

        roll = rollSpring.value
        // The springs work in screen axes; the face draws them inside its own turned frame.
        val radians = Math.toRadians(roll.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        eyeX = eyeSpringX.value * c + eyeSpringY.value * s
        eyeY = eyeSpringY.value * c - eyeSpringX.value * s
        mouthX = mouthSpringX.value * c + mouthSpringY.value * s
        mouthY = mouthSpringY.value * c - mouthSpringX.value * s
    }
}
