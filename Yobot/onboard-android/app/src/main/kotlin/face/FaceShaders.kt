package com.vadymsidorov.yobot.face

import android.graphics.RuntimeShader
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

private const val GLOW_AGSL = """
uniform float2 uResolution;
uniform float2 uCenter;
uniform float3 uColor;
uniform float uIntensity;
uniform float uTime;

half4 main(float2 fragCoord) {
    float2 p = (fragCoord - uCenter) / uResolution.x;
    p.y *= 0.75;
    float d = length(p);
    float pulse = 0.92 + 0.08 * sin(uTime * 1.4);
    float core = exp(-d * d * 6.0);
    float halo = exp(-d * 2.2) * 0.35;
    float3 c = uColor * (core + halo) * uIntensity * pulse * 0.42;
    // Faint scanlines give the glass a screen-like texture.
    c *= 0.94 + 0.06 * sin(fragCoord.y * 1.6);
    return half4(half3(c), 1.0);
}
"""

/** Expression-tinted radial glow behind the face. Falls back to a plain gradient where AGSL is unavailable (previews). */
class GlowBackground {
    private val shader: RuntimeShader? = runCatching { RuntimeShader(GLOW_AGSL) }
        .onFailure { Log.w("yobot", "glow shader unavailable, using gradient fallback", it) }
        .getOrNull()
    private val brush: Brush? = shader?.let { ShaderBrush(it) }

    fun draw(scope: DrawScope, center: Offset, color: Color, intensity: Float, time: Float) = with(scope) {
        val s = shader
        if (s != null && brush != null) {
            s.setFloatUniform("uResolution", size.width, size.height)
            s.setFloatUniform("uCenter", center.x, center.y)
            s.setFloatUniform("uColor", color.red, color.green, color.blue)
            s.setFloatUniform("uIntensity", intensity)
            s.setFloatUniform("uTime", time)
            drawRect(brush)
        } else {
            drawRect(Color.Black)
            drawRect(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.35f * intensity), Color.Transparent),
                    center = center,
                    radius = size.width * 0.7f,
                ),
            )
        }
    }
}
