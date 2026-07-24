package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.MapLibreView
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.dummyPeers
import dev.lokalgrid.app.ui.relative
import dev.lokalgrid.app.ui.theme.Lg

@Composable
fun MapScreen(state: LiveState) {
    val peers = dummyPeers(state.latest)
    Column(Modifier.fillMaxSize().background(Lg.Paper)) {
        MapLibreView(state.latest, peers, Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            SectionLabel("people · dummy · phase 02")
            InfoRow("you") { Pill("here", PillKind.OK) }
            val self = state.latest
            for (p in peers) {
                InfoRow(p.name, value = self?.let { relative(it, p) } ?: "—")
            }
            SectionLabel("rendering")
            InfoRow("ellipse", "gnss uncertainty (hdop)")
            InfoRow("dashed") { Pill("interpolated, not observed", PillKind.NEUTRAL) }
        }
    }
}
