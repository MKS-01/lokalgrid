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
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.MapLibreView
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.peersOf
import dev.lokalgrid.app.ui.relative
import dev.lokalgrid.app.ui.staleness
import dev.lokalgrid.app.ui.theme.Lg

/**
 * Everyone on one map — the product in one screen. Peers are real now: they come
 * from the node's `peer` frames, and the forward flow is the button below, which
 * offers *your* position back to the node.
 */
@Composable
fun MapScreen(state: LiveState, onSharePosition: () -> Unit = {}) {
    val peers = peersOf(state)
    Column(Modifier.fillMaxSize().background(Lg.Paper)) {
        MapLibreView(state.latest, peers, state.track, Modifier.fillMaxWidth().weight(1f))
        // The map takes the free space; this panel keeps its own bottom gap so the
        // last row never sits flush on the tab bar.
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
            SectionLabel("people · ${peers.size + 1} on this node")
            InfoRow("you (${state.selfName})") {
                Pill(if (state.positionsShared > 0) "shared ×${state.positionsShared}" else "here", PillKind.OK)
            }
            val self = state.latest
            for (p in peers) {
                InfoRow(if (p.ghost) "${p.name} · ghost" else p.name) {
                    Pill(self?.let { relative(it, p) } ?: "—", p.staleness())
                }
            }
            if (peers.isEmpty()) {
                InfoRow("nobody else yet", "run a 2nd client, or --ghosts 2")
            }
            // The decimation reason, when the node declines to spend the link on a
            // position that barely moved. A silent skip would look like a dead GPS.
            state.lastPeerSkip?.let { InfoRow("last skip", it) }
            LgButton("Share my position", primary = true, enabled = state.connected, onClick = onSharePosition)
        }
    }
}
