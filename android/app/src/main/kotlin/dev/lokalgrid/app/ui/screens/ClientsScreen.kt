package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.lokalgrid.app.ui.CardSub
import dev.lokalgrid.app.ui.CardTitle
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgCard
import dev.lokalgrid.app.ui.LgTextField
import dev.lokalgrid.app.ui.MeterBar
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.ScrollGap
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg

/**
 * Who is on the node, and what each of them is spending. The cap is 9 (the
 * NimBLE ceiling) but the real bottleneck is airtime, so the meters below are
 * the honest measure of "how full is this node" — not the connection count.
 * Both roster and meters are node-computed; the app never estimates them.
 */
@Composable
fun ClientsScreen(state: LiveState, onRename: (String) -> Unit = {}) {
    var callsign by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Lg.Paper)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = ScrollGap)
    ) {
        SectionLabel("clients · ${state.clientCount} of ${state.cap}")
        if (state.roster.isEmpty()) {
            LgCard(selected = true) {
                CardTitle("this device")
                CardSub(if (state.connected) "wifi · connected · waiting for roster" else "connecting…")
            }
        } else {
            for (c in state.roster) {
                val mine = c.id == state.selfId
                val stat = state.stats?.clients?.firstOrNull { it.id == c.id }
                LgCard(selected = mine) {
                    CardTitle(if (mine) "${c.name} · this device" else c.name)
                    CardSub(
                        buildString {
                            append(if (c.isGhost) "ghost · synthetic peer" else "${c.transport} · id ${c.id}")
                            stat?.let { append(" · ${it.messages} msg · ${it.airtimeMs} ms airtime") }
                        }
                    )
                }
            }
        }

        SectionLabel("airtime, last hour")
        val st = state.stats
        if (st == null) {
            InfoRow("waiting", "no stats frame from the node yet")
        } else {
            for (c in st.clients) {
                InfoRow(c.name, "${c.sharePct}% · ${c.airtimeMs} ms")
                MeterBar(c.sharePct / 100f, if (c.id == state.selfId) Lg.Lock else Lg.Sig)
            }
            InfoRow("duty cycle used") {
                Pill(
                    "${"%.2f".format(st.dutyActualPct)}% of ${"%.1f".format(state.duty * 100)}%",
                    if (st.dutyUsedPct > 90) PillKind.WARN else PillKind.OK,
                )
            }
            MeterBar((st.dutyUsedPct / 100).toFloat(), if (st.dutyUsedPct > 90) Lg.Warn else Lg.Lora)
            InfoRow("queue depth", "${st.queueDepth} waiting")
            InfoRow("node uptime", "${st.uptimeS / 60} min")
        }

        SectionLabel("your callsign")
        InfoRow("now", state.selfName)
        LgTextField(
            value = callsign,
            onValueChange = { callsign = it },
            placeholder = "new callsign…",
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            onSubmit = { onRename(callsign); callsign = "" },
        )
        LgButton("Rename on the roster", primary = true, enabled = state.connected && callsign.isNotBlank()) {
            onRename(callsign)
            callsign = ""
        }
    }
}
