package com.vadymsidorov.walky.face

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val HEAT_LEVELS = 8
private const val LAYERS = 3

/**
 * The rain as a 2D liquid drawn in a pixel-art style: flat, hard-edged squares on a coarse grid.
 *
 * Motion: every particle is carried by an ambient flow of drifting swirls, pulled by gravity,
 * and thrown about by changes the IMU reports: body acceleration, a change of tilt, and
 * rotation (the liquid lags behind a turning body, as a real one would). Flow and gravity are
 * of similar strength, so at rest the liquid keeps churning and is only denser toward the low
 * side.
 *
 * Depth: each particle has a fixed depth. Near ones are larger, brighter and react more; far
 * ones are single dim grid cells. Particles only interact with others in the same
 * of [LAYERS] depth layers, where they push each other apart to keep a rest distance.
 *
 * Life: each particle fades in, lives a few seconds, fades out and respawns as rain on the
 * high edge, in a burst at a wandering emitter, or anywhere on the screen.
 *
 * Positions are in design dp with the origin at the top-left of the screen. Particles are
 * stored far to near, which is also the draw order. Nothing is allocated per step or per draw.
 */
internal class FaceLiquid(private val random: Random = Random(0x70B07)) {
    private val count = FaceSpec.LIQUID_PARTICLES
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val ax = FloatArray(count)
    private val ay = FloatArray(count)
    private val age = FloatArray(count) { FaceSpec.LIQUID_FADE_SECONDS }
    private val life = FloatArray(count) { random.nextFloat() * FaceSpec.LIQUID_MAX_LIFE_SECONDS }

    // Fixed per particle. Depth runs 0 (far) to 1 (near), with more particles far than near.
    private val depth = FloatArray(count) { (it / (count - 1f)).pow(FaceSpec.LIQUID_DEPTH_SKEW) }
    private val layer = IntArray(count) { min(LAYERS - 1, (depth[it] * LAYERS).toInt()) }
    /** Side of the square in grid cells: one for far particles, up to the maximum for the nearest. */
    private val cells = IntArray(count) {
        val near = depth[it] * depth[it] * depth[it]
        (1f + (FaceSpec.LIQUID_MAX_CELLS - 1) * near * (0.7f + 0.6f * random.nextFloat()))
            .roundToInt().coerceIn(1, FaceSpec.LIQUID_MAX_CELLS)
    }
    private val size = FloatArray(count) { cells[it] * FaceSpec.LIQUID_CELL }
    private val tint = IntArray(count) {
        val pick = random.nextFloat() * FaceSpec.LiquidTintShares.sum()
        var sum = 0f
        FaceSpec.LiquidTintShares.indexOfFirst { share -> sum += share; pick < sum }.coerceAtLeast(0)
    }

    // Uniform grid of reach-sized cells: cellHead is the first particle in a cell, next chains the rest.
    private val next = IntArray(count)
    private var cellHead = IntArray(0)
    private var columns = 0
    private var rows = 0
    private var height = 0f

    private var time = 0f
    private var emitterX = FaceSpec.FRAME_WIDTH / 2f
    private var emitterY = FaceSpec.FRAME_HEIGHT / 2f
    private var emitterSeconds = 0f
    // Slow average of the gravity direction; the difference from it is a change of tilt.
    private var settledDownX = 0f
    private var settledDownY = 1f

    private val palette = IntArray(FaceSpec.LiquidTints.size * HEAT_LEVELS) {
        val base = FaceSpec.LiquidTints[it / HEAT_LEVELS]
        lerp(base, FaceSpec.LiquidHot, FaceSpec.LIQUID_HOT_MIX * (it % HEAT_LEVELS) / (HEAT_LEVELS - 1f)).toArgb()
    }
    private val paint = Paint()

    init {
        for (i in 0 until count) {
            x[i] = random.nextFloat() * FaceSpec.FRAME_WIDTH
            y[i] = random.nextFloat() * FaceSpec.FRAME_HEIGHT
        }
    }

