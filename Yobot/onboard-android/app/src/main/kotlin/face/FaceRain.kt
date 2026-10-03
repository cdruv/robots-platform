package com.vadymsidorov.yobot.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import kotlin.random.Random

/** Half-width katakana ｦ–ﾝ, digits and a few symbols. */
private val RainGlyphs: List<Char> = ('ｦ'..'ﾝ') + ('0'..'9') + "*=:|¦・Z<>".toList()

/** One falling column: its glyphs, loop duration and starting phase. Fixed for the life of the face. */
internal class RainColumn(val text: String, val seconds: Float, val phase: Float, val bright: Boolean)

internal fun rainColumns(random: Random = Random(0x70B07)): List<RainColumn> = List(FaceSpec.RAIN_COLUMNS) { i ->
    RainColumn(
        text = List(FaceSpec.RAIN_GLYPHS) { RainGlyphs.random(random) }.joinToString("\n"),
        seconds = FaceSpec.RAIN_MIN_SECONDS + random.nextFloat() * (FaceSpec.RAIN_MAX_SECONDS - FaceSpec.RAIN_MIN_SECONDS),
        phase = random.nextFloat(),
        bright = i in FaceSpec.RainBrightColumns,
    )
}

/** Rain columns laid out for one display size. [unit] is pixels per design dp. */
internal class RainLayer(
    private val columns: List<RainColumn>,
    measurer: TextMeasurer,
    density: Density,
    private val unit: Float,
) {
    private val columnWidth = FaceSpec.RAIN_COLUMN_WIDTH * unit
    private val layouts: List<TextLayoutResult> = with(density) {
        val style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = (FaceSpec.RAIN_FONT_SIZE * unit).toSp(),
            lineHeight = (FaceSpec.RAIN_GLYPH_PITCH * unit).toSp(),
            textAlign = TextAlign.Center,
        )
        columns.map { measurer.measure(it.text, style, constraints = Constraints.fixedWidth(columnWidth.roundToInt())) }
    }
    private val dimBrush = Brush.verticalGradient(
        0f to FaceSpec.RainDim.copy(alpha = 0f), 0.55f to FaceSpec.RainDim, 1f to FaceSpec.RainDim,
    )
    private val brightBrush = Brush.verticalGradient(
        0f to FaceSpec.RainDim.copy(alpha = 0f), 0.55f to FaceSpec.RainDim, 1f to FaceSpec.RainBright,
    )

    fun draw(scope: DrawScope, seconds: Double, alpha: Float) = with(scope) {
        val padding = FaceSpec.RAIN_SIDE_PADDING * unit
        val gap = (size.width - 2f * padding - columnWidth) / (columns.size - 1)
        columns.forEachIndexed { i, column ->
            val layout = layouts[i]
            val phase = ((seconds / column.seconds + column.phase) % 1.0).toFloat()
            val y = (2f * phase - 1f) * FaceSpec.RAIN_TRAVEL * layout.size.height
            drawText(
                layout,
                brush = if (column.bright) brightBrush else dimBrush,
                topLeft = Offset(padding + i * gap, y),
                alpha = alpha,
            )
        }
    }
}
