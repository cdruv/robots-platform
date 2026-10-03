package com.vadymsidorov.yobot.output

import com.vadymsidorov.yobot.core.output.Face
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.core.output.Reaction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Face output connection: holds the target the renderer draws and relays one-shot reactions. */
class ComposeFace : Face {
    private val target = MutableStateFlow(FaceState())
    val state: StateFlow<FaceState> = target.asStateFlow()

    private val pendingReactions = MutableSharedFlow<Reaction>(extraBufferCapacity = 8)

    /** Scaffold: the renderer does not collect or play reactions yet. */
    val reactions: SharedFlow<Reaction> = pendingReactions.asSharedFlow()

    override fun setTarget(state: FaceState) {
        target.value = state
    }

    override fun react(reaction: Reaction) {
        pendingReactions.tryEmit(reaction)
    }
}
