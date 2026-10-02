package com.vadymsidorov.yobot.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.output.Glance
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural face. Animation advances once per frame in [FaceAnimState]; only the draw
 * phase re-runs each frame, so the composition itself stays idle between target changes.
 */
@Composable
fun FaceRenderer(target: FaceState, modifier: Modifier = Modifier, glance: Glance? = null) {
    val anim = remember { FaceAnimState(target) }
    val painter = remember { FacePainter() }
    var frameTime by remember { mutableFloatStateOf(0f) }

    SideEffect {
        anim.target = target
        glance?.let { anim.glance(it.id, it.x, it.y, it.durationMs) }
    }
    LaunchedEffect(anim) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                anim.advance((now - last) / 1e9f)
                last = now
                frameTime = anim.time
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frameTime // Read in the draw scope: invalidates drawing only, not composition.
        painter.draw(this, anim)
    }
}

/** Owns reusable drawing objects so the per-frame path allocates nothing. */
private class FacePainter {
    private val glow = GlowBackground()
    private val leftEye = Path()
    private val rightEye = Path()
    private val mouth = Path()
    private var geometry = FaceGeometry(0f, 0f)
    private var stroke = Stroke()

    fun draw(scope: DrawScope, anim: FaceAnimState) = with(scope) {
        if (geometry.width != size.width || geometry.height != size.height) {
            geometry = FaceGeometry(size.width, size.height)
            stroke = Stroke(width = geometry.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        val geo = geometry
        val t = anim.time
        val energy = anim.energy.value
        val color = Color(anim.red.value.coerceIn(0f, 1f), anim.green.value.coerceIn(0f, 1f), anim.blue.value.coerceIn(0f, 1f))
        val bob = sin(t * 1.1f) * size.height * 0.006f * energy
        val breathe = 1f + 0.012f * sin(t * 1.6f) * energy

        glow.draw(this, Offset(geo.centerX, geo.eyesY + bob), color, 0.45f + 0.55f * energy, t)

        val gx = anim.gazeX.value * geo.gazeShiftX
        val gy = anim.gazeY.value * geo.gazeShiftY + bob
        withTransform({ scale(breathe, breathe, Offset(geo.centerX, geo.eyesY)) }) {
            drawEye(geo, anim, -1, leftEye, color, gx, gy)
            drawEye(geo, anim, 1, rightEye, color, gx, gy)
            drawMouth(geo, anim, color, gx, gy)
        }
        drawRing(geo, anim, color, bob)
    }

    private fun DrawScope.drawEye(geo: FaceGeometry, anim: FaceAnimState, side: Int, path: Path, color: Color, gx: Float, gy: Float) {
        val t = anim.time
        val asym = 1f + anim.asymmetry.value * side
        val perspective = 1f + 0.08f * anim.gazeX.value * side
        val wobble = anim.wobble.value * geo.eyeWidth * 0.12f
        val w = geo.eyeWidth * anim.width.value * asym * perspective
        val openness = (anim.open.value * (1f - anim.blink)).coerceAtLeast(0.06f)
        val h = geo.eyeHeight * anim.height.value * asym * perspective * openness
        val cx = geo.eyeCenterX(side) + gx + wobble * cos(t * 5f + side)
        val cy = geo.eyesY + gy + wobble * sin(t * 5f + side)
        val l = cx - w / 2f
        val r = cx + w / 2f
        val top = cy - h / 2f
        val bottom = cy + h / 2f

        // Visible region: below a slanted top lid and above a curved bottom lid.
        val pad = w * 0.2f
        val tilt = anim.lidTilt.value * h * 0.5f
        val topBase = top + anim.topLid.value * h
        val lidBottom = bottom + pad
        path.reset()
        path.moveTo(l - pad, topBase + side * tilt)
        path.lineTo(r + pad, topBase - side * tilt)
        path.lineTo(r + pad, lidBottom)
        path.quadraticTo(cx, lidBottom - anim.bottomLid.value * h * 2.2f, l - pad, lidBottom)
        path.close()

        clipPath(path) {
            drawRoundRect(color, Offset(l, top), Size(w, h), CornerRadius(min(w, h) * 0.38f))
            drawCircle(Color.White.copy(alpha = 0.2f), radius = w * 0.08f, center = Offset(l + w * 0.28f, top + h * 0.24f))
        }
    }

    private fun DrawScope.drawMouth(geo: FaceGeometry, anim: FaceAnimState, color: Color, gx: Float, gy: Float) {
        val mx = geo.centerX + gx * 0.6f
        val my = geo.mouthY + gy * 0.6f
        val halfW = geo.mouthWidth * anim.mouthWidth.value / 2f
        val curve = geo.mouthWidth * 0.22f * anim.mouthCurve.value
        val openH = geo.mouthWidth * 0.5f * anim.mouthOpenNow
        mouth.reset()
        mouth.moveTo(mx - halfW, my)
        mouth.quadraticTo(mx, my + 2f * curve, mx + halfW, my)
        mouth.quadraticTo(mx, my + 2f * curve + 2f * openH, mx - halfW, my)
        mouth.close()
        drawPath(mouth, color)
        drawPath(mouth, color, style = stroke)
    }

    /** Orbiting particles: a calm halo while thinking, fast "stars" when dizzy. */
    private fun DrawScope.drawRing(geo: FaceGeometry, anim: FaceAnimState, color: Color, bob: Float) {
        val alpha = maxOf(anim.ringAlpha.value, anim.wobble.value).coerceIn(0f, 1f)
        if (alpha < 0.01f) return
        val ringColor = lerp(color, DIZZY_STAR, anim.wobble.value.coerceIn(0f, 1f))
        val cy = geo.eyesY - geo.eyeHeight * 1.05f + bob
        val baseRx = geo.eyeSpacing * 1.7f
        for (i in 0 until anim.particleCount) {
            val a = anim.particleAngle[i]
            val rx = baseRx * anim.particleRadius[i]
            val depth = (sin(a) + 1f) / 2f
            drawCircle(
                color = ringColor.copy(alpha = alpha * (0.25f + 0.75f * depth)),
                radius = geo.strokeWidth * 0.55f * anim.particleSize[i] * (0.6f + 0.6f * depth),
                center = Offset(geo.centerX + cos(a) * rx, cy + sin(a) * rx * 0.2f),
            )
        }
    }

    companion object {
        val DIZZY_STAR = Color(1f, 0.92f, 0.45f)
    }
}

private class ExpressionProvider : PreviewParameterProvider<Expression> {
    override val values = Expression.entries.asSequence()
}

@Preview(widthDp = 360, heightDp = 800, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun FaceRendererPreview(@PreviewParameter(ExpressionProvider::class) expression: Expression) {
    FaceRenderer(
        FaceState(
            expression = expression,
            thinking = expression == Expression.Thinking,
            energy = if (expression == Expression.Sleepy) 0.5f else 1f,
        ),
    )
}
