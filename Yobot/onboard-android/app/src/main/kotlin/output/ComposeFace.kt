package com.vadymsidorov.yobot.output

import com.vadymsidorov.yobot.core.output.Face
import com.vadymsidorov.yobot.core.output.FaceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Face output connection. Only the neutral target is supported. */
class ComposeFace : Face {
    private val target = MutableStateFlow(FaceState())
    val state: StateFlow<FaceState> = target.asStateFlow()

    override fun setTarget(state: FaceState) {
        target.value = state
    }
}
