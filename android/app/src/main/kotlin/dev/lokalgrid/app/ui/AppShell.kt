package dev.lokalgrid.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.ui.screens.ChatScreen
import dev.lokalgrid.app.ui.screens.ClientsScreen
import dev.lokalgrid.app.ui.screens.ConfigScreen
import dev.lokalgrid.app.ui.screens.LiveScreen
import dev.lokalgrid.app.ui.screens.MapScreen
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

enum class Tab(val label: String) { LIVE("Live"), MAP("Map"), CHAT("Chat"), CLIENTS("Clients"), CONFIG("Config") }

@Composable
fun AppShell(state: LiveState) {
    var tab by remember { mutableStateOf(Tab.LIVE) }
    var showDiag by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        SysBar(state)
        AppBar(tab, state, onLongPressTitle = { showDiag = !showDiag })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                Tab.LIVE -> LiveScreen(state)
                Tab.MAP -> MapScreen(state)
                Tab.CHAT -> ChatScreen()
                Tab.CLIENTS -> ClientsScreen(state)
                Tab.CONFIG -> ConfigScreen()
            }
            if (showDiag) DiagnosticsOverlay(state) { showDiag = false }
        }
        TabBar(tab) { tab = it }
    }
}

@Composable
private fun SysBar(state: LiveState) {
    Row(
        Modifier.fillMaxWidth().background(Lg.Deep).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("lokalgrid · node", color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)
        Text(if (state.connected) "1 client" else "offline", color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppBar(tab: Tab, state: LiveState, onLongPressTitle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Lg.Rule, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
                    size = androidx.compose.ui.geometry.Size(size.width, 1f))
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Long-press the title opens Diagnostics — not a sixth tab (§6 UI rule).
        Text(
            tab.label,
            color = Lg.Ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPressTitle)
        )
        when (tab) {
            Tab.LIVE, Tab.MAP ->
                if (state.connected) Pill("wifi", PillKind.OK) else Pill("connecting", PillKind.NEUTRAL)
            Tab.CHAT -> Pill("lane 2", PillKind.NEUTRAL)
            Tab.CLIENTS -> Text("1 of 9", color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)
            Tab.CONFIG -> Text("admin only", color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Lg.Rule, size = androidx.compose.ui.geometry.Size(size.width, 1f))
            }
    ) {
        for (t in Tab.entries) {
            val on = t == current
            Box(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(t) }
                    .then(if (on) Modifier.drawBehind {
                        drawRect(Lg.Lock, size = androidx.compose.ui.geometry.Size(size.width, 2f))
                    } else Modifier)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t.label,
                    color = if (on) Lg.Lock else Lg.Ink3,
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsOverlay(state: LiveState, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper.copy(alpha = 0.97f))
            .clickable(onClick = onClose)
            .padding(16.dp)
    ) {
        SectionLabel("diagnostics · tap to close")
        InfoRow("status", state.status)
        InfoRow("connected", state.connected.toString())
        InfoRow("fixes received", state.fixCount.toString())
        InfoRow("dropped", state.dropped.toString())
        state.lastDrop?.let { InfoRow("last drop", it) }
        val r = state.latest
        if (r != null) {
            InfoRow("seq", r.seqLo.toString())
            InfoRow("epoch", r.epoch.toString())
            InfoRow("flags", "0x%08x".format(r.flags))
            InfoRow("hdop×10 / sv", "${r.hd} / ${r.sv}")
        }
    }
}
