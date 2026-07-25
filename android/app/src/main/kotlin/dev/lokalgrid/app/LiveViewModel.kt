package dev.lokalgrid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lokalgrid.app.net.NodeClient
import dev.lokalgrid.protocol.ClientFrame
import dev.lokalgrid.protocol.Lane
import dev.lokalgrid.protocol.NodeFrame
import dev.lokalgrid.protocol.RosterEntry
import dev.lokalgrid.protocol.TrackRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Default host: 10.0.2.2 is the Android emulator's alias for the dev machine,
 *  where `npm start` in mock-node/ is listening on 8787. Change for a real device. */
private const val DEFAULT_URL = "ws://10.0.2.2:8787"

/**
 * One message in the shared channel, in whatever state we currently know it to
 * be. The node owns `seq` — until it hands one back, a message we sent is
 * *pending*, not sent. The client never invents what the node is authoritative
 * about (§3 ownership rule), so the UI can say exactly what is true.
 */
data class ChatEntry(
    val msgId: String? = null,     // our id for it; null for other people's messages
    val seq: Long = 0,             // node-assigned; 0 = not acknowledged yet
    val from: Int = -1,
    val name: String,
    val text: String,
    val epoch: Long,
    val lane: Int = Lane.MESSAGE,
    val mine: Boolean = false,
    val delivered: Boolean = false,   // node echoed it — it exists on the node
    val relayReason: String? = null,  // queue state for the link *out* (LoRa)
    val relayEtaMs: Long = 0,
    val relayed: Boolean = false,     // actually went out over the radio
    val error: String? = null,        // refused, with the node's reason
) {
    val key: String get() = msgId ?: "seq-$seq"
}

data class LiveState(
    val url: String = DEFAULT_URL,
    val connected: Boolean = false,
    val status: String = "connecting…",
    val latest: TrackRecord? = null,
    val fixCount: Int = 0,
    val dropped: Int = 0,
    val lastDrop: String? = null,
    // forward flow / chat
    val selfId: Int = -1,
    val selfName: String = "you",
    val cap: Int = 9,
    val duty: Double = 0.01,
    val roster: List<RosterEntry> = emptyList(),
    val messages: List<ChatEntry> = emptyList(),
    val nodeNotice: String? = null,   // connection-scope refusal, e.g. "node full"
    // the rest of the forward flow
    val peers: List<NodeFrame.Peer> = emptyList(),   // where everyone else says they are
    val lastPeerSkip: String? = null,                // your position was decimated, and why
    val positionsShared: Int = 0,
    val config: NodeFrame.Config? = null,            // the config in force on the node
    val lastConfigResult: NodeFrame.ConfigResult? = null,
    val stats: NodeFrame.Stats? = null,              // airtime accounting from the node
) {
    val clientCount: Int get() = if (roster.isNotEmpty()) roster.size else if (connected) 1 else 0

    /** Your messages still waiting on the link out — what the outbound panel shows. */
    val outbox: List<ChatEntry> get() = messages.filter { it.mine && !it.relayed && it.error == null }
}

class LiveViewModel(private val url: String = DEFAULT_URL) : ViewModel() {

    private val client = NodeClient(url)
    private val _state = MutableStateFlow(LiveState(url = url))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var msgCounter = 0

    init {
        viewModelScope.launch {
            client.events().collect { ev ->
                _state.value = reduce(_state.value, ev)
            }
        }
    }

    /**
     * The forward flow. The bubble appears immediately as *pending* — an
     * optimistic echo of what we asked for, not a claim that it landed. The node
     * confirms it by echoing back with a seq, then keeps us posted on the queue
     * for the link out.
     */
    fun sendChat(text: String, emergency: Boolean = false) {
        val body = text.trim()
        if (body.isEmpty()) return
        val id = "m-${msgCounter++}-${System.currentTimeMillis() % 100_000}"
        val lane = if (emergency) Lane.EMERGENCY else Lane.MESSAGE
        val pending = ChatEntry(
            msgId = id,
            name = _state.value.selfName,
            from = _state.value.selfId,
            text = body,
            epoch = System.currentTimeMillis() / 1000,
            lane = lane,
            mine = true,
        )
        val ok = client.send(ClientFrame.Send(id, body, lane))
        _state.value = _state.value.copy(
            messages = _state.value.messages +
                pending.copy(error = if (ok) null else "not sent — no link to the node")
        )
    }

    /** Rename yourself on the roster — the node refuses a clash, with a reason. */
    fun setName(name: String) {
        if (name.isNotBlank()) client.send(ClientFrame.Name(name.trim()))
    }

    /** Restart the mock's synthetic track. Phase 03 replaces this with a real one. */
    fun resetTrack() = client.send(ClientFrame.Reset)

    /** Ask for the chat delta from our own cursor — the client owns its cursor (§3). */
    fun resync() {
        client.send(ClientFrame.Cursor(_state.value.messages.maxOfOrNull { it.seq } ?: 0))
    }

