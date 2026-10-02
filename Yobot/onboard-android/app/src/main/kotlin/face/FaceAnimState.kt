package com.vadymsidorov.yobot.face

import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.output.FaceState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Morph target for one expression. Lid and mouth values are fractions of eye/mouth size. */
internal class ExpressionShape(
    val open: Float = 1f,
    val width: Float = 1f,
    val height: Float = 1f,
    /** Top-lid slant: + lowers the inner corners (angry), − the outer ones (sad). */
    val lidTilt: Float = 0f,
    val topLid: Float = 0f,
    /** Upward-curving bottom lid that turns eyes into happy crescents. */
    val bottomLid: Float = 0f,
    /** −1 frown … +1 smile. */
    val mouthCurve: Float = 0.15f,
    val mouthWidth: Float = 1f,
    val mouthOpen: Float = 0f,
    /** + makes the screen-right eye larger and the left smaller. */
    val asymmetry: Float = 0f,
    val wobble: Float = 0f,
    val red: Float,
    val green: Float,
    val blue: Float,
)

internal val shapes: Map<Expression, ExpressionShape> = mapOf(
    Expression.Neutral to ExpressionShape(red = 0.35f, green = 0.85f, blue = 1f),
    Expression.Happy to ExpressionShape(width = 1.05f, height = 0.95f, bottomLid = 0.45f, mouthCurve = 0.85f, mouthWidth = 1.2f, mouthOpen = 0.08f, red = 1f, green = 0.8f, blue = 0.35f),
    Expression.Sad to ExpressionShape(open = 0.85f, width = 0.95f, height = 0.9f, lidTilt = -0.3f, topLid = 0.15f, mouthCurve = -0.6f, mouthWidth = 0.8f, red = 0.35f, green = 0.5f, blue = 1f),
    Expression.Surprised to ExpressionShape(width = 1.1f, height = 1.18f, mouthCurve = 0f, mouthWidth = 0.42f, mouthOpen = 0.75f, red = 0.75f, green = 0.95f, blue = 1f),
    Expression.Curious to ExpressionShape(height = 1.05f, topLid = 0.05f, mouthCurve = 0.1f, mouthWidth = 0.7f, mouthOpen = 0.1f, asymmetry = 0.22f, red = 0.4f, green = 1f, blue = 0.75f),
    Expression.Thinking to ExpressionShape(open = 0.9f, width = 0.95f, height = 0.9f, lidTilt = 0.05f, topLid = 0.25f, mouthCurve = -0.05f, mouthWidth = 0.55f, asymmetry = 0.12f, red = 0.68f, green = 0.55f, blue = 1f),
    Expression.Sleepy to ExpressionShape(open = 0.4f, width = 1.05f, height = 0.9f, lidTilt = -0.1f, topLid = 0.35f, mouthCurve = 0f, mouthWidth = 0.55f, mouthOpen = 0.1f, red = 0.35f, green = 0.42f, blue = 0.75f),
    Expression.Angry to ExpressionShape(open = 0.9f, height = 0.85f, lidTilt = 0.42f, topLid = 0.2f, bottomLid = 0.08f, mouthCurve = -0.45f, mouthWidth = 0.9f, red = 1f, green = 0.32f, blue = 0.25f),
    Expression.Confused to ExpressionShape(open = 0.95f, lidTilt = 0.15f, topLid = 0.08f, mouthCurve = -0.25f, mouthWidth = 0.7f, asymmetry = -0.3f, red = 1f, green = 0.62f, blue = 0.3f),
    Expression.Dizzy to ExpressionShape(open = 0.9f, topLid = 0.05f, mouthCurve = -0.1f, mouthWidth = 0.8f, mouthOpen = 0.25f, wobble = 1f, red = 1f, green = 0.45f, blue = 0.9f),
)

/**
 * Everything that moves on the face, advanced once per frame by [advance] and read by the
 * draw code. Plain mutable fields and preallocated arrays: no allocation per frame.
 */
class FaceAnimState(initial: FaceState, seed: Int = 7) {
    var target: FaceState = initial

    private val random = Random(seed)
    private val shape0 = shapes.getValue(initial.expression)

    val open = FloatSpring(shape0.open, 14f)
    val width = FloatSpring(shape0.width, 12f)
    val height = FloatSpring(shape0.height, 12f)
    val lidTilt = FloatSpring(shape0.lidTilt, 12f)
    val topLid = FloatSpring(shape0.topLid, 12f)
    val bottomLid = FloatSpring(shape0.bottomLid, 12f)
    val mouthCurve = FloatSpring(shape0.mouthCurve, 10f)
    val mouthWidth = FloatSpring(shape0.mouthWidth, 10f)
    val mouthOpen = FloatSpring(shape0.mouthOpen, 14f)
    val asymmetry = FloatSpring(shape0.asymmetry, 8f)
    val wobble = FloatSpring(shape0.wobble, 5f)
    val red = FloatSpring(shape0.red, 6f)
    val green = FloatSpring(shape0.green, 6f)
    val blue = FloatSpring(shape0.blue, 6f)
    val gazeX = FloatSpring(initial.gazeX, 28f)
    val gazeY = FloatSpring(initial.gazeY, 28f)
    val speaking = FloatSpring(if (initial.speaking) 1f else 0f, 12f)
    val ringAlpha = FloatSpring(if (initial.thinking) 1f else 0f, 5f)
    val energy = FloatSpring(initial.energy, 2f)

