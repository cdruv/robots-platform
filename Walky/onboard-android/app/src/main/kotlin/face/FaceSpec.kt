package com.vadymsidorov.walky.face

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

    // Liquid: pixel-art squares with depth. Lengths in design dp, speeds in dp/s. All tunable.
    const val LIQUID_PARTICLES = 220

    // Look
    /** Base colours and how common each is. Fast particles shift toward [LiquidHot]. */
    val LiquidTints = listOf(
        RainDim, Color(0xFF9A35B5), RainBright, Color(0xFF6A4BE8), Color(0xFF3C6BFF), EyeRim, Color(0xFFFFD0F2),
    )
    val LiquidTintShares = listOf(0.28f, 0.2f, 0.14f, 0.16f, 0.09f, 0.07f, 0.06f)
    val LiquidHot = Color(0xFFFFE6FA)
    const val LIQUID_HOT_MIX = 0.75f
    /** Speed at which a particle is fully hot. */
    const val LIQUID_HOT_SPEED = 260f
    /** Side of one grid cell. Every particle is a square of whole cells, snapped to this grid. */
    const val LIQUID_CELL = 3f
    /** Side of the nearest particles, in cells; the farthest are one cell. */
    const val LIQUID_MAX_CELLS = 4
    /** Above 1 puts more particles far away than near. */
    const val LIQUID_DEPTH_SKEW = 1.6f
    /** Opacity of the farthest and the nearest particles. */
    const val LIQUID_FAR_ALPHA = 0.45f
    const val LIQUID_NEAR_ALPHA = 0.95f
    const val LIQUID_ALPHA = 1f
    const val LIQUID_ALPHA_OVERLAY = 0.3f

    // Response to the body
    /**
     * Acceleration of a near particle under 1 g, dp/s². Kept close to the pull of the ambient
     * flow, so gravity biases where the particles gather without flattening them into a pool.
     */
    const val LIQUID_GRAVITY = 150f
    /** Exaggeration of body acceleration relative to gravity, so small movements stir the liquid. */
    const val LIQUID_ACCEL_GAIN = 6f
    /** Extra push when the tilt changes, per g of change, fading over the settle time. */
    const val LIQUID_TILT_GAIN = 7f
    const val LIQUID_TILT_SETTLE_SECONDS = 1.2f
    /** Speed the liquid is thrown to, per rad/s of body rotation: across the screen (dp/s) and around its centre (1/s). */
    const val LIQUID_TURN_SLIDE = 130f
    const val LIQUID_TURN_SWIRL = 1f
    /** How fast the liquid picks that speed up, 1/s. */
    const val LIQUID_TURN_RESPONSE = 3.5f
    /** Share of all the above that the farthest and the nearest particles feel. */
    const val LIQUID_FAR_RESPONSE = 0.45f
    const val LIQUID_NEAR_RESPONSE = 1.2f

    // Ambient flow: two layers of drifting swirls that keep the liquid moving when the body is
    // still. Speeds in dp/s, swirl sizes in dp.
    const val LIQUID_FLOW_SPEED = 70f
    const val LIQUID_FLOW_SIZE = 260f
    const val LIQUID_FLOW_FINE_SPEED = 40f
    const val LIQUID_FLOW_FINE_SIZE = 140f
    /** Share of the flow speed that the farthest particles drift at. */
    const val LIQUID_FAR_FLOW = 0.4f
    /** Rate at which particles are dragged toward the flow, 1/s; lower makes them heavier. */
    const val LIQUID_FLOW_FOLLOW = 1.5f

    // Interaction between particles in the same depth layer
    /** Rest distance between two particles: this gap plus 0.9 of their sizes, at most [LIQUID_REACH]. */
    const val LIQUID_REST_GAP = 10f
    const val LIQUID_REACH = 44f
    /** Push at full overlap, dp/s². */
    const val LIQUID_STIFFNESS = 5000f
    /** Damping of the speed at which touching particles approach, 1/s. */
    const val LIQUID_VISCOSITY = 20f

    // Edges, life and spawning
    /** Gap kept between particle centres and the screen edge. */
    const val LIQUID_MARGIN = 3f
    /** Share of speed kept when bouncing off a screen edge. */
    const val LIQUID_BOUNCE = 0.3f
    const val LIQUID_MAX_SPEED = 2500f
    const val LIQUID_MIN_LIFE_SECONDS = 5f
    const val LIQUID_MAX_LIFE_SECONDS = 14f
    /** Time a particle takes to fade in after it spawns and to fade out before it dies. */
    const val LIQUID_FADE_SECONDS = 0.6f
    /** Where new particles appear: as rain on the high edge, in a burst at the emitter, or (the rest) anywhere. */
    const val LIQUID_RAIN_SHARE = 0.3f
    const val LIQUID_BURST_SHARE = 0.35f
    const val LIQUID_BURST_SPREAD = 10f
    const val LIQUID_BURST_MIN_SPEED = 40f
    const val LIQUID_BURST_MAX_SPEED = 160f
    /** The burst emitter jumps to a new random spot this often. */
    const val LIQUID_EMITTER_MIN_SECONDS = 0.5f
    const val LIQUID_EMITTER_MAX_SECONDS = 2f

    // Body motion (IMU reflex). All of it is tunable.
    const val MOTION_STEP_SECONDS = 1f / 120f
    const val MOTION_MAX_FRAME_SECONDS = 0.05f
    /** Exaggeration of body acceleration relative to gravity, for the sway of the eyes and mouth. */
    const val MOTION_ACCEL_GAIN = 1.25f
    /** The eyes and mouth turn about this point to stay level. */
    const val LEVEL_PIVOT_X = 206f
    const val LEVEL_PIVOT_Y = 450f
    const val LEVEL_MAX_DEGREES = 25f
    /** In-plane gravity, in g, below which levelling is off; it is fully on at twice this. */
    const val LEVEL_MIN_GRAVITY = 0.25f
    /** Spring frequencies are in rad/s; damping below 1 overshoots. */
    const val LEVEL_FREQUENCY = 12f
    const val LEVEL_DAMPING = 0.6f
    /** Offset of the eyes and mouth per g of sideways or extra downward force. */
    const val EYE_SWAY = 11f
    const val MOUTH_SWAY = 16f
    const val SWAY_MAX_G = 2f
    const val EYE_SWAY_FREQUENCY = 14f
    const val MOUTH_SWAY_FREQUENCY = 10f
    const val SWAY_DAMPING = 0.35f
    /** Extra pupil travel per dp of eye sway, so the pupils look where the eyes are pulled. */
    const val PUPIL_FOLLOW = 1.1f

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
