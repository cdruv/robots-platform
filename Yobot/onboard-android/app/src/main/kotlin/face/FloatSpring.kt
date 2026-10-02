package com.vadymsidorov.yobot.face

/**
 * Critically damped spring, integrated implicitly so it stays stable at any frame time.
 * [omega] is the natural frequency in rad/s; higher settles faster (~4.7/omega seconds to 99%).
 */
class FloatSpring(initial: Float, var omega: Float) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial

    fun step(dt: Float): Float {
        val f = 1f + 2f * dt * omega
        val oo = omega * omega
        val hoo = dt * oo
        val hhoo = dt * hoo
        val detInv = 1f / (f + hhoo)
        val x = (f * value + dt * velocity + hhoo * target) * detInv
        velocity = (velocity + hoo * (target - value)) * detInv
        value = x
        return value
    }

    fun snap(to: Float) {
        target = to
        value = to
        velocity = 0f
    }
}
