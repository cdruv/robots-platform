package com.vadymsidorov.yobot.face

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.core.output.MouthMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

private val ExpressionEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** Keeps the blink at real speed when system animations are switched off. */
private object RealTimeMotion : MotionDurationScale {
    override val scaleFactor = 1f
}

/**
 * The "Rain + Glow" face: falling glyph rain, a vignette, two glowing eyes with pupils, and a
 * bar mouth. It draws whatever [target] says and adds ambient motion (rain, glow, gaze wander,
 * blinks) on independent clocks that expression changes never reset.
 *
 * With [overlayVisible] the face recedes behind the debug overlay. With [reducedMotion] only
 * the blink moves. Reactions are not rendered yet.
 */
@Composable
fun FaceRenderer(
    target: FaceState,
    modifier: Modifier = Modifier,
    overlayVisible: Boolean = false,
    reducedMotion: Boolean = systemAnimationsOff(),
) {
    val mouth = rememberUpdatedState(target)
    val pose = remember { mutableStateOf(eyePose(target.expression)) }
    LaunchedEffect(target.expression) {
        val start = pose.value
        val end = eyePose(target.expression)
        if (start != end) {
            animate(0f, 1f, animationSpec = tween(FaceSpec.EXPRESSION_MS, easing = ExpressionEasing)) { t, _ ->
                pose.value = lerp(start, end, t)
            }
        }
    }
    val overlay = animateFloatAsState(if (overlayVisible) 1f else 0f, tween(FaceSpec.OVERLAY_FADE_MS), label = "overlay")

    // One clock for rain, glow and the mouth cursor. It stays at zero under reduced motion.
    val clockNanos = remember { mutableLongStateOf(0L) }
    val gaze = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            clockNanos.longValue = 0L
            gaze.snapTo(Offset.Zero)
            return@LaunchedEffect
        }
        launch {
            val origin = withFrameNanos { it } - clockNanos.longValue
            while (true) withFrameNanos { clockNanos.longValue = it - origin }
        }
        while (true) {
            delay(Random.nextLong(FaceSpec.GAZE_MIN_HOLD_MS, FaceSpec.GAZE_MAX_HOLD_MS))
            gaze.animateTo(
                Offset(
                    (Random.nextFloat() * 2f - 1f) * FaceSpec.GAZE_RANGE_X,
                    (Random.nextFloat() * 2f - 1f) * FaceSpec.GAZE_RANGE_Y,
                ),
                tween(FaceSpec.GAZE_MOVE_MS, easing = EaseInOut),
            )
        }
    }

    val blinkLeft = remember { Animatable(1f) }
    val blinkRight = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        withContext(RealTimeMotion) {
            while (true) {
                delay(Random.nextLong(FaceSpec.BLINK_MIN_INTERVAL_MS, FaceSpec.BLINK_MAX_INTERVAL_MS))
                val blinks = if (Random.nextInt(FaceSpec.BLINK_DOUBLE_ONE_IN) == 0) 2 else 1
                repeat(blinks) { i ->
                    if (i > 0) delay(FaceSpec.BLINK_DOUBLE_GAP_MS)
                    coroutineScope {
                        launch { blinkLeft.blink() }
                        launch {
                            delay(FaceSpec.BLINK_RIGHT_EYE_LAG_MS)
                            blinkRight.blink()
                        }
                    }
                }
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val columns = remember { rainColumns() }
    val shapes = remember { GlowRectPainter() }
    val pupilClip = remember { Path() }

    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val unit = size.width / FaceSpec.FRAME_WIDTH
            val frameTop = (size.height - FaceSpec.FRAME_HEIGHT * unit) / 2f
            val rain = RainLayer(columns, measurer, this, unit)
            val vignetteCenter = Offset(size.width * 0.5f, size.height * 0.45f)
            val vignetteRadius = size.width * 0.8f
            val vignetteSquash = size.height * 0.55f / vignetteRadius
            val vignette = Brush.radialGradient(
                0.25f to FaceSpec.Background.copy(alpha = 0f),
                0.9f to FaceSpec.Background,
                center = vignetteCenter,
                radius = vignetteRadius,
            )

            onDrawBehind {
                val seconds = clockNanos.longValue / 1e9
                val dim = overlay.value
                val state = mouth.value

                drawRect(FaceSpec.Background)
                rain.draw(this, seconds, mix(FaceSpec.RAIN_ALPHA, FaceSpec.RAIN_ALPHA_OVERLAY, dim))
                scale(1f, vignetteSquash, pivot = vignetteCenter) {
                    val reach = size.height / vignetteSquash
                    drawRect(vignette, Offset(0f, vignetteCenter.y - reach), Size(size.width, 2f * reach))
                }

                translate(top = frameTop) {
                    val breathe = (0.5 - 0.5 * cos(2.0 * PI * seconds / FaceSpec.GLOW_SECONDS)).toFloat()
                    val eye = pose.value
                    drawEye(shapes, pupilClip, eye, FaceSpec.EYE_LEFT_X, blinkLeft.value, gaze.value, breathe, dim, unit)
                    drawEye(shapes, pupilClip, eye, FaceSpec.EYE_RIGHT_X, blinkRight.value, gaze.value, breathe, dim, unit)
                    drawMouth(shapes, state.mouth, state.mouthLevel, seconds, 1f - dim, unit)
                }
            }
        },
    )
}

