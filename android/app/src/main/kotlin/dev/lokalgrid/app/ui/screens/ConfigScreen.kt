package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.onboarding.Setup
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgTextField
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

/**
 * Config is **staged locally and written explicitly** (§6) — the app never
 * reconfigures the node as you type. Edits collect in `staged`; "Write to node"
 * sends them as one patch; the node decides and answers per key.
 *
 * Some rows are deliberately not editable. Duty cycle and the AP idle timeout
 * are enforced in firmware, not offered as settings (§2) — the node ships the
 * reason with the lock, and the UI shows the reason rather than a greyed box
 * with no explanation.
 */
@Composable
fun ConfigScreen(
    state: LiveState,
    onWrite: (Map<String, String>) -> Unit = {},
    onReopenSetup: () -> Unit = {},
) {
    val cfg = state.config
    val context = LocalContext.current
    val staged = remember { mutableStateMapOf<String, String>() }
    var editing by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(Lg.Paper)
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
    ) {
        // This phone's own setup, above the node's config — the two are different
        // machines and the UI should never blur which one a setting lives on.
        SectionLabel("this phone · setup")
        InfoRow("node url", state.url)
        val blePerms = Setup.blePermissions + Setup.notificationPermission
        for (p in blePerms) {
            InfoRow(Setup.label(p)) {
                val ok = Setup.granted(context, p)
                Pill(if (ok) "granted" else "not granted", if (ok) PillKind.OK else PillKind.WARN)
            }
        }
        InfoRow("battery") {
            val ok = Setup.batteryExempt(context)
            Pill(if (ok) "exempt" else "restricted — sync will die", if (ok) PillKind.OK else PillKind.WARN)
        }
        InfoRow("ble link") { Pill("phase 03 · needs the board", PillKind.NEUTRAL) }
        LgButton("Open setup again") { onReopenSetup() }
        LgButton("Android app settings") { Setup.openAppSettings(context) }

        if (cfg == null) {
            SectionLabel("node config")
            InfoRow("waiting", "no config frame from the node yet")
            InfoRow("status", state.status)
            return@Column
        }

        SectionLabel("node config · editable")
        for (setting in cfg.editable) {
            val current = cfg.values[setting.key].orEmpty()
            val pending = staged[setting.key]
            InfoRow(setting.key) {
                Pill(
                    if (pending != null) "$current → $pending" else current,
                    if (pending != null) PillKind.NEUTRAL else PillKind.OK,
                )
            }
            Text(
                setting.note + (setting.min?.let { " · ${it}–${setting.max}" } ?: ""),
                color = Lg.Ink3, fontFamily = Mono, fontSize = 9.sp,
            )
            if (editing == setting.key) {
                LgTextField(
                    value = pending ?: current,
                    onValueChange = { staged[setting.key] = it },
                    placeholder = current,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onSubmit = { editing = null },
                )
            }
            LgButton(
                if (editing == setting.key) "Done editing ${setting.key}" else "Edit ${setting.key}",
                enabled = state.connected,
            ) {
                editing = if (editing == setting.key) null else setting.key
            }
        }

        // Not settings. Shown with the reason, so nobody goes looking for the toggle.
        SectionLabel("enforced in firmware · not settings")
        for ((key, why) in cfg.locked) {
            InfoRow(key, cfg.values[key].orEmpty())
            Text(why, color = Lg.Ink3, fontFamily = Mono, fontSize = 9.sp)
        }

        SectionLabel("write")
        if (staged.isEmpty()) {
            InfoRow("staged", "nothing changed")
        } else {
            for ((k, v) in staged) InfoRow(k, "→ $v")
        }
        LgButton(
            "Write ${staged.size} change${if (staged.size == 1) "" else "s"} to node",
            primary = true,
            enabled = state.connected && staged.isNotEmpty(),
        ) {
            onWrite(staged.toMap())
            staged.clear()
            editing = null
        }
        if (staged.isNotEmpty()) {
            LgButton("Discard staged changes") { staged.clear(); editing = null }
        }

        // What the node did with the last write — applied and refused, both named.
        state.lastConfigResult?.let { res ->
            SectionLabel("last write")
            for ((k, v) in res.applied) InfoRow(k) { Pill("applied · $v", PillKind.OK) }
            for (r in res.refused) {
                InfoRow(r.key) { Pill("refused", PillKind.WARN) }
                Text(r.reason, color = Lg.Warn, fontFamily = Mono, fontSize = 9.sp)
            }
            if (res.applied.isEmpty() && res.refused.isEmpty()) InfoRow("result", "nothing to do")
        }
    }
}
