package dev.lokalgrid.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lokalgrid.app.loc.PhoneLocation
import dev.lokalgrid.app.net.BleClient
import dev.lokalgrid.app.net.NodeClient
import dev.lokalgrid.app.net.WifiBinding
import dev.lokalgrid.app.ui.hdopTimes10
import dev.lokalgrid.protocol.ClientFrame
import dev.lokalgrid.protocol.Lane
import dev.lokalgrid.protocol.NodeFrame
import dev.lokalgrid.protocol.RosterEntry
import dev.lokalgrid.protocol.TrackRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /**
     * A local, monotonic row id. Only the list needs it: `msgId` is null for other
     * people's messages and `seq` is the node's to assign, so neither is a key we
     * can *guarantee* is unique — and a duplicate key crashes a `LazyColumn`
     * outright. A node that repeats itself must render oddly, never take the app
     * down; the reducer above is what keeps repeats from becoming two rows.
     */
    val rowId: Long = 0,
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
    val key: Long get() = rowId
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
    // the phone's own GPS — what *you* contribute to the map
    val gps: PhoneLocation.State = PhoneLocation.State.NotGranted,
    val myFix: PhoneLocation.Fix? = null,   // last fix from this phone, kept with its age
    val fineLocation: Boolean = false,      // precise, or coarse-only
    val lastShareSource: String? = null,    // which fix actually went, stated plainly
    // which wire this session is on, and what BLE negotiated on it
    val transport: String = "wifi",
    val bleMtu: Int = 0,
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

/**
 * How old the phone's own fix may be and still be worth sharing. Past this it is
 * history, not a position — and the node's fix, clearly labelled, is the more
 * honest thing to offer.
 */
private const val MY_FIX_MAX_AGE_S = 120L

