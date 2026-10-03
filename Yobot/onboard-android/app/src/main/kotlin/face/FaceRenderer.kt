package com.vadymsidorov.yobot.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.output.FaceState
import kotlin.math.min

/** One static expression. No frame loop, blinking, gaze movement, or animation state. */
@Composable
fun FaceRenderer(target: FaceState, modifier: Modifier = Modifier) {
    val glow = remember { GlowBackground() }
    val mouth = remember { Path() }
    val color = when (target.expression) {
        Expression.Neutral -> Color(0.35f, 0.85f, 1f)
    }

    Canvas(modifier.fillMaxSize()) {
        val geo = FaceGeometry(size.width, size.height)
        glow.draw(this, Offset(geo.centerX, geo.eyesY), color)
        for (side in listOf(-1, 1)) {
            val left = geo.eyeCenterX(side) - geo.eyeWidth / 2f
            val top = geo.eyesY - geo.eyeHeight / 2f
            drawRoundRect(
                color,
                Offset(left, top),
                Size(geo.eyeWidth, geo.eyeHeight),
                CornerRadius(min(geo.eyeWidth, geo.eyeHeight) * 0.38f),
            )
            drawCircle(
                Color.White.copy(alpha = 0.2f),
                radius = geo.eyeWidth * 0.08f,
                center = Offset(left + geo.eyeWidth * 0.28f, top + geo.eyeHeight * 0.24f),
            )
        }
        mouth.reset()
        mouth.moveTo(geo.centerX - geo.mouthWidth / 2f, geo.mouthY)
        mouth.quadraticTo(
            geo.centerX, geo.mouthY + geo.mouthWidth * 0.066f,
            geo.centerX + geo.mouthWidth / 2f, geo.mouthY,
        )
        drawPath(mouth, color, style = Stroke(geo.strokeWidth, cap = StrokeCap.Round))
    }
}

@Preview(widthDp = 360, heightDp = 800, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun FaceRendererPreview() {
    FaceRenderer(FaceState())
}
