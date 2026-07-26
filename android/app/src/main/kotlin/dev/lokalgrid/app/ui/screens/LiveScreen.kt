package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.loc.PhoneLocation
import dev.lokalgrid.app.ui.BigValue
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgTextField
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.ReasonRow
import dev.lokalgrid.app.ui.ScrollGap
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.accuracyMeters
import dev.lokalgrid.app.ui.gpsKind
import dev.lokalgrid.app.ui.gpsLabel
import dev.lokalgrid.app.ui.myPeer
import dev.lokalgrid.app.ui.peersOf
import dev.lokalgrid.app.ui.relative
import dev.lokalgrid.app.ui.staleness
import dev.lokalgrid.app.ui.theme.Lg
import dev.lokalgrid.protocol.TrackRecord
import kotlin.math.abs

/**
 * The fix, the people, the node — and the three things you can do to it: share
 * where you are, take a callsign, restart the track.
 */
@Composable
fun LiveScreen(
    state: LiveState,
    onSharePosition: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onResetTrack: () -> Unit = {},
) {
    val r = state.latest
    var callsign by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = ScrollGap)
    ) {
        if (r == null) {
            SectionLabel("fix")
            InfoRow("status", state.status)
            InfoRow("waiting", "no fix yet")
            InfoRow("node url", state.url)
            // Your own GPS does not depend on the node, so it is shown even with
            // nothing arriving — the app is not gated behind a live link.
            YourPosition(state)
            return@Column
        }

        SectionLabel("fix")
        InfoRow("fix") {
            Pill(if (r.fix3d) "3D · ${r.sv} sv" else "2D · ${r.sv} sv", if (r.fix3d) PillKind.OK else PillKind.WARN)
        }
        BigValue("%.5f %s\n%.5f %s".format(abs(r.latDeg), if (r.latDeg >= 0) "N" else "S", abs(r.lonDeg), if (r.lonDeg >= 0) "E" else "W"))
        InfoRow("accuracy", "± %.0f m (hdop %.1f)".format(accuracyMeters(r.hd), r.hd / 10.0))
        InfoRow("altitude", if (r.baro == TrackRecord.BARO_ABSENT) "${r.alt} m · no baro" else "${r.alt} m · baro ${r.baro}")
        if (!r.timeValid) InfoRow("time") { Pill("invalid — repair on client", PillKind.WARN) }

        YourPosition(state)
        val me = myPeer(state)

        val peers = peersOf(state)
        SectionLabel("people · ${peers.size + 1} on this node")
        InfoRow("you (${state.selfName})") { Pill("here", PillKind.OK) }
        for (p in peers) {
            InfoRow(if (p.ghost) "${p.name} · ghost" else p.name) {
                // Measured from your phone when it knows, from the node otherwise.
                Pill(me?.let { relative(it, p) } ?: relative(r, p), p.staleness())
            }
        }
        if (peers.isEmpty()) InfoRow("nobody else", "no peer has shared a position yet")

        SectionLabel("node")
        // The node's own fix, with its age. A node that is standing still and a
        // node that has lost its satellites both leave the dot where it was —
        // the only difference is this row, so it is not optional (§6).
        state.stats?.let { st ->
            val age = st.gnssAgeS
            InfoRow("node fix") {
                when {
                    st.gnssSource != "gnss" -> Pill("synthetic track", PillKind.LORA)
                    age < 0 -> Pill("no fix yet · ${st.gnssSats} sv", PillKind.WARN)
                    age <= 3 -> Pill("live · ${st.gnssSats} sv · hdop %.1f".format(st.gnssHdop / 10.0), PillKind.OK)
                    else -> Pill("${age}s old · ${st.gnssSats} sv", PillKind.WARN)
                }
            }
            if (st.gnssSource == "gnss" && age > 3) {
                ReasonRow(
                    "why it stopped",
                    "the node has not had a fix for ${age} s, so it is logging nothing rather " +
                        "than repeating a position it can no longer see. Indoors that is normal.",
                )
            }
        }
        InfoRow("battery", if (r.bat > 0) "${r.bat}%" else "usb, no cell")
        InfoRow("charging", if (r.charging) "yes" else "no")
        InfoRow("last fix", "#${r.seqLo} · ${state.fixCount} received" + if (state.dropped > 0) " · ${state.dropped} dropped" else "")
        // Resume state. The cursor is ours to state, the log is the node's to own
        // (§3) — so both numbers are shown, and a gap is named rather than drawn
        // through as if the track were continuous.
        SectionLabel("history")
        InfoRow("your cursor", "seq ${state.posCursor}")
        InfoRow("node log", "seq ${state.posOldest} … ${state.posNewestKnown} · ${state.posHeld} held")
        if (state.catchingUp) {
            InfoRow("catching up") {
                Pill("${state.backlogRemaining} of ${state.backlogTotal} left", PillKind.NEUTRAL)
            }
        } else if (state.backlogTotal > 0) {
            InfoRow("resumed") { Pill("${state.backlogTotal} fixes replayed", PillKind.OK) }
        }
        state.gapReason?.let {
            InfoRow("gap") { Pill("${state.lostBefore} lost", PillKind.WARN) }
            InfoRow("why", it)
        }
        InfoRow("track held", "${state.track.size} points")

        state.stats?.let { st ->
            InfoRow("uptime", "${st.uptimeS / 60} min")
            InfoRow("link out", "${"%.2f".format(st.dutyActualPct)}% of ${"%.1f".format(state.duty * 100)}% ceiling")
            InfoRow("waiting to send", "${st.queueDepth} in queue")
        }

        // The forward flow, on this tab: your position, and your callsign.
        SectionLabel("actions")
        state.lastPeerSkip?.let { ReasonRow("last position", it) }
        // What the button will actually send, before it is pressed — the same rule
        // as showing a message's airtime cost up front (§2, the airtime economy).
        InfoRow("will share", if (me != null) "your phone's fix" else "the node's fix, labelled")
        state.lastShareSource?.let { ReasonRow("last shared", it) }
        LgButton(
            if (state.positionsShared > 0) "Share my position (${state.positionsShared} sent)" else "Share my position",
            primary = true,
            enabled = state.connected,
            onClick = onSharePosition,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f)) {
                LgTextField(
                    value = callsign,
                    onValueChange = { callsign = it },
                    placeholder = "callsign (now: ${state.selfName})",
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = { onRename(callsign); callsign = "" },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(110.dp)) {
                LgButton("Rename", enabled = state.connected && callsign.isNotBlank()) {
                    onRename(callsign)
                    callsign = ""
                }
            }
        }
        LgButton("Reset the track", enabled = state.connected, onClick = onResetTrack)
    }
}

