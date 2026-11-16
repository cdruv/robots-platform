package com.vadymsidorov.walky

import android.content.Context
import com.vadymsidorov.walky.core.cognition.BrainMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User settings, persisted across launches. The session forwards them to the Executive. */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val currentBrain = MutableStateFlow(
        BrainMode.entries.firstOrNull { it.name == preferences.getString(KEY_BRAIN, null) } ?: BrainMode.Idle
    )
    val brain: StateFlow<BrainMode> = currentBrain.asStateFlow()

    private val currentMuted = MutableStateFlow(preferences.getBoolean(KEY_MUTED, false))
    val muted: StateFlow<Boolean> = currentMuted.asStateFlow()

    fun setBrain(mode: BrainMode) {
        currentBrain.value = mode
        preferences.edit().putString(KEY_BRAIN, mode.name).apply()
    }

    fun setMuted(muted: Boolean) {
        currentMuted.value = muted
        preferences.edit().putBoolean(KEY_MUTED, muted).apply()
    }

    private companion object {
        const val PREFERENCES = "settings"
        const val KEY_BRAIN = "brain"
        const val KEY_MUTED = "muted"
    }
}
