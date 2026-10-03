package com.vadymsidorov.yobot.face

import androidx.compose.ui.graphics.Color

/**
 * Design tokens for the "3a · Rain + Glow" face. Lengths are in design dp on the 412×915
 * portrait reference frame; the renderer scales the frame to the display width. Blur values
 * are Gaussian sigmas, half the blur radius the design quotes.
 */
internal object FaceSpec {
    const val FRAME_WIDTH = 412f
    const val FRAME_HEIGHT = 915f

    val Background = Color(0xFF0D0712)
    val RainDim = Color(0xFF6B1A6E)
    val RainBright = Color(0xFFFF7AD9)
    val EyeRim = Color(0xFF3CCFFF)
    val EyeFill = Color(0xFF0B2230)
    val AlertRim = RainBright
    val SleepRim = Color(0xFF1A4A5C)
    val SleepFill = Color(0xFF0A1A24)
    val Pupil = Color(0xFF8FE9FF)
    val Catchlight = Color(0xFFEAFCFF)

    // Rain
    const val RAIN_COLUMNS = 12
    val RainBrightColumns = setOf(0, 6, 8)
    const val RAIN_GLYPHS = 72
    const val RAIN_FONT_SIZE = 13f
    /** Glyph pitch along the column: 1em plus the 0.1em tracking. */
    const val RAIN_GLYPH_PITCH = RAIN_FONT_SIZE * 1.1f
    /** Column box width: the 1.3 line height. */
    const val RAIN_COLUMN_WIDTH = RAIN_FONT_SIZE * 1.3f
    const val RAIN_SIDE_PADDING = 10f
    const val RAIN_MIN_SECONDS = 10f
    const val RAIN_MAX_SECONDS = 19f
    const val RAIN_TRAVEL = 0.6f
    const val RAIN_ALPHA = 0.9f
    const val RAIN_ALPHA_OVERLAY = 0.3f

    // Eyes
    const val EYE_LEFT_X = 102f
    const val EYE_RIGHT_X = 310f
    const val EYE_STROKE = 2.5f
    const val EYE_OVERLAY_RIM_ALPHA = 0.25f
    const val GLOW_SECONDS = 3.2f
    const val OUTER_GLOW_SIGMA = 23f
    const val OUTER_GLOW_SIGMA_PEAK = 34.5f
    const val OUTER_GLOW_ALPHA = 0.4f
    const val OUTER_GLOW_ALPHA_PEAK = 0.6f
    const val INNER_GLOW_SIGMA = 19f
    const val INNER_GLOW_SIGMA_PEAK = 21.5f
    const val INNER_GLOW_ALPHA = 0.2f
    const val INNER_GLOW_ALPHA_PEAK = 0.27f

    // Pupil
    const val PUPIL_WIDTH = 53f
    const val PUPIL_HEIGHT = 69f
    const val PUPIL_RADIUS = 19f
    const val PUPIL_GLOW_SIGMA = 11.5f
    const val CATCHLIGHT_RADIUS = 7f
    /** Catchlight centre measured from the pupil's top-left corner. */
    const val CATCHLIGHT_OFFSET = 16f

    // Gaze and blink
    const val GAZE_RANGE_X = 17f
    const val GAZE_RANGE_Y = 11f
    const val GAZE_MIN_HOLD_MS = 1500L
    const val GAZE_MAX_HOLD_MS = 3000L
    const val GAZE_MOVE_MS = 500
    const val BLINK_CLOSED_SCALE = 0.08f
    const val BLINK_CLOSE_MS = 110
    const val BLINK_OPEN_MS = 150
    const val BLINK_RIGHT_EYE_LAG_MS = 80L
    const val BLINK_MIN_INTERVAL_MS = 3000L
    const val BLINK_MAX_INTERVAL_MS = 7000L
    const val BLINK_DOUBLE_ONE_IN = 3
    const val BLINK_DOUBLE_GAP_MS = 180L

    // Mouth. Listening, speaking and processing values are proposed in the design: tunable.
    const val MOUTH_X = 206f
    const val MOUTH_Y = 551f
    const val MOUTH_WIDTH = 107f
    const val MOUTH_HEIGHT = 5f
    const val MOUTH_GLOW_SIGMA = 9f
    const val MOUTH_CYCLE_SECONDS = 1.1f
    const val MOUTH_LISTENING_MAX_WIDTH = 127f
    const val MOUTH_SPEAKING_MIN_WIDTH = 60f
    const val MOUTH_SPEAKING_MAX_WIDTH = 160f
    const val MOUTH_PROCESSING_SEGMENT = 32f

    const val EXPRESSION_MS = 320
    const val OVERLAY_FADE_MS = 240
}
