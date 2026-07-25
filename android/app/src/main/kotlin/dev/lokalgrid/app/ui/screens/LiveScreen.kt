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
import dev.lokalgrid.app.ui.BigValue
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgTextField
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.accuracyMeters
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
            .padding(horizontal = 14.dp)
    ) {
        if (r == null) {
            SectionLabel("fix")
            InfoRow("status", state.status)
            InfoRow("waiting", "no fix yet")
            InfoRow("node url", state.url)
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

        val peers = peersOf(state)
        SectionLabel("people · ${peers.size + 1} on this node")
        InfoRow("you (${state.selfName})") { Pill("here", PillKind.OK) }
        for (p in peers) {
            InfoRow(if (p.ghost) "${p.name} · ghost" else p.name) { Pill(relative(r, p), p.staleness()) }
        }
        if (peers.isEmpty()) InfoRow("nobody else", "no peer has shared a position yet")

        SectionLabel("node")
        InfoRow("battery", "${r.bat}%")
        InfoRow("charging", if (r.charging) "yes" else "no")
        InfoRow("last fix", "#${r.seqLo} · ${state.fixCount} received" + if (state.dropped > 0) " · ${state.dropped} dropped" else "")
        state.stats?.let { st ->
            InfoRow("uptime", "${st.uptimeS / 60} min")
            InfoRow("link out", "${"%.2f".format(st.dutyActualPct)}% of ${"%.1f".format(state.duty * 100)}% ceiling")
            InfoRow("waiting to send", "${st.queueDepth} in queue")
        }

        // The forward flow, on this tab: your position, and your callsign.
        SectionLabel("actions")
        state.lastPeerSkip?.let { InfoRow("last position", it) }
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
