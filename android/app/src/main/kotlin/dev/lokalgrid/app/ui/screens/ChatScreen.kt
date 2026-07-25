package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.ChatEntry
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.ui.CardSub
import dev.lokalgrid.app.ui.CardTitle
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.LgCard
import dev.lokalgrid.app.ui.LgTextField
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg
import dev.lokalgrid.protocol.Lane
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Mono = FontFamily.Monospace
private val HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * One shared channel, text only (§2) — and the first user of the forward flow.
 *
 * Two different truths are shown per message and never conflated:
 *   • **delivered** — the node accepted it and echoed a seq, so everyone on this
 *     node has it. Immediate: they share a WiFi/BLE link, no radio involved.
 *   • **relay** — the same message going out over LoRa, which is duty-cycled and
 *     shared. That is where "queued 56 s, bravo ahead of you" comes from: a
 *     reason with a name in it, never a spinner (§6).
 */
@Composable
fun ChatScreen(state: LiveState, onSend: (String, Boolean) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size)
    }

    val submit: (Boolean) -> Unit = { emergency ->
        if (draft.isNotBlank()) {
            onSend(draft, emergency)
            draft = ""
        }
    }

    Column(Modifier.fillMaxSize().background(Lg.Paper).imePadding()) {
        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    SectionLabel("one shared channel")
                    CardSub(
                        if (state.connected) "no messages yet — say something and everyone on the node sees it"
                        else "not connected to a node · ${state.status}"
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                ) {
                    item { SectionLabel("one shared channel · ${state.messages.size} messages") }
                    items(state.messages, key = { it.key }) { MessageCard(it) }
                }
            }
        }

        val queued = state.outbox.filter { it.relayReason != null && !it.relayed }
        if (queued.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                SectionLabel("link out · ${queued.size} waiting on airtime")
                queued.take(3).forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            m.text.take(22),
                            color = Lg.Ink2, fontFamily = Mono, fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Pill(
                            m.relayReason.orEmpty(),
                            if (m.lane == Lane.EMERGENCY) PillKind.WARN else PillKind.LORA,
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // A disabled Send with no explanation is the spinner problem wearing a
            // different hat: it shows *that* you can't send, never *why*. Name the
            // failure and the next action (§6).
            if (!state.connected) {
                Text(
                    "no link to the node · ${state.url}",
                    color = Lg.Warn, fontFamily = Mono, fontSize = 10.sp,
                )
                Text(
                    "start the mock (cd mock-node && npm start). On a real phone, " +
                        "10.0.2.2 is emulator-only — use your dev machine's LAN IP.",
                    color = Lg.Ink3, fontFamily = Mono, fontSize = 9.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LgTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "message the group as ${state.selfName}…",
                modifier = Modifier.fillMaxWidth(),
                onSubmit = { submit(false) },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LgButton("Send", primary = true, enabled = state.connected && draft.isNotBlank()) {
                        submit(false)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    LgButton("Emergency · lane 0", enabled = state.connected && draft.isNotBlank()) {
                        submit(true)
                    }
                }
            }
            state.nodeNotice?.let {
                Text(it, color = Lg.Warn, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun MessageCard(m: ChatEntry) {
    LgCard(selected = m.mine) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CardTitle("${if (m.mine) "you" else m.name} · ${HHMMSS.format(Instant.ofEpochSecond(m.epoch))}")
            when {
                m.error != null -> Pill("refused", PillKind.WARN)
                m.lane == Lane.EMERGENCY -> Pill("lane 0", PillKind.WARN)
                !m.delivered -> Pill("pending", PillKind.NEUTRAL)
                m.relayed -> Pill("relayed", PillKind.LORA)
                else -> Pill("seq ${m.seq}", PillKind.OK)
            }
        }
        Text(m.text, color = Lg.Ink, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        // The honest part: what is true about this message right now, and why.
        when {
            m.error != null -> CardSub("✕ ${m.error}")
            !m.delivered -> CardSub("waiting for the node to acknowledge — no seq yet")
            m.mine && m.relayReason != null -> CardSub("link out: ${m.relayReason}")
            m.mine -> CardSub("delivered to everyone on the node")
            else -> Unit
        }
    }
}