    /**
     * Share where *you* are. The node decides whether it moved far enough to be
     * worth the link (decimation by distance, §3) and answers either way.
     *
     * Until the app has its own GPS permission, this offers the node's own fix as
     * our position — honest for a phone sitting next to the node, and it exercises
     * the whole path. Replaced by the phone's fix when location lands.
     */
    fun shareMyPosition() {
        val fix = _state.value.latest ?: run {
            _state.value = _state.value.copy(lastPeerSkip = "no fix to share yet")
            return
        }
        val ok = client.send(
            ClientFrame.Pos(latE7 = fix.latE7, lonE7 = fix.lonE7, hd = fix.hd, epoch = fix.epoch)
        )
        _state.value = _state.value.copy(
            positionsShared = _state.value.positionsShared + 1,
            lastPeerSkip = if (ok) _state.value.lastPeerSkip else "not shared — no link to the node",
        )
    }

    /** Write staged config. Explicit, never mid-edit (§6). */
    fun writeConfig(patch: Map<String, String>) {
        if (patch.isNotEmpty()) client.send(ClientFrame.ConfigSet(patch))
    }

    private fun reduce(s: LiveState, ev: NodeClient.Event): LiveState = when (ev) {
        is NodeClient.Event.Fix -> s.copy(latest = ev.record, fixCount = s.fixCount + 1)
        is NodeClient.Event.Dropped -> s.copy(dropped = s.dropped + 1, lastDrop = ev.reason)
        is NodeClient.Event.Status -> s.copy(connected = ev.connected, status = ev.detail)
        is NodeClient.Event.Frame -> reduceFrame(s, ev.frame)
    }

    private fun reduceFrame(s: LiveState, f: NodeFrame): LiveState = when (f) {
        is NodeFrame.Hello -> s.copy(
            status = "node ${f.deviceId} · ${f.mode} · ${f.hz} Hz · proto ${f.proto}",
            selfId = f.youId,
            selfName = f.youName,
            cap = f.cap,
            duty = f.duty,
        )

        is NodeFrame.Roster -> s.copy(
            roster = f.clients,
            cap = f.cap,
            // The roster is the node's answer about who we are, so a rename it
            // accepted (or refused) is reflected here — never assumed from what
            // we asked for. Same ownership rule as `seq` on a message (§3).
            selfName = f.clients.firstOrNull { it.id == s.selfId }?.name ?: s.selfName,
            // A client that left takes its dot with it — the node is authoritative
            // about who exists, so a stale peer must not linger on the map.
            peers = s.peers.filter { p -> f.clients.any { it.id == p.id } },
        )

        // The authoritative record of a message. If it echoes one of ours, we
        // upgrade the pending bubble in place instead of showing it twice.
        is NodeFrame.Chat -> {
            val i = s.messages.indexOfFirst { it.mine && it.msgId != null && it.msgId == f.msgId }
            val entry = ChatEntry(
                msgId = f.msgId,
                seq = f.seq,
                from = f.from,
                name = f.name,
                text = f.text,
                epoch = f.epoch,
                lane = f.lane,
                mine = f.from == s.selfId,
                delivered = true,
            )
            if (i >= 0) {
                val old = s.messages[i]
                s.copy(messages = s.messages.toMutableList().also {
                    it[i] = entry.copy(
                        mine = true,
                        relayReason = old.relayReason,
                        relayEtaMs = old.relayEtaMs,
                        relayed = old.relayed,
                    )
                })
            } else {
                s.copy(messages = s.messages + entry)
            }
        }

        is NodeFrame.Queued -> {
            val ackedSeq = f.seq
            s.updateMsg(f.msgId) {
                it.copy(
                    seq = if (ackedSeq != null && ackedSeq > 0) ackedSeq else it.seq,
                    relayReason = f.reason,
                    relayEtaMs = f.etaMs,
                )
            }
        }

        is NodeFrame.Relayed -> s.updateMsg(f.msgId) {
            it.copy(relayed = true, relayReason = "sent over LoRa · ${f.airtimeMs} ms airtime", relayEtaMs = 0)
        }

        // Never a silent failure: every refusal lands somewhere visible (§3).
        is NodeFrame.Rejected -> {
            val id = f.msgId
            if (id != null) s.updateMsg(id) { it.copy(error = f.reason, relayReason = null) }
            else s.copy(nodeNotice = "${f.scope}: ${f.reason}")
        }

        // Everyone on one map. Keyed by client id: a peer's newest position
        // replaces its old one, it never stacks up as a second dot.
        is NodeFrame.Peer -> s.copy(
            peers = s.peers.filterNot { it.id == f.id } + f,
            lastPeerSkip = if (f.id == s.selfId) null else s.lastPeerSkip,
        )

        is NodeFrame.PeerSkip -> s.copy(lastPeerSkip = f.reason)

        is NodeFrame.Config -> s.copy(config = f)
        is NodeFrame.ConfigResult -> s.copy(lastConfigResult = f)
        is NodeFrame.Stats -> s.copy(stats = f)

        is NodeFrame.Unknown -> s.copy(nodeNotice = "unknown frame \"${f.type}\" — node is newer than this app")
        is NodeFrame.Malformed -> s.copy(dropped = s.dropped + 1, lastDrop = "control frame: ${f.error}")
    }

    private fun LiveState.updateMsg(msgId: String, f: (ChatEntry) -> ChatEntry): LiveState {
        val i = messages.indexOfFirst { it.msgId == msgId }
        if (i < 0) return this
        return copy(messages = messages.toMutableList().also { it[i] = f(it[i]) })
    }
}
