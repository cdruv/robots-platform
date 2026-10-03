package com.vadymsidorov.yobot.face

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.util.Log
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

private const val GLOW_RECT_AGSL = """
uniform float2 uCenter;
uniform float2 uHalf;
uniform float2 uRadii;       // top, bottom corner radius
uniform float uStroke;
uniform float4 uGlowParams;  // outer sigma, outer alpha, inner sigma, inner alpha
layout(color) uniform half4 uRim;
layout(color) uniform half4 uFill;
layout(color) uniform half4 uGlow;

// Share of a Gaussian-blurred edge left at x sigmas past it: 1 - CDF(x).
float falloff(float x) {
    float z = abs(x) * 0.70710678;
    float t = 1.0 / (1.0 + 0.47047 * z);
    float h = 0.5 * t * (0.3480242 + t * (-0.0958798 + t * 0.7478556)) * exp(-z * z);
    return x > 0.0 ? h : 1.0 - h;
}

half4 over(half4 src, half4 dst) { return src + dst * (1.0 - src.a); }

half4 main(float2 xy) {
    float2 p = xy - uCenter;
    // Signed distance to a rounded rect whose top and bottom corner radii differ.
    bool top = p.y < (uRadii.x - uRadii.y) * 0.5;
    float r = top ? uRadii.x : uRadii.y;
    float2 q = float2(abs(p.x), top ? -p.y : p.y) - uHalf + r;
    float d = max(
        min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r,
        max(abs(p.x) - uHalf.x, abs(p.y) - uHalf.y));

    float shape = clamp(0.5 - d, 0.0, 1.0);
    float core = clamp(0.5 - d - uStroke, 0.0, 1.0);
    half4 glow = premul(uGlow);

    half4 c = glow * (uGlowParams.y * falloff(d / uGlowParams.x) * (1.0 - shape));
    c = over(premul(uFill) * core, c);
    c = over(glow * (uGlowParams.w * falloff((-d - uStroke) / uGlowParams.z) * core), c);
    c = over(premul(uRim) * (shape - core), c);
    return c;
}
"""

/** Corner radii limited the way CSS does it, so adjacent corners never overlap. */
internal fun fitRadii(width: Float, height: Float, top: Float, bottom: Float): Pair<Float, Float> {
    val f = min(1f, min(width / (2f * max(top, bottom, 0.001f)), height / max(top + bottom, 0.001f)))
    return top * f to bottom * f
}

private fun max(a: Float, b: Float, c: Float) = max(a, max(b, c))

/**
 * Draws a rounded rect with a rim, a fill, a blurred outer glow and an inset glow in one AGSL
 * pass. Eyes, pupils and the mouth all use it. Where AGSL is unavailable (previews) it falls
 * back to a flat fill and rim with no glow.
 */
internal class GlowRectPainter {
    private val shader: RuntimeShader? = runCatching { RuntimeShader(GLOW_RECT_AGSL) }
        .onFailure { Log.w("yobot", "glow shader unavailable, drawing flat shapes", it) }
        .getOrNull()
    private val paint = Paint()
    private val fallbackPath = Path()

    /** All lengths in pixels. [topRadius] and [bottomRadius] must already fit, see [fitRadii]. */
    fun draw(
        scope: DrawScope,
        center: Offset,
        halfWidth: Float,
        halfHeight: Float,
        topRadius: Float,
        bottomRadius: Float,
        fill: Color,
        glow: Color,
        outerSigma: Float,
        outerAlpha: Float,
        rim: Color = Color.Transparent,
        stroke: Float = 0f,
        innerSigma: Float = 1f,
        innerAlpha: Float = 0f,
        alpha: Float = 1f,
    ) {
        if (alpha <= 0f) return
        val s = shader ?: return drawFlat(scope, center, halfWidth, halfHeight, topRadius, bottomRadius, fill, rim, stroke, alpha)
        s.setFloatUniform("uCenter", center.x, center.y)
        s.setFloatUniform("uHalf", halfWidth, halfHeight)
        s.setFloatUniform("uRadii", topRadius, bottomRadius)
        s.setFloatUniform("uStroke", stroke)
        s.setFloatUniform("uGlowParams", max(outerSigma, 0.001f), outerAlpha, max(innerSigma, 0.001f), innerAlpha)
        s.setColorUniform("uRim", rim.toArgb())
        s.setColorUniform("uFill", fill.toArgb())
        s.setColorUniform("uGlow", glow.toArgb())
        paint.shader = s
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        // The glow is invisible past three sigmas.
        val margin = (if (outerAlpha > 0f) outerSigma * 3f else 0f) + 1f
        scope.drawIntoCanvas {
            it.nativeCanvas.drawRect(
                center.x - halfWidth - margin, center.y - halfHeight - margin,
                center.x + halfWidth + margin, center.y + halfHeight + margin,
                paint,
            )
        }
    }

    private fun drawFlat(
        scope: DrawScope, center: Offset, halfWidth: Float, halfHeight: Float,
        topRadius: Float, bottomRadius: Float, fill: Color, rim: Color, stroke: Float, alpha: Float,
    ) = with(scope) {
        roundRectPath(fallbackPath, center, halfWidth - stroke / 2f, halfHeight - stroke / 2f, topRadius, bottomRadius)
        drawPath(fallbackPath, fill, alpha = alpha)
        if (stroke > 0f) drawPath(fallbackPath, rim, alpha = alpha, style = Stroke(stroke))
    }
}

/** Resets [path] to a rounded rect around [center] with separate top and bottom corner radii. */
internal fun roundRectPath(
    path: Path, center: Offset, halfWidth: Float, halfHeight: Float, topRadius: Float, bottomRadius: Float,
) {
    val top = CornerRadius(max(topRadius, 0f))
    val bottom = CornerRadius(max(bottomRadius, 0f))
    path.reset()
    path.addRoundRect(
        RoundRect(
            Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight),
            topLeft = top, topRight = top, bottomRight = bottom, bottomLeft = bottom,
        ),
    )
}
