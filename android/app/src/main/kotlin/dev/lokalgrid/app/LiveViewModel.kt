package dev.lokalgrid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lokalgrid.app.net.NodeClient
import dev.lokalgrid.protocol.TrackRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Default host: 10.0.2.2 is the Android emulator's alias for the dev machine,
 *  where `npm start` in mock-node/ is listening on 8787. Change for a real device. */
private const val DEFAULT_URL = "ws://10.0.2.2:8787"

data class LiveState(
    val connected: Boolean = false,
    val status: String = "connecting…",
    val latest: TrackRecord? = null,
    val fixCount: Int = 0,
    val dropped: Int = 0,
    val lastDrop: String? = null,
)

class LiveViewModel(url: String = DEFAULT_URL) : ViewModel() {

    private val client = NodeClient(url)
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            client.events().collect { ev ->
                _state.value = reduce(_state.value, ev)
            }
        }
    }

    private fun reduce(s: LiveState, ev: NodeClient.Event): LiveState = when (ev) {
        is NodeClient.Event.Hello -> s.copy(status = "node: ${ev.text}")
        is NodeClient.Event.Fix -> s.copy(latest = ev.record, fixCount = s.fixCount + 1)
        is NodeClient.Event.Dropped -> s.copy(dropped = s.dropped + 1, lastDrop = ev.reason)
        is NodeClient.Event.Status -> s.copy(connected = ev.connected, status = ev.detail)
    }
}
