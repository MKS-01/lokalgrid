package dev.lokalgrid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lokalgrid.app.net.NodeClient
import dev.lokalgrid.protocol.ClientFrame
import dev.lokalgrid.protocol.Lane
import dev.lokalgrid.protocol.NodeFrame
import dev.lokalgrid.protocol.RosterEntry
import dev.lokalgrid.protocol.TrackRecord
import kotlinx.coroutines.Job
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
    // position cursor + backlog resume
    val posCursor: Long = 0,          // last position seq we have; ours to state, not guess
    val posHeld: Int = 0,             // how many the node still holds
    val posOldest: Long = 0,
    val catchingUp: Boolean = false,
    val backlogTotal: Int = 0,        // what the node said it owed us on resume
    val backlogRemaining: Int = 0,
    val lostBefore: Int = 0,          // records that aged out before we came back
    val gapReason: String? = null,
    val track: List<TrackRecord> = emptyList(),  // observed history, oldest first
) {
    val clientCount: Int get() = if (roster.isNotEmpty()) roster.size else if (connected) 1 else 0

    /**
     * The newest position seq we can *prove* the node has: whatever its last stats
     * frame said, or our own cursor if we have received further since. Stats tick
     * every few seconds while the cursor advances every second, so taking the node
     * figure alone would briefly render "you have seq 17, the node holds 14" —
     * two true numbers that read as a contradiction.
     */
    val posNewestKnown: Long get() = maxOf(stats?.posNewest ?: 0L, posCursor)

    /** Your messages still waiting on the link out — what the outbound panel shows. */
    val outbox: List<ChatEntry> get() = messages.filter { it.mine && !it.relayed && it.error == null }
}

/** How much observed track to keep in memory for the map. Room replaces this later (§6). */
private const val TRACK_KEEP = 1500

class LiveViewModel(
    private val url: String = DEFAULT_URL,
    private val savedCursor: Long = 0,
    private val onCursor: (Long) -> Unit = {},
) : ViewModel() {

    private val client = NodeClient(url)
    private val _state = MutableStateFlow(LiveState(url = url, posCursor = savedCursor))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var msgCounter = 0

    private var lastSaved = savedCursor
    private var link: Job? = null

    init {
        connect()
    }

    private fun connect() {
        link?.cancel()
        link = viewModelScope.launch {
            client.events().collect { ev ->
                val next = reduce(_state.value, ev)
                _state.value = next
                // Persist occasionally rather than every record: a killed app should
                // resume a few seconds behind, not from zero. The exact figure is
                // re-stated to the node on reconnect, which corrects any drift.
                if (next.posCursor - lastSaved >= 30) {
                    lastSaved = next.posCursor
                    onCursor(next.posCursor)
                }
            }
        }
    }

    /**
     * Drop the socket and open a new one. The cursor survives, so this resumes a
     * delta rather than starting over — reconnecting is cheap by design, which is
     * the whole reason the app does not need to gate itself behind a live link.
     */
    fun reconnect() {
        _state.value = _state.value.copy(connected = false, status = "reconnecting to $url…")
        connect()
    }

    override fun onCleared() {
        onCursor(_state.value.posCursor)
        super.onCleared()
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

    /**
     * State our cursors and ask for the delta. The client is authoritative about
     * what it has received (§3), so this is the app *telling* the node where it
     * got to — chat and positions separately — never the node assuming.
     */
    fun resync() {
        val s = _state.value
        client.send(ClientFrame.Cursor(s.messages.maxOfOrNull { it.seq } ?: 0, s.posCursor))
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
        // Each record advances our cursor by exactly one: the socket is ordered
        // and lossless, so counting is safe *between* the node's explicit sync
        // points, and a backlogChunk/Done frame overrides the count rather than
        // adding to it. Any drift is resolved in the node's favour.
        is NodeClient.Event.Fix -> s.copy(
            latest = ev.record,
            fixCount = s.fixCount + 1,
            posCursor = s.posCursor + 1,
            backlogRemaining = (s.backlogRemaining - 1).coerceAtLeast(0),
            track = (s.track + ev.record).takeLast(TRACK_KEEP),
        )
        is NodeClient.Event.Dropped -> s.copy(dropped = s.dropped + 1, lastDrop = ev.reason)
        is NodeClient.Event.Status -> s.copy(connected = ev.connected, status = ev.detail)
        is NodeClient.Event.Frame -> reduceFrame(s, ev.frame)
    }

    private fun reduceFrame(s: LiveState, f: NodeFrame): LiveState = when (f) {
        is NodeFrame.Hello -> {
            // The node has just told us what it holds; state our cursors straight
            // back so the resume happens before anything renders as "current".
            viewModelScope.launch { resync() }
            s.copy(
                status = "node ${f.deviceId} · ${f.mode} · ${f.hz} Hz · proto ${f.proto}",
                selfId = f.youId,
                selfName = f.youName,
                cap = f.cap,
                duty = f.duty,
                posHeld = f.posHeld,
                posOldest = f.posOldest,
            )
        }

        // What we are owed, stated before it arrives. `lost` is the honest part:
        // records that aged out of the node before we came back, which the map
        // must show as a gap rather than draw a straight line through.
        is NodeFrame.Backlog -> s.copy(
            posCursor = (f.from - 1).coerceAtLeast(0),
            catchingUp = f.count > 0,
            backlogTotal = f.count,
            backlogRemaining = f.count,
            lostBefore = f.lost,
            gapReason = f.reason,
            posHeld = f.held,
            posOldest = f.oldest,
            // A gap means the track we hold is no longer continuous with what is
            // arriving — drop it rather than joining two unrelated stretches.
            track = if (f.lost > 0) emptyList() else s.track,
        )

        is NodeFrame.BacklogChunk -> s.copy(posCursor = f.cursor, backlogRemaining = f.remaining)

        is NodeFrame.BacklogDone -> {
            onCursor(f.cursor)
            s.copy(posCursor = f.cursor, catchingUp = false, backlogRemaining = 0)
        }

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
        is NodeFrame.Stats -> s.copy(
            stats = f,
            posHeld = if (f.posHeld > 0) f.posHeld else s.posHeld,
            posOldest = if (f.posOldest > 0) f.posOldest else s.posOldest,
        )

        is NodeFrame.Unknown -> s.copy(nodeNotice = "unknown frame \"${f.type}\" — node is newer than this app")
        is NodeFrame.Malformed -> s.copy(dropped = s.dropped + 1, lastDrop = "control frame: ${f.error}")
    }

    private fun LiveState.updateMsg(msgId: String, f: (ChatEntry) -> ChatEntry): LiveState {
        val i = messages.indexOfFirst { it.msgId == msgId }
        if (i < 0) return this
        return copy(messages = messages.toMutableList().also { it[i] = f(it[i]) })
    }
}
