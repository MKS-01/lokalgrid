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
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg

// Node config. Read-only mirror of the plan's defaults for now; staged-and-
// written-explicitly editing (never silent reconfigure, §6) arrives with the
// real node in Phase 03.
@Composable
fun ConfigScreen() {
    Column(
        Modifier.fillMaxSize().background(Lg.Paper)
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
    ) {
        SectionLabel("radios")
        InfoRow("wifi ap") { Pill("on demand", PillKind.OK) }
        InfoRow("ap idle timeout", "5 min")
        InfoRow("ble presence") { Pill("always", PillKind.OK) }
        InfoRow("lora duty ceiling", "1.0%")

        SectionLabel("position sharing")
        InfoRow("interval, moving", "60 s")
        InfoRow("interval, still", "10 min")
        InfoRow("decimate by", "50 m")

        SectionLabel("policy")
        InfoRow("max clients", "9")
        InfoRow("chat", "one channel, text only")

        SectionLabel("editing")
        InfoRow("writes") { Pill("phase 03 · real node", PillKind.NEUTRAL) }
        LgButton("Staged changes — write to node")
    }
}