    /**
     * Advances the simulation by [dt] seconds. Forces are in g along screen axes (x right, y
     * down): ([downX], [downY]) is gravity and ([kickX], [kickY]) comes from body acceleration.
     * Rotation is in rad/s about the device axes: [turnX] and [turnY] slide the liquid across
     * the screen, [turnZ] swirls it about the centre. [screenHeight] is in design dp.
     */
    fun step(
        downX: Float, downY: Float, kickX: Float, kickY: Float,
        turnX: Float, turnY: Float, turnZ: Float,
        screenHeight: Float, dt: Float,
    ) {
        if (screenHeight != height) resize(screenHeight)
        time += dt
        emitterSeconds -= dt
        if (emitterSeconds <= 0f) {
            emitterX = random.nextFloat() * FaceSpec.FRAME_WIDTH
            emitterY = random.nextFloat() * height
            emitterSeconds = FaceSpec.LIQUID_EMITTER_MIN_SECONDS +
                random.nextFloat() * (FaceSpec.LIQUID_EMITTER_MAX_SECONDS - FaceSpec.LIQUID_EMITTER_MIN_SECONDS)
        }
        settledDownX += (downX - settledDownX) * dt / FaceSpec.LIQUID_TILT_SETTLE_SECONDS
        settledDownY += (downY - settledDownY) * dt / FaceSpec.LIQUID_TILT_SETTLE_SECONDS

        cellHead.fill(-1)
        for (i in 0 until count) {
            val cell = cellOf(x[i], y[i])
            next[i] = cellHead[cell]
            cellHead[cell] = i
        }

        // Steady gravity, plus the short-lived pushes from acceleration and from a change of tilt.
        val forceX = (downX + kickX * FaceSpec.LIQUID_ACCEL_GAIN + (downX - settledDownX) * FaceSpec.LIQUID_TILT_GAIN) *
            FaceSpec.LIQUID_GRAVITY
        val forceY = (downY + kickY * FaceSpec.LIQUID_ACCEL_GAIN + (downY - settledDownY) * FaceSpec.LIQUID_TILT_GAIN) *
            FaceSpec.LIQUID_GRAVITY
        // The liquid stays behind when the body turns. Seen from the screen it slides the other
        // way: sideways for a turn about the vertical axis, vertically about the horizontal one,
        // and around the centre for a turn in the screen plane.
        val slideX = turnY * FaceSpec.LIQUID_TURN_SLIDE
        val slideY = -turnX * FaceSpec.LIQUID_TURN_SLIDE
        val swirl = turnZ * FaceSpec.LIQUID_TURN_SWIRL
        val centerX = FaceSpec.FRAME_WIDTH / 2f
        val centerY = height / 2f
        for (i in 0 until count) {
            val respond = FaceSpec.LIQUID_FAR_RESPONSE + (FaceSpec.LIQUID_NEAR_RESPONSE - FaceSpec.LIQUID_FAR_RESPONSE) * depth[i]
            ax[i] = (forceX + (slideX - swirl * (y[i] - centerY)) * FaceSpec.LIQUID_TURN_RESPONSE) * respond
            ay[i] = (forceY + (slideY + swirl * (x[i] - centerX)) * FaceSpec.LIQUID_TURN_RESPONSE) * respond
        }

        val reach = FaceSpec.LIQUID_REACH
        for (i in 0 until count) {
            val column = (x[i] / reach).toInt()
            val row = (y[i] / reach).toInt()
            for (r in row - 1..row + 1) {
                if (r < 0 || r >= rows) continue
                for (c in column - 1..column + 1) {
                    if (c < 0 || c >= columns) continue
                    var j = cellHead[r * columns + c]
                    while (j >= 0) {
                        if (j > i && layer[j] == layer[i]) interact(i, j, reach)
                        j = next[j]
                    }
                }
            }
        }

        // Ambient flow: two grids of swirls whose phases drift at unrelated rates, so the
        // pattern never repeats. Each is the curl of a stream function, so it stirs without
        // herding particles into clumps.
        val wave = 2f * PI.toFloat() / FaceSpec.LIQUID_FLOW_SIZE
        val fineWave = 2f * PI.toFloat() / FaceSpec.LIQUID_FLOW_FINE_SIZE
        val phaseX = time * 0.31f
        val phaseY = time * 0.23f + 1f
        val finePhaseX = time * -0.47f + 2f
        val finePhaseY = time * 0.39f + 4f
        val follow = FaceSpec.LIQUID_FLOW_FOLLOW
        val margin = FaceSpec.LIQUID_MARGIN
        val right = FaceSpec.FRAME_WIDTH - margin
        val bottom = height - margin
        for (i in 0 until count) {
            // Far particles drift slower than near ones.
            val drift = FaceSpec.LIQUID_FAR_FLOW + (1f - FaceSpec.LIQUID_FAR_FLOW) * depth[i]
            val flowX = drift * (
                FaceSpec.LIQUID_FLOW_SPEED * sin(wave * x[i] + phaseX) * cos(wave * y[i] + phaseY) +
                    FaceSpec.LIQUID_FLOW_FINE_SPEED * sin(fineWave * x[i] + finePhaseX) * cos(fineWave * y[i] + finePhaseY)
                )
            val flowY = -drift * (
                FaceSpec.LIQUID_FLOW_SPEED * cos(wave * x[i] + phaseX) * sin(wave * y[i] + phaseY) +
                    FaceSpec.LIQUID_FLOW_FINE_SPEED * cos(fineWave * x[i] + finePhaseX) * sin(fineWave * y[i] + finePhaseY)
                )
            var velocityX = vx[i] + (ax[i] + (flowX - vx[i]) * follow) * dt
            var velocityY = vy[i] + (ay[i] + (flowY - vy[i]) * follow) * dt
            val speed = sqrt(velocityX * velocityX + velocityY * velocityY)
            if (speed > FaceSpec.LIQUID_MAX_SPEED) {
                velocityX *= FaceSpec.LIQUID_MAX_SPEED / speed
                velocityY *= FaceSpec.LIQUID_MAX_SPEED / speed
            }
            var px = x[i] + velocityX * dt
            var py = y[i] + velocityY * dt
            if (px < margin) {
                px = margin
                if (velocityX < 0f) velocityX *= -FaceSpec.LIQUID_BOUNCE
            } else if (px > right) {
                px = right
                if (velocityX > 0f) velocityX *= -FaceSpec.LIQUID_BOUNCE
            }
            if (py < margin) {
                py = margin
                if (velocityY < 0f) velocityY *= -FaceSpec.LIQUID_BOUNCE
            } else if (py > bottom) {
                py = bottom
                if (velocityY > 0f) velocityY *= -FaceSpec.LIQUID_BOUNCE
            }
            x[i] = px
            y[i] = py
            vx[i] = velocityX
            vy[i] = velocityY
            age[i] += dt
            life[i] -= dt
            if (life[i] <= 0f) respawn(i, downX, downY)
        }
    }