class LiveViewModel(
    private val url: String = DEFAULT_URL,
    private val savedCursor: Long = 0,
    private val onCursor: (Long) -> Unit = {},
    private val gps: PhoneLocation? = null,
    /** Pins the socket to the WiFi the node is on; null falls back to whatever
     *  route Android picks, which is fine for the mock and wrong for a SoftAP. */
    private val binding: WifiBinding? = null,
    /** The BLE transport. Same protocol, different wire — see BleClient. */
    private val ble: BleClient? = null,
    /** "wifi" or "ble". The session is identical either way (§3): only the bytes
     *  travel differently, so nothing above this layer changes. */
    private val transport: String = "wifi",
    private val bleAddress: String? = null,
) : ViewModel() {

    private val client = NodeClient(url, binding)
    private val useBle: Boolean get() = transport == "ble" && bleAddress != null && ble != null

    private val _state = MutableStateFlow(
        LiveState(url = url, posCursor = savedCursor, transport = transport)
    )
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var msgCounter = 0

    /** Row ids for the chat list — local, monotonic, never from the wire. */
    private var rowCounter = 0L
    private fun nextRowId(): Long = ++rowCounter

    private var lastSaved = savedCursor
    private var link: Job? = null
    private var gpsJob: Job? = null

    /** Consecutive failed attempts, for the backoff and for what the UI says. */
    private var attempt = 0
    private var lastError: String? = null

    init {
        connect()
        watchGps()
        // Joining the node's AP is the event that makes the next attempt work, so
        // it retries immediately rather than sitting out the backoff.
        binding?.onChanged = {
            _state.value = _state.value.copy(status = "wifi changed — reconnecting")
            connect()
        }
    }

    override fun onCleared() {
        binding?.onChanged = null
        onCursor(_state.value.posCursor)
        super.onCleared()
    }

    /**
     * Listen to the phone's own GNSS. Re-callable, because the answer changes
     * outside the app: a permission granted in a dialog, or location switched off
     * in Settings, both need the flow rebuilt rather than a stale state left on
     * screen claiming something that is no longer true.
     */
    fun watchGps() {
        val src = gps ?: return
        gpsJob?.cancel()
        _state.value = _state.value.copy(fineLocation = src.fineGranted())
        gpsJob = viewModelScope.launch {
            src.updates().collect { st ->
                _state.value = _state.value.copy(
                    gps = st,
                    // A fix survives a later ProvidersOff/NotGranted: it is still
                    // the last place we know this phone was, and its age says how
                    // much to trust it. Dropping it would erase a true fact.
                    myFix = (st as? PhoneLocation.State.Live)?.fix ?: _state.value.myFix,
                )
            }
        }
    }

    /**
     * Keep a socket to the node, for as long as this ViewModel lives.
     *
     * The loop is the fix for the worst bug the first hardware test found: the
     * app opened its socket at launch, the phone joined the node's WiFi *after*
     * that, and nothing ever tried again — the only way back was to kill the app.
     * A field node you have to restart an app to talk to is not a field node.
     *
     * Backoff is bounded and **named on screen**: "retrying in 4 s" is a state,
     * a spinner is not (§6). A WiFi change resets it to zero, because joining the
     * AP is the event most likely to make the next attempt the one that works.
     */
    private fun connect() {
        link?.cancel()
        attempt = 0
        link = viewModelScope.launch {
            while (isActive) {
                if (attempt > 0) {
                    val wait = backoffMs(attempt)
                    _state.value = _state.value.copy(
                        connected = false,
                        status = "retrying in ${wait / 1000} s (attempt ${attempt + 1}) · ${lastError ?: "no link"}",
                    )
                    delay(wait)
                }
                attempt++

                val events = if (useBle) ble!!.events(bleAddress!!) else client.events()
                events.collect { ev ->
                    if (ev is NodeClient.Event.Status) {
                        if (ev.connected) attempt = 0 else lastError = ev.detail
                        if (useBle) _state.value = _state.value.copy(bleMtu = ble!!.mtu)
                    }
                    val next = reduce(_state.value, ev)
                    _state.value = next
                    // Persist occasionally rather than every record: a killed app
                    // should resume a few seconds behind, not from zero. The exact
                    // figure is re-stated to the node on reconnect, which corrects
                    // any drift.
                    if (next.posCursor - lastSaved >= 30) {
                        lastSaved = next.posCursor
                        onCursor(next.posCursor)
                    }
                }
                // The flow ended, which means the socket failed or closed. Round
                // again — the cursor survives, so this costs a delta, not a resync.
            }
        }
    }

    /** 1, 2, 4, 8, capped at 15 s — long enough not to hammer a node that is off,
     *  short enough that walking into range feels immediate. */
    private fun backoffMs(attempt: Int): Long =
        (1000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(15_000L)

    /**
     * Drop the socket and open a new one. The cursor survives, so this resumes a
     * delta rather than starting over — reconnecting is cheap by design, which is
     * the whole reason the app does not need to gate itself behind a live link.
     */
    fun reconnect() {
        _state.value = _state.value.copy(connected = false, status = "reconnecting to $url…")
        connect()
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
            rowId = nextRowId(),
            msgId = id,
            name = _state.value.selfName,
            from = _state.value.selfId,
            text = body,
            epoch = System.currentTimeMillis() / 1000,
            lane = lane,
            mine = true,
        )
        val ok = sendFrame(ClientFrame.Send(id, body, lane))
        _state.value = _state.value.copy(
            messages = _state.value.messages +
                pending.copy(error = if (ok) null else "not sent — no link to the node")
        )
    }

    /** Rename yourself on the roster — the node refuses a clash, with a reason. */
    fun setName(name: String) {
        if (name.isNotBlank()) sendFrame(ClientFrame.Name(name.trim()))
    }

    /** One place decides which wire a frame goes out on. */
    private fun sendFrame(frame: ClientFrame): Boolean =
        if (useBle) ble!!.send(frame) else client.send(frame)

    /** Restart the mock's synthetic track. Phase 03 replaces this with a real one. */
    fun resetTrack() = sendFrame(ClientFrame.Reset)

    /**
     * State our cursors and ask for the delta. The client is authoritative about
     * what it has received (§3), so this is the app *telling* the node where it
     * got to — chat and positions separately — never the node assuming.
     */
    fun resync() {
        val s = _state.value
        sendFrame(ClientFrame.Cursor(s.messages.maxOfOrNull { it.seq } ?: 0, s.posCursor))
    }

    /**
     * Share where *you* are. The node decides whether it moved far enough to be
     * worth the link (decimation by distance, §3) and answers either way.
     *
     * The phone's own GNSS is the position when it has one. When it does not —
     * permission refused, location off, GNSS still cold — the node's fix goes
     * instead and the UI *says* that it was the node's, which is honest for a
     * phone standing next to the node and keeps the path exercised. What never
     * happens is a position going out under a source nobody stated.
     */
    fun shareMyPosition() {
        val s = _state.value
        val mine = s.myFix?.takeIf { it.ageS() <= MY_FIX_MAX_AGE_S }
        val frame: ClientFrame.Pos
        val source: String
        if (mine != null) {
            frame = ClientFrame.Pos(
                latE7 = mine.latE7,
                lonE7 = mine.lonE7,
                hd = hdopTimes10(mine.accuracyM),
                epoch = mine.epochS,
            )
            source = "your phone · ${mine.provider} · ±${accuracyLabel(mine.accuracyM)}"
        } else {
            val node = s.latest ?: run {
                _state.value = s.copy(lastPeerSkip = noFixReason(s), lastShareSource = null)
                return
            }
            frame = ClientFrame.Pos(latE7 = node.latE7, lonE7 = node.lonE7, hd = node.hd, epoch = node.epoch)
            source = "the node's own fix — ${noFixReason(s)}"
        }
        val ok = sendFrame(frame)
        _state.value = _state.value.copy(
            positionsShared = if (ok) _state.value.positionsShared + 1 else _state.value.positionsShared,
            lastShareSource = if (ok) source else null,
            lastPeerSkip = if (ok) _state.value.lastPeerSkip else "not shared — no link to the node",
        )
    }

    /** Why the phone's own fix wasn't used — a reason, always, never a silence. */
    private fun noFixReason(s: LiveState): String = when {
        gps == null -> "no location source on this build"
        s.gps is PhoneLocation.State.NotGranted -> "location not granted to this app"
        s.gps is PhoneLocation.State.ProvidersOff -> "location is switched off on this phone"
        s.myFix != null -> "your phone's fix is ${s.myFix.ageS()} s old"
        else -> "your phone has no fix yet — a cold GNSS start takes a minute outdoors"
    }

    private fun accuracyLabel(accuracyM: Double): String =
        if (accuracyM <= 0) "unknown" else "%.0f m".format(accuracyM)

    /** Write staged config. Explicit, never mid-edit (§6). */
    fun writeConfig(patch: Map<String, String>) {
        if (patch.isNotEmpty()) sendFrame(ClientFrame.ConfigSet(patch))
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

        // The authoritative record of a message. If it echoes one we already hold
        // — our own pending bubble, or a message the node re-sent because we
        // asked for a backlog that overlapped — we upgrade it in place instead of
        // showing it twice. `seq` is the node's identity for a message (§3), so
        // two frames with the same seq are one message, not two.
        is NodeFrame.Chat -> {
            val i = s.messages.indexOfFirst {
                (it.mine && it.msgId != null && it.msgId == f.msgId) ||
                    (f.seq > 0 && it.seq == f.seq)
            }
            val entry = ChatEntry(
                rowId = nextRowId(),
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
                        // Keep the row's identity and our queue state: the node's
                        // echo carries what it knows, not what we know about the
                        // link out — and the row must not jump in the list.
                        rowId = old.rowId,
                        msgId = entry.msgId ?: old.msgId,
                        mine = entry.mine || old.mine,
                        relayReason = old.relayReason,
                        relayEtaMs = old.relayEtaMs,
                        relayed = old.relayed,
                        error = old.error,
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
