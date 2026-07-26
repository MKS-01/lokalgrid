package dev.lokalgrid.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.loc.PhoneLocation
import dev.lokalgrid.app.net.BleClient
import dev.lokalgrid.app.onboarding.Setup
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

/** Where a step is, as a fact rather than a mood. */
private enum class StepState(val label: String, val kind: PillKind) {
    DONE("ok", PillKind.OK),
    WORKING("working", PillKind.LORA),
    NEEDS_YOU("needs you", PillKind.WARN),
    WAITING("phase 03", PillKind.NEUTRAL),
}

/**
 * The Link screen: what the app is connected over, how far along it is, and what
 * to do about it — as an ordered flow rather than settings scattered across tabs.
 *
 * It deliberately does **not** gate the app. The client owns its cursor and can
 * resume a delta whenever the link returns (§3), so the map, chat and history you
 * already have stay readable with the node unreachable. Blocking on a live link
 * would hide exactly the data the resume design exists to preserve.
 *
 * Not a sixth tab either (§6) — it opens over the content from the status bar,
 * and the tab bar stays live underneath.
 */
@Composable
fun LinkScreen(
    state: LiveState,
    ble: BleClient? = null,
    onUseBle: (String) -> Unit = {},
    onUseWifi: () -> Unit = {},
    onReconnect: () -> Unit,
    onChangeNode: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val wanted = remember { Setup.blePermissions + Setup.notificationPermission }
    var granted by remember { mutableStateOf(Setup.allGranted(context, wanted)) }
    var battery by remember { mutableStateOf(Setup.batteryExempt(context)) }
    var location by remember { mutableStateOf(PhoneLocation.granted(context)) }
    OnResumeEffect {
        granted = Setup.allGranted(context, wanted)
        battery = Setup.batteryExempt(context)
        location = PhoneLocation.granted(context)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = Setup.allGranted(context, wanted) }

    // Why BLE cannot be used, if it cannot: a missing permission and a switched-off
    // adapter are different problems with different fixes, and the step says which.
    val bleWhy: String? = when {
        ble == null -> "no bluetooth support in this build"
        !granted && wanted.isNotEmpty() -> "the app has not been granted Bluetooth permission yet"
        else -> ble.unavailableReason()
    }

    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<BleClient.Found>>(emptyList()) }
    if (scanning && ble != null && bleWhy == null) {
        LaunchedEffect(Unit) {
            ble.scan().collect { found = it }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper)
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = ScrollGap)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Link", color = Lg.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Pill(if (state.connected) "connected" else "offline", if (state.connected) PillKind.OK else PillKind.WARN)
        }
        Text(
            if (state.connected) "you are ${state.selfName} on this node · ${state.clientCount} of ${state.cap} clients"
            else "not talking to a node — everything below is what you already have",
            color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // 1 ─ permissions
        Step(
            n = 1,
            title = "permissions",
            state = if (wanted.isEmpty() || granted) StepState.DONE else StepState.NEEDS_YOU,
            detail = if (wanted.isEmpty()) "granted at install on this Android version"
            else if (granted) "bluetooth and notifications granted"
            else "bluetooth is needed before the node can be reached over BLE",
        )
        if (wanted.isNotEmpty() && !granted) {
            LgButton("Grant Bluetooth permissions", primary = true) { launcher.launch(wanted.toTypedArray()) }
        }
        // Location is a separate row because it is a separate promise: never used
        // to find the node, only to say where *you* are when you ask it to.
        InfoRow("location") {
            Pill(
                if (location) "granted · for sharing only" else "not granted",
                if (location) PillKind.OK else PillKind.NEUTRAL,
            )
        }
        Note(
            if (location) "Used only when you tap \"Share my position\". BLUETOOTH_SCAN stays neverForLocation — the app never derives a position from a scan."
            else "Not needed to use the app. Asked for at the tap on \"Share my position\"; without it the node's own fix is shared instead, labelled as the node's."
        )
        InfoRow("battery") {
            Pill(if (battery) "exempt" else "restricted", if (battery) PillKind.OK else PillKind.WARN)
        }
        if (!battery) {
            Note("Android will kill background sync in this state. It cannot be fixed from inside the app.")
            LgButton("Open battery settings") { Setup.openBatterySettings(context) }
        }

        // 2 ─ wifi / websocket, the transport that works today
        Step(
            n = 2,
            title = "wifi · websocket",
            state = when {
                state.connected -> StepState.DONE
                else -> StepState.NEEDS_YOU
            },
            detail = state.url,
        )
        InfoRow("status", state.status)
        if (!state.connected) {
            Note("The node must be running and on the same network. 10.0.2.2 resolves only on an emulator.")
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                LgButton(if (state.connected) "Reconnect" else "Try again", primary = !state.connected, onClick = onReconnect)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { LgButton("Change node", onClick = onChangeNode) }
        }

        // 3 ─ BLE, real now: the board has a GATT service
        val onBle = state.transport == "ble"
        Step(
            n = 3,
            title = "ble · always-on link",
            state = when {
                onBle && state.connected -> StepState.DONE
                onBle -> StepState.WORKING
                bleWhy != null -> StepState.NEEDS_YOU
                else -> StepState.WAITING
            },
            detail = when {
                onBle && state.connected -> "connected over ble · mtu ${state.bleMtu}"
                onBle -> "connecting over ble — ${state.status}"
                bleWhy != null -> bleWhy
                else -> "the node serves the same protocol over ble at ~2 mA"
            },
        )

        if (bleWhy != null) {
            ErrorState(
                title = "bluetooth is not usable",
                detail = bleWhy,
                actionLabel = if (!granted && wanted.isNotEmpty()) "Grant Bluetooth permissions" else null,
                onAction = if (!granted && wanted.isNotEmpty()) {
                    { launcher.launch(wanted.toTypedArray()) }
                } else null,
            )
        } else {
            Note(
                "BLE is the layer that lets the node stay reachable for a week instead of a day, " +
                    "and the one the phone can sync over with its screen off. Same session as " +
                    "wifi — cursors, chat and backlog carry across."
            )
            IconRow(
                LgIcon.Bluetooth,
                if (onBle) Lg.Lock else Lg.Ink3,
                "using ble",
                if (onBle) "yes" else "no, wifi",
            )

            if (scanning) {
                if (found.isEmpty()) {
                    WaitingState(
                        title = "scanning",
                        reason = "looking for anything advertising the lokalgrid service. " +
                            "The node advertises continuously, so a few seconds is enough.",
                        actionLabel = "Stop",
                        onAction = { scanning = false },
                    )
                } else {
                    SectionLabel("nodes in range")
                    for (f in found) {
                        InfoRow("${f.name} · ${f.address.takeLast(5)}") {
                            Pill("${f.rssi} dBm", if (f.rssi > -70) PillKind.OK else PillKind.NEUTRAL)
                        }
                        LgButton("Use this node over BLE", primary = true) {
                            scanning = false
                            onUseBle(f.address)
                        }
                    }
                }
            } else {
                LgButton(if (onBle) "Scan again" else "Scan for nodes", primary = !onBle) {
                    found = emptyList()
                    scanning = true
                }
                if (onBle) LgButton("Go back to wifi") { onUseWifi() }
            }
        }

        // 4 ─ the session itself: cursors, backlog, what resume will do
        Step(
            n = 4,
            title = "session",
            state = when {
                state.catchingUp -> StepState.WORKING
                state.connected -> StepState.DONE
                else -> StepState.WAITING
            },
            detail = when {
                state.catchingUp -> "catching up — ${state.backlogRemaining} of ${state.backlogTotal} left"
                state.connected -> "current with the node"
                state.posCursor > 0 -> "held at seq ${state.posCursor}; resumes from here"
                else -> "nothing received yet"
            },
        )
        InfoRow("your cursor", "seq ${state.posCursor}")
        InfoRow("node log", if (state.posOldest > 0) "seq ${state.posOldest} … ${state.posNewestKnown}" else "unknown")
        InfoRow("track held", "${state.track.size} points")
        InfoRow("messages", "${state.messages.size}")
        InfoRow("your gps") { Pill(gpsLabel(state), gpsKind(state)) }
        state.lastShareSource?.let { ReasonRow("last shared", it) }
        state.gapReason?.let {
            InfoRow("gap") { Pill("${state.lostBefore} lost", PillKind.WARN) }
            Note(it)
        }

        LgButton("Close", onClick = onClose)
    }
}

@Composable
private fun Step(n: Int, title: String, state: StepState, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$n · ${title.uppercase()}",
            color = Lg.Ink3, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp,
        )
        Pill(state.label, state.kind)
    }
    Text(detail, color = Lg.Ink2, fontFamily = Mono, fontSize = 11.sp)
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).background(Lg.Rule2).fillMaxWidth())
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Lg.Ink3, fontFamily = Mono, fontSize = 9.sp, lineHeight = 14.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}