private suspend fun Animatable<Float, *>.blink() {
    animateTo(FaceSpec.BLINK_CLOSED_SCALE, tween(FaceSpec.BLINK_CLOSE_MS))
    animateTo(1f, tween(FaceSpec.BLINK_OPEN_MS))
}

/** [dim] is 0 for the normal face and 1 behind the debug overlay, where only a faint rim remains. */
private fun DrawScope.drawEye(
    shapes: GlowRectPainter,
    pupilClip: Path,
    eye: EyePose,
    centerX: Float,
    blink: Float,
    gaze: Offset,
    breathe: Float,
    dim: Float,
    unit: Float,
) {
    val center = Offset((centerX + eye.offsetX) * unit, eye.centerY * unit)
    val halfWidth = eye.width * unit / 2f
    val halfHeight = eye.height * unit / 2f
    val (topRadius, bottomRadius) = fitRadii(eye.width * unit, eye.height * unit, eye.topRadius * unit, eye.bottomRadius * unit)
    val stroke = FaceSpec.EYE_STROKE * unit
    val lit = 1f - dim
    val glow = eye.glowAlpha * lit

    scale(1f, mix(1f, blink, eye.blink), pivot = center) {
        shapes.draw(
            this, center, halfWidth, halfHeight, topRadius, bottomRadius,
            fill = eye.fill.copy(alpha = lit),
            glow = eye.glow,
            outerSigma = mix(FaceSpec.OUTER_GLOW_SIGMA, FaceSpec.OUTER_GLOW_SIGMA_PEAK, breathe) * unit,
            outerAlpha = mix(FaceSpec.OUTER_GLOW_ALPHA, FaceSpec.OUTER_GLOW_ALPHA_PEAK, breathe) * glow,
            rim = eye.rim.copy(alpha = mix(1f, FaceSpec.EYE_OVERLAY_RIM_ALPHA, dim)),
            stroke = stroke,
            innerSigma = mix(FaceSpec.INNER_GLOW_SIGMA, FaceSpec.INNER_GLOW_SIGMA_PEAK, breathe) * unit,
            innerAlpha = mix(FaceSpec.INNER_GLOW_ALPHA, FaceSpec.INNER_GLOW_ALPHA_PEAK, breathe) * glow,
        )

        val pupilAlpha = eye.pupilAlpha * lit
        if (pupilAlpha > 0.004f) {
            // The rim clips the pupil, as overflow:hidden does in the reference.
            roundRectPath(pupilClip, center, halfWidth - stroke, halfHeight - stroke, topRadius - stroke, bottomRadius - stroke)
            clipPath(pupilClip) {
                val pupil = center + Offset(
                    (gaze.x * eye.wander + eye.gazeX) * unit,
                    (gaze.y * eye.wander + eye.gazeY) * unit,
                )
                val scaled = eye.pupilScale * unit
                val pupilHalfWidth = FaceSpec.PUPIL_WIDTH * scaled / 2f
                val pupilHalfHeight = FaceSpec.PUPIL_HEIGHT * scaled / 2f
                shapes.draw(
                    this, pupil, pupilHalfWidth, pupilHalfHeight,
                    FaceSpec.PUPIL_RADIUS * scaled, FaceSpec.PUPIL_RADIUS * scaled,
                    fill = FaceSpec.Pupil,
                    glow = FaceSpec.EyeRim,
                    outerSigma = FaceSpec.PUPIL_GLOW_SIGMA * unit,
                    outerAlpha = 1f,
                    alpha = pupilAlpha,
                )
                drawCircle(
                    FaceSpec.Catchlight,
                    radius = FaceSpec.CATCHLIGHT_RADIUS * scaled,
                    center = pupil + Offset(
                        FaceSpec.CATCHLIGHT_OFFSET * scaled - pupilHalfWidth,
                        FaceSpec.CATCHLIGHT_OFFSET * scaled - pupilHalfHeight,
                    ),
                    alpha = pupilAlpha,
                )
            }
        }
    }
}

