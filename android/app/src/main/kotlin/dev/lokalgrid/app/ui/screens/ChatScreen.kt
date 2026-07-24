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
import dev.lokalgrid.app.ui.CardSub
import dev.lokalgrid.app.ui.CardTitle
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgCard
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg

// Wireframe layout, static sample content. One shared channel, text only (§2).
// Wired to real messages in Phase 02, once the mock replays chat + clients.
@Composable
fun ChatScreen() {
    Column(
        Modifier.fillMaxSize().background(Lg.Paper)
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
    ) {
        SectionLabel("one shared channel · phase 02")
        LgCard { CardTitle("user a · 09:14"); CardSub("reached the ridge, taking the north path") }
        LgCard(selected = true) { CardTitle("you · 09:16"); CardSub("copy, we are 20 min behind") }
        LgCard { CardTitle("user b · 09:31"); CardSub("water source dry, carry extra") }

        SectionLabel("outbound queue")
        InfoRow("your message") { Pill("queued 40 s", PillKind.NEUTRAL) }
        InfoRow("ahead of you", "user a ×1")
        InfoRow("airtime credit", "340 / 1000 ms")

        LgButton("Send", primary = true)
        LgButton("Send as emergency (lane 0)")
    }
}
