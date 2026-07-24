package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.ui.CardSub
import dev.lokalgrid.app.ui.CardTitle
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgCard
import dev.lokalgrid.app.ui.MeterBar
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg

// Up to 9 clients now (cap raised to the NimBLE ceiling, 2026-07-23). The live
// arbitration + airtime accounting is Phase 04; this shows the shape.
@Composable
fun ClientsScreen(state: LiveState) {
    Column(
        Modifier.fillMaxSize().background(Lg.Paper)
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
    ) {
        SectionLabel("clients · 1 of 9")
        LgCard(selected = true) {
            CardTitle("this device")
            CardSub(if (state.connected) "wifi · mock node · connected" else "connecting…")
        }
        LgCard { CardTitle("user a"); CardSub("ble · presence only · phase 02") }
        LgCard { CardTitle("user b"); CardSub("ble · out of range · phase 02") }

        SectionLabel("airtime, last hour · phase 04")
        InfoRow("this device", "31%"); MeterBar(0.31f)
        InfoRow("user a", "44%"); MeterBar(0.44f)
        InfoRow("user b", "9%"); MeterBar(0.09f)
        InfoRow("duty cycle used", "0.61% of 1%")

        LgButton("Pair a new device")
    }
}