private fun DrawScope.drawMouth(
    shapes: GlowRectPainter,
    mode: MouthMode,
    level: Float,
    seconds: Double,
    alpha: Float,
    unit: Float,
) {
    val cycle = ((seconds / FaceSpec.MOUTH_CYCLE_SECONDS) % 1.0).toFloat()
    val amount = level.coerceIn(0f, 1f)
    var centerX = FaceSpec.MOUTH_X
    val width: Float
    when (mode) {
        MouthMode.Idle -> {
            if (cycle >= 0.5f) return // hard cursor blink
            width = FaceSpec.MOUTH_WIDTH
        }
        MouthMode.Listening -> width = mix(FaceSpec.MOUTH_WIDTH, FaceSpec.MOUTH_LISTENING_MAX_WIDTH, amount)
        MouthMode.Speaking -> width = mix(FaceSpec.MOUTH_SPEAKING_MIN_WIDTH, FaceSpec.MOUTH_SPEAKING_MAX_WIDTH, amount)
        MouthMode.Processing -> {
            width = FaceSpec.MOUTH_PROCESSING_SEGMENT
            centerX += (cycle - 0.5f) * (FaceSpec.MOUTH_WIDTH - width)
        }
    }
    val radius = FaceSpec.MOUTH_HEIGHT * unit / 2f
    shapes.draw(
        this, Offset(centerX * unit, FaceSpec.MOUTH_Y * unit), width * unit / 2f, radius, radius, radius,
        fill = FaceSpec.EyeRim,
        glow = FaceSpec.EyeRim,
        outerSigma = FaceSpec.MOUTH_GLOW_SIGMA * unit,
        outerAlpha = 1f,
        alpha = alpha,
    )
}

@Composable
private fun systemAnimationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Preview(name = "Idle", widthDp = 412, heightDp = 915)
@Composable
private fun IdlePreview() = FaceRenderer(FaceState(), reducedMotion = true)

@Preview(name = "Happy", widthDp = 412, heightDp = 915)
@Composable
private fun HappyPreview() = FaceRenderer(FaceState(Expression.Happy), reducedMotion = true)

@Preview(name = "Thinking", widthDp = 412, heightDp = 915)
@Composable
private fun ThinkingPreview() = FaceRenderer(FaceState(Expression.Thinking, MouthMode.Processing), reducedMotion = true)

@Preview(name = "Alert", widthDp = 412, heightDp = 915)
@Composable
private fun AlertPreview() = FaceRenderer(FaceState(Expression.Alert, MouthMode.Listening, 0.5f), reducedMotion = true)

@Preview(name = "Sleep", widthDp = 412, heightDp = 915)
@Composable
private fun SleepPreview() = FaceRenderer(FaceState(Expression.Sleep), reducedMotion = true)

@Preview(name = "Behind debug overlay", widthDp = 412, heightDp = 915)
@Composable
private fun OverlayPreview() = FaceRenderer(FaceState(), overlayVisible = true, reducedMotion = true)
