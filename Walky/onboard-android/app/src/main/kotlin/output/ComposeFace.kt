package com.vadymsidorov.walky.output

import com.vadymsidorov.walky.core.output.Face
import com.vadymsidorov.walky.core.output.FaceState
import com.vadymsidorov.walky.core.output.Reaction
import com.vadymsidorov.walky.core.telemetry.FaceReactionMessage
import com.vadymsidorov.walky.core.telemetry.Telemetry
import com.vadymsidorov.walky.core.telemetry.Topics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Face output connection: holds the target the renderer draws and relays one-shot reactions.
 * Records what the face is told, not what it draws: each changed target on `/face/state`,
 * each reaction on `/face/reaction`. Blinks, gaze and sway are decoration a viewer can redo
 * from those and the IMU. [telemetry] is the current session's.
 */
class ComposeFace(private val telemetry: () -> Telemetry) : Face {
    private val target = MutableStateFlow(FaceState())
    val state: StateFlow<FaceState> = target.asStateFlow()

    private val pendingReactions = MutableSharedFlow<Reaction>(extraBufferCapacity = 8)

    /** Scaffold: the renderer does not collect or play reactions yet. */
    val reactions: SharedFlow<Reaction> = pendingReactions.asSharedFlow()

    override fun setTarget(state: FaceState) {
        if (target.getAndUpdate { state } != state) recordState()
    }

    override fun react(reaction: Reaction) {
        telemetry().channel(Topics.FaceReaction).write(FaceReactionMessage(reaction))
        pendingReactions.tryEmit(reaction)
    }

    /** Records the current target, as each session's recording must start with it. */
    fun recordState() {
        telemetry().channel(Topics.FaceStateTopic).write(target.value)
    }
}