/**
 * Where *this phone* is — a separate section from the node's fix, because they are
 * two different claims. Sharing used to offer the node's fix as yours; it was
 * honest for a phone sitting next to the node, and it is now the fallback rather
 * than the answer.
 *
 * Every non-fix state says why, so an empty position never reads as a broken app.
 */
@Composable
private fun YourPosition(state: LiveState) {
    val me = myPeer(state)
    SectionLabel("your position · this phone")
    InfoRow("gps") { Pill(gpsLabel(state), gpsKind(state)) }
    if (me != null) {
        BigValue(
            "%.5f %s\n%.5f %s".format(
                abs(me.latDeg), if (me.latDeg >= 0) "N" else "S",
                abs(me.lonDeg), if (me.lonDeg >= 0) "E" else "W",
            )
        )
        if (!state.fineLocation) {
            InfoRow("precision", "coarse only — the ring is the accuracy you granted")
        }
    }
    when (state.gps) {
        is PhoneLocation.State.NotGranted ->
            ReasonRow("why not", "location is asked for when you share, not at startup")
        is PhoneLocation.State.ProvidersOff ->
            ReasonRow("why not", "turn location on in system settings")
        is PhoneLocation.State.Waiting ->
            ReasonRow("why not", "a cold GNSS start takes a minute outdoors, longer inside")
        is PhoneLocation.State.Live -> {}
    }
}