    private val springs = arrayOf(
        open, width, height, lidTilt, topLid, bottomLid, mouthCurve, mouthWidth, mouthOpen,
        asymmetry, wobble, red, green, blue, gazeX, gazeY, speaking, ringAlpha, energy,
    )

    /** Seconds since start; drives periodic motion. */
    var time = 0f
        private set

    /** 0 = open, 1 = fully closed. */
    var blink = 0f
        private set
    private var nextBlinkAt = 1.5f
    private var blinkStart = -1f
    private var doubleBlink = false

    private var wanderX = 0f
    private var wanderY = 0f
    private var nextWanderAt = 2f

    private var glanceX = 0f
    private var glanceY = 0f
    private var glanceUntil = -1f
    private var lastGlanceId = -1L

    /** Mouth opening including speech flapping, 0..1. */
    var mouthOpenNow = 0f
        private set

    val particleCount = 36
    val particleAngle = FloatArray(particleCount) { it * (2f * PI.toFloat() / particleCount) }
    val particleRadius = FloatArray(particleCount) { 0.85f + random.nextFloat() * 0.3f }
    val particleSize = FloatArray(particleCount) { 0.4f + random.nextFloat() * 0.6f }
    val particleSpeed = FloatArray(particleCount) { 0.8f + random.nextFloat() * 0.5f }

    fun glance(id: Long, x: Float, y: Float, durationMs: Long) {
        if (id == lastGlanceId) return
        lastGlanceId = id
        glanceX = x
        glanceY = y
        glanceUntil = time + durationMs / 1000f
    }

    fun advance(dtRaw: Float) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        time += dt
        val t = target
        val shape = shapes.getValue(t.expression)

        open.target = shape.open
        width.target = shape.width
        height.target = shape.height
        lidTilt.target = shape.lidTilt
        topLid.target = shape.topLid + (1f - t.energy) * 0.25f
        bottomLid.target = shape.bottomLid
        mouthCurve.target = shape.mouthCurve
        mouthWidth.target = shape.mouthWidth
        mouthOpen.target = shape.mouthOpen
        asymmetry.target = shape.asymmetry
        wobble.target = shape.wobble
        red.target = shape.red
        green.target = shape.green
        blue.target = shape.blue
        speaking.target = if (t.speaking) 1f else 0f
        ringAlpha.target = if (t.thinking) 1f else 0f
        energy.target = t.energy

        updateGaze(t)
        updateBlink()
        for (spring in springs) spring.step(dt)

        val flap = abs(sin(time * 11f) * sin(time * 3.7f + 1f))
        mouthOpenNow = (mouthOpen.value + speaking.value * (0.15f + 0.45f * flap)).coerceIn(0f, 1f)

        val spin = 1.2f + wobble.value * 2.5f
        for (i in 0 until particleCount) particleAngle[i] += dt * spin * particleSpeed[i]
    }

    private fun updateGaze(t: FaceState) {
        if (time >= nextWanderAt) {
            // Saccade: jump to a new nearby point, sometimes back to center.
            val range = if (abs(t.gazeX) + abs(t.gazeY) > 0.05f) 0.12f else 0.35f
            val centered = random.nextFloat() < 0.3f
            wanderX = if (centered) 0f else (random.nextFloat() * 2f - 1f) * range
            wanderY = if (centered) 0f else (random.nextFloat() * 2f - 1f) * range * 0.6f
            nextWanderAt = time + 1.2f + random.nextFloat() * 3f * (1.5f - t.energy * 0.5f)
        }
        if (time < glanceUntil) {
            gazeX.target = glanceX
            gazeY.target = glanceY
        } else {
            gazeX.target = (t.gazeX + wanderX).coerceIn(-1f, 1f)
            gazeY.target = (t.gazeY + wanderY).coerceIn(-1f, 1f)
        }
    }

    private fun updateBlink() {
        if (blinkStart < 0f && time >= nextBlinkAt) {
            blinkStart = time
            doubleBlink = random.nextFloat() < 0.15f
        }
        if (blinkStart < 0f) {
            blink = 0f
            return
        }
        val duration = 0.16f
        val phase = (time - blinkStart) / duration
        when {
            phase < 1f -> blink = 1f - abs(phase * 2f - 1f)
            doubleBlink && phase < 2.2f -> blink = if (phase < 1.2f) 0f else 1f - abs((phase - 1.2f) * 2f - 1f)
            else -> {
                blink = 0f
                blinkStart = -1f
                nextBlinkAt = time + 2f + random.nextFloat() * 4f
            }
        }
    }
}
