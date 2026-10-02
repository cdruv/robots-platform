package com.vadymsidorov.yobot.output

import com.vadymsidorov.yobot.core.output.Face
import com.vadymsidorov.yobot.core.output.FaceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** A momentary gaze override; [id] distinguishes repeated glances in the same direction. */
data class Glance(val id: Long, val x: Float, val y: Float, val durationMs: Long)

/** [Face] backed by state flows that `FaceScreen` renders. Safe to call from any thread. */
class ComposeFace : Face {
    private val _state = MutableStateFlow(FaceState())
    val state: StateFlow<FaceState> = _state.asStateFlow()

    private val glanceSeq = AtomicLong(0)
    private val _glance = MutableStateFlow<Glance?>(null)
    val glance: StateFlow<Glance?> = _glance.asStateFlow()

    override fun setTarget(state: FaceState) {
        _state.value = state
    }

    override fun glance(x: Float, y: Float, durationMs: Long) {
        _glance.value = Glance(glanceSeq.incrementAndGet(), x, y, durationMs)
    }
}
