package com.ethic.vpn.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Disconnected : VpnState
    data class Connecting(val server: String) : VpnState
    data class Connected(val server: String, val since: Long) : VpnState
    data class Error(val message: String) : VpnState
}

/** Process-wide connection state shared by the service, the UI, the quick settings tile and the widgets. */
object VpnStatus {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** Set by the service and the activity, so the home screen widgets can be redrawn on changes. */
    @Volatile var appContext: Context? = null

    fun set(state: VpnState) {
        _state.value = state
        appContext?.let(VpnWidget::updateAll)
    }
}