    /** Pushes two particles apart when they are inside their rest distance, and damps the speed at which they approach. */
    private fun interact(i: Int, j: Int, reach: Float) {
        val dx = x[j] - x[i]
        val dy = y[j] - y[i]
        val distanceSquared = dx * dx + dy * dy
        val rest = min(FaceSpec.LIQUID_REST_GAP + (size[i] + size[j]) * 0.9f, reach)
        if (distanceSquared >= rest * rest) return
        val distance = sqrt(distanceSquared)
        // Two particles on the same spot have no direction to part along: pick one.
        val nx = if (distance > 0.001f) dx / distance else 1f
        val ny = if (distance > 0.001f) dy / distance else 0f
        val overlap = 1f - distance / rest
        val closing = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
        val push = overlap * (FaceSpec.LIQUID_STIFFNESS - FaceSpec.LIQUID_VISCOSITY * closing)
        ax[i] -= push * nx
        ay[i] -= push * ny
        ax[j] += push * nx
        ay[j] += push * ny
    }

    /** Restarts a particle: as rain, in a burst at the emitter, or anywhere. */
    private fun respawn(i: Int, downX: Float, downY: Float) {
        val margin = FaceSpec.LIQUID_MARGIN
        var px = random.nextFloat() * FaceSpec.FRAME_WIDTH
        var py = random.nextFloat() * height
        vx[i] = 0f
        vy[i] = 0f
        val kind = random.nextFloat()
        if (kind < FaceSpec.LIQUID_RAIN_SHARE) {
            // Onto the edge gravity pulls away from; stays where it is when the screen lies flat.
            px -= downX * 2f * height
            py -= downY * 2f * height
        } else if (kind < FaceSpec.LIQUID_RAIN_SHARE + FaceSpec.LIQUID_BURST_SHARE) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val speed = FaceSpec.LIQUID_BURST_MIN_SPEED +
                random.nextFloat() * (FaceSpec.LIQUID_BURST_MAX_SPEED - FaceSpec.LIQUID_BURST_MIN_SPEED)
            px = emitterX + cos(angle) * random.nextFloat() * FaceSpec.LIQUID_BURST_SPREAD
            py = emitterY + sin(angle) * random.nextFloat() * FaceSpec.LIQUID_BURST_SPREAD
            vx[i] = cos(angle) * speed
            vy[i] = sin(angle) * speed
        }
        x[i] = px.coerceIn(margin, FaceSpec.FRAME_WIDTH - margin)
        y[i] = py.coerceIn(margin, height - margin)
        age[i] = 0f
        life[i] = FaceSpec.LIQUID_MIN_LIFE_SECONDS +
            random.nextFloat() * (FaceSpec.LIQUID_MAX_LIFE_SECONDS - FaceSpec.LIQUID_MIN_LIFE_SECONDS)
    }

    private fun resize(screenHeight: Float) {
        height = screenHeight
        columns = ceil(FaceSpec.FRAME_WIDTH / FaceSpec.LIQUID_REACH).toInt() + 1
        rows = ceil(screenHeight / FaceSpec.LIQUID_REACH).toInt() + 1
        cellHead = IntArray(columns * rows)
    }

    private fun cellOf(px: Float, py: Float): Int {
        val column = (px / FaceSpec.LIQUID_REACH).toInt().coerceIn(0, columns - 1)
        val row = (py / FaceSpec.LIQUID_REACH).toInt().coerceIn(0, rows - 1)
        return row * columns + column
    }

    /**
     * [unit] is pixels per design dp. Every square is snapped to the grid of [FaceSpec.LIQUID_CELL]
     * cells and to whole screen pixels, so edges stay hard and motion moves in steps. Fast
     * particles turn a hotter colour.
     */
    fun draw(scope: DrawScope, unit: Float, alpha: Float) = scope.drawIntoCanvas {
        val canvas = it.nativeCanvas
        val cell = FaceSpec.LIQUID_CELL
        for (i in 0 until count) {
            val fade = min(1f, min(age[i], life[i]) / FaceSpec.LIQUID_FADE_SECONDS)
            val opacity = FaceSpec.LIQUID_FAR_ALPHA + (FaceSpec.LIQUID_NEAR_ALPHA - FaceSpec.LIQUID_FAR_ALPHA) * depth[i]
            val visible = alpha * fade * opacity
            if (visible <= 0.004f) continue
            val speed = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            val heat = min(1f, speed / FaceSpec.LIQUID_HOT_SPEED)
            paint.color = palette[tint[i] * HEAT_LEVELS + (heat * (HEAT_LEVELS - 1) + 0.5f).toInt()]
            paint.alpha = (visible * 255f + 0.5f).toInt()
            val column = ((x[i] - size[i] / 2f) / cell).roundToInt()
            val row = ((y[i] - size[i] / 2f) / cell).roundToInt()
            canvas.drawRect(
                (column * cell * unit).roundToInt().toFloat(),
                (row * cell * unit).roundToInt().toFloat(),
                ((column + cells[i]) * cell * unit).roundToInt().toFloat(),
                ((row + cells[i]) * cell * unit).roundToInt().toFloat(),
                paint,
            )
        }
    }
}
