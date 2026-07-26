package dev.lokalgrid.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.loc.PhoneLocation
import dev.lokalgrid.app.net.BleClient
import dev.lokalgrid.app.ui.screens.ChatScreen
import dev.lokalgrid.app.ui.screens.ClientsScreen
import dev.lokalgrid.app.ui.screens.ConfigScreen
import dev.lokalgrid.app.ui.screens.LiveScreen
import dev.lokalgrid.app.ui.screens.MapScreen
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

enum class Tab(val label: String) { LIVE("Live"), MAP("Map"), CHAT("Chat"), CLIENTS("Clients"), CONFIG("Config") }

@Composable
fun AppShell(
    state: LiveState,
    onSendChat: (String, Boolean) -> Unit = { _, _ -> },
    onSharePosition: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onResetTrack: () -> Unit = {},
    onWriteConfig: (Map<String, String>) -> Unit = {},
    onReopenSetup: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onLocationChanged: () -> Unit = {},
    ble: BleClient? = null,
    onUseBle: (String) -> Unit = {},
    onUseWifi: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.LIVE) }
    var showDiag by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }

    // Location is asked for here, at the tap, because this is the one action that
    // needs it — the first-run flow deliberately does not ask (§6: ask when the
    // reason is on screen). If the user grants it, the share they asked for still
    // happens; if they refuse, the share still happens using the node's fix, and
    // says so.
    val context = LocalContext.current
    var shareAfterGrant by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onLocationChanged()
        if (shareAfterGrant) {
            shareAfterGrant = false
            onSharePosition()
        }
    }
    val sharePosition: () -> Unit = {
        if (PhoneLocation.granted(context)) {
            onSharePosition()
        } else {
            shareAfterGrant = true
            locationLauncher.launch(PhoneLocation.PERMISSIONS.toTypedArray())
        }
    }
    // Granted or revoked in Settings behind our back: re-read on every return.
    OnResumeEffect { onLocationChanged() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // The status bar is the way in to the Link screen — connection state is
        // where you'd tap to ask about connection state.
        SysBar(state) { showLink = !showLink }
        AppBar(tab, state, onLongPressTitle = { showDiag = !showDiag })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                Tab.LIVE -> LiveScreen(state, sharePosition, onRename, onResetTrack)
                Tab.MAP -> MapScreen(state, sharePosition)
                Tab.CHAT -> ChatScreen(state, onSendChat)
                Tab.CLIENTS -> ClientsScreen(state, onRename)
                Tab.CONFIG -> ConfigScreen(state, onWriteConfig, onReopenSetup)
            }
            // Both overlays cover the content only — the tab bar stays live, so
            // the app is never blocked behind a connection screen.
            if (showLink) {
                LinkScreen(
                    state = state,
                    ble = ble,
                    onUseBle = onUseBle,
                    onUseWifi = onUseWifi,
                    onReconnect = onReconnect,
                    onChangeNode = { showLink = false; onReopenSetup() },
                    onClose = { showLink = false },
                )
            }
            if (showDiag) DiagnosticsOverlay(state) { showDiag = false }
        }
        TabBar(tab) {
            tab = it
            showLink = false
        }
    }
}

@Composable
private fun SysBar(state: LiveState, onTap: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Lg.Deep)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            if (state.connected) "lokalgrid · node · you are ${state.selfName}" else "lokalgrid · node",
            color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp
        )
        Text(
            (if (state.connected) "${state.clientCount} client${if (state.clientCount == 1) "" else "s"}" else "offline") + "  ⌃link",
            color = if (state.connected) Lg.Ink3 else Lg.Warn, fontFamily = Mono, fontSize = 10.sp
        )
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
            Tab.LIVE, Tab.MAP -> when {
                state.catchingUp -> Pill("catching up · ${state.backlogRemaining}", PillKind.LORA)
                state.connected -> Pill(state.transport, PillKind.OK)
                else -> Pill("connecting", PillKind.NEUTRAL)
            }
            Tab.CHAT -> {
                val waiting = state.outbox.count { it.relayReason != null && !it.relayed }
                if (waiting > 0) Pill("$waiting on airtime", PillKind.LORA) else Pill("lane 2", PillKind.NEUTRAL)
            }
            Tab.CLIENTS -> Text(
                "${state.clientCount} of ${state.cap}",
                color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp
            )
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
        InfoRow("node url", state.url)
        InfoRow("status", state.status)
        InfoRow("connected", state.connected.toString())
        InfoRow("fixes received", state.fixCount.toString())
        InfoRow("dropped", state.dropped.toString())
        state.lastDrop?.let { InfoRow("last drop", it) }
        InfoRow("you", "${state.selfName} (id ${state.selfId})")
        InfoRow("transport", "${state.transport}${if (state.bleMtu > 0) " · mtu ${state.bleMtu}" else ""}")
        InfoRow("phone gps", gpsLabel(state))
        InfoRow("location grant", if (state.fineLocation) "fine" else "coarse or none")
        state.myFix?.let { InfoRow("my fix", "%.6f, %.6f".format(it.latDeg, it.lonDeg)) }
        state.lastShareSource?.let { InfoRow("last shared", it) }
        InfoRow("clients", "${state.clientCount} of ${state.cap}")
        InfoRow("duty cycle", "${"%.2f".format(state.duty * 100)}%")
        InfoRow("messages", state.messages.size.toString())
        InfoRow("position cursor", "seq ${state.posCursor} of ${state.posOldest}+${state.posHeld}")
        InfoRow("backlog", if (state.catchingUp) "${state.backlogRemaining} left" else "current")
        if (state.lostBefore > 0) InfoRow("aged out", "${state.lostBefore} before resume")
        InfoRow("waiting on airtime", state.outbox.count { !it.relayed }.toString())
        state.nodeNotice?.let { InfoRow("node said", it) }
        val r = state.latest
        if (r != null) {
            InfoRow("seq", r.seqLo.toString())
            InfoRow("epoch", r.epoch.toString())
            InfoRow("flags", "0x%08x".format(r.flags))
            InfoRow("hdop×10 / sv", "${r.hd} / ${r.sv}")
        }
    }
}
