package dev.lokalgrid.app.onboarding

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lokalgrid.app.Prefs
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgTextField
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.ScrollGap
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

/**
 * First run, four steps: what this is · permissions · battery · the node.
 *
 * Rules it follows, all from §6: nothing is a spinner, every state names itself,
 * and no step lies about what it did. Skipping is allowed and *recorded* — a
 * setup you can't leave is worse than one you can re-enter, and the Config tab
 * reopens this whenever you want.
 */
@Composable
fun OnboardingScreen(prefs: Prefs, onDone: (String) -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(prefs.nodeUrl) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(Lg.Deep).padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("lokalgrid · setup", color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)
            Text("step ${step + 1} of 4", color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)
        }

        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            when (step) {
                0 -> StepIntro()
                1 -> StepPermissions(context)
                2 -> StepBattery(context)
                else -> StepNode(url) { url = it }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (step > 0) {
                Box(Modifier.weight(1f)) { LgButton("Back") { step-- } }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f)) {
                LgButton(if (step < 3) "Next" else "Start", primary = true) {
                    if (step < 3) {
                        step++
                    } else {
                        prefs.nodeUrl = url
                        prefs.onboarded = true
                        onDone(url)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIntro() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = ScrollGap)) {
        Spacer(Modifier.height(12.dp))
        Text("lokalgrid", color = Lg.Ink, fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "one node · everyone on one map",
            color = Lg.Lock, fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Body(
            "A shared field node: one T-Beam serves several phones over WiFi and BLE, " +
                "with LoRa as the link out. No carrier, no internet, no server."
        )
        SectionLabel("what this app shows you")
        InfoRow("positions", "yours, from this phone, and everyone's")
        InfoRow("uncertainty", "always — ring, age, 2D/3D")
        InfoRow("chat", "one shared channel, text only")
        InfoRow("the queue", "why a message is waiting")
        Body(
            "Setup is three short steps: Bluetooth permission, one Android battery " +
                "setting that silently kills background sync, and which node to talk to."
        )
        Body("You can skip any of them and finish setup later from the Config tab.")
    }
}

@Composable
private fun StepPermissions(context: Context) {
    val wanted = remember { Setup.blePermissions + Setup.notificationPermission }
    // Re-check on every resume: the user can change these in Settings behind our
    // back, and a stale "granted" is exactly the kind of lie this UI must not tell.
    var granted by remember { mutableStateOf(wanted.associateWith { Setup.granted(context, it) }) }
    val refresh = { granted = wanted.associateWith { Setup.granted(context, it) } }
    OnResume(refresh)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = ScrollGap)) {
        Spacer(Modifier.height(12.dp))
        Text("Permissions", color = Lg.Ink, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Body(
            "BLE is the always-on layer: ~2 mA, so the node can stay reachable for a week " +
                "instead of a day. WiFi only comes up on demand for bulk transfer."
        )

        SectionLabel("asked for")
        if (wanted.isEmpty()) {
            InfoRow("nothing", "this Android version grants these at install")
        }
        for (p in wanted) {
            InfoRow(Setup.label(p)) {
                Pill(if (granted[p] == true) "granted" else "not yet", if (granted[p] == true) PillKind.OK else PillKind.NEUTRAL)
            }
            Note(Setup.why(p))
        }

        SectionLabel("not asked for here")
        InfoRow("location") { Pill("later, at the tap", PillKind.NEUTRAL) }
        Note(
            "BLUETOOTH_SCAN is declared neverForLocation, so finding the node never needs " +
                "your location. Sharing where you are does — so that permission is asked for " +
                "the first time you tap \"Share my position\", where the reason is on screen. " +
                "Refuse it and the app still works; your dot just isn't on the map."
        )

        if (wanted.isNotEmpty() && wanted.any { granted[it] != true }) {
            LgButton("Grant Bluetooth permissions", primary = true) { launcher.launch(wanted.toTypedArray()) }
            Note("Denied by accident? Android stops asking after two refusals — the Config tab links to app settings.")
        } else if (wanted.isNotEmpty()) {
            Note("All granted. BLE itself arrives with the hardware in Phase 03 — nothing connects over Bluetooth yet.")
        }
    }
}

@Composable
private fun StepBattery(context: Context) {
    var exempt by remember { mutableStateOf(Setup.batteryExempt(context)) }
    OnResume { exempt = Setup.batteryExempt(context) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = ScrollGap)) {
        Spacer(Modifier.height(12.dp))
        Text("Battery", color = Lg.Ink, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Body(
            "This is the step that actually breaks things. Android — One UI especially — " +
                "puts unused apps into \"Deep sleeping\" and stops their background work " +
                "without telling the app. Sync then fails silently, which looks like a bug " +
                "in the node."
        )

        SectionLabel("this device")
        InfoRow("model", Setup.deviceLabel)
        InfoRow("battery exemption") {
            Pill(if (exempt) "exempt" else "restricted", if (exempt) PillKind.OK else PillKind.WARN)
        }
        if (Setup.isOneUi) {
            Note("One UI: Settings → Battery → Background usage limits → remove Lokalgrid from \"Deep sleeping apps\", and set it to Unrestricted.")
        } else {
            Note("Set battery usage for Lokalgrid to Unrestricted so background sync survives.")
        }

        LgButton(
            if (exempt) "Battery settings (already exempt)" else "Open battery settings",
            primary = !exempt,
        ) { Setup.openBatterySettings(context) }

        SectionLabel("if it happens anyway")
        Body(
            "The app cannot prevent this, only notice it — so when sync stops for a reason " +
                "that looks like the OS, Diagnostics says so rather than showing a spinner."
        )
    }
}

@Composable
private fun StepNode(url: String, onUrl: (String) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = ScrollGap)) {
        Spacer(Modifier.height(12.dp))
        Text("The node", color = Lg.Ink, fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Body(
            "The T-Beam serves this itself now. The mock node is still there for " +
                "work with the board unplugged: `npm start` in mock-node/, same protocol."
        )

        SectionLabel("websocket url")
        LgTextField(
            value = url,
            onValueChange = onUrl,
            placeholder = Prefs.DEFAULT_URL,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Note("The node: ws://192.168.4.1/ws — join its \"lokalgrid\" WiFi first.")
        Note("Emulator: ws://10.0.2.2:8787 — that alias only works there.")
        Note("Real phone: ws://<your dev machine's LAN IP>:8787, same WiFi.")
        Note("Phase 03: the T-Beam's own SoftAP, fixed SSID `lokalgrid`.")

        SectionLabel("presets")
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { LgButton("The node", primary = true) { onUrl(Prefs.NODE_URL) } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { LgButton("Emulator") { onUrl(Prefs.DEFAULT_URL) } }
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { LgButton("LAN :8787") { onUrl("ws://192.168.1.") } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { }
        }
        Note("You can change this later in Config → setup.")
    }
}

// ---------------------------------------------------------------- helpers ----

@Composable
private fun Body(text: String) {
    Text(
        text,
        color = Lg.Ink2, fontFamily = Mono, fontSize = 11.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = Lg.Ink3, fontFamily = Mono, fontSize = 9.sp, lineHeight = 14.sp)
}

/** Run [block] whenever the screen comes back to the foreground. */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
