package com.vadymsidorov.yobot.face

/** Layout of the static face, proportional to the display size. */
class FaceGeometry(val width: Float, val height: Float) {
    val centerX = width / 2f
    val eyesY = height * 0.42f
    val eyeSpacing = width * 0.25f
    val eyeWidth = width * 0.27f
    val eyeHeight = width * 0.32f
    val mouthY = height * 0.60f
    val mouthWidth = width * 0.24f
    val strokeWidth = width * 0.022f

    fun eyeCenterX(side: Int) = centerX + side * eyeSpacing
}
