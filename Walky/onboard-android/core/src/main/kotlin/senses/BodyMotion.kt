package com.vadymsidorov.walky.core.senses

import kotlin.math.atan2
import kotlin.math.sqrt

const val STANDARD_GRAVITY = 9.80665f

/**
 * One IMU sample in Android device axes (x right, y up the screen, z out of the screen), m/s².
 * [gravityX]..[gravityZ] is the gravity reading, which points up: (0, 9.8, 0) for an upright
 * phone. [accelX]..[accelZ] is the acceleration of the body with gravity removed.
 * [rotationX]..[rotationZ] is the rate of turn about each axis in rad/s, positive
 * counter-clockwise seen from the axis tip, and zero when there is no gyroscope. The default is
 * an upright body at rest.
 */
data class BodyMotion(
    val gravityX: Float = 0f,
    val gravityY: Float = STANDARD_GRAVITY,
    val gravityZ: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
) {
    /** Rotation in the screen plane, degrees: 0 upright, positive with the left edge down (seen facing the screen). */
    val rollDegrees: Float get() = Math.toDegrees(atan2(gravityX, gravityY).toDouble()).toFloat()

    /** Lean out of the vertical, degrees: 0 upright, positive leaning back, 90 lying screen-up. */
    val pitchDegrees: Float
        get() = Math.toDegrees(atan2(gravityZ, sqrt(gravityX * gravityX + gravityY * gravityY)).toDouble()).toFloat()

    val accelMagnitude: Float get() = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
}
