package dev.lokalgrid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

/**
 * The splash. Not a timed logo — it is up while the app does one real thing:
 * open the socket to the node. It names the node and reports what that attempt
 * is doing, and it gives up waiting after [BOOT_GRACE_MS] so a dead node delays
 * you by a second rather than trapping you behind a brand screen.
 *
 * A splash that waits on a timer is a spinner with better art (§6). This one
 * waits on the connection and then says how it went.
 */
@Composable
fun BootScreen(url: String, status: String = "opening the link…", failed: Boolean = false) {
    Column(
        Modifier.fillMaxSize().background(Lg.Paper).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("lokalgrid", color = Lg.Ink, fontFamily = Mono, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "one node · everyone on one map",
            color = Lg.Lock, fontFamily = Mono, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            url,
            color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            status,
            color = if (failed) Lg.Warn else Lg.Ink2,
            fontFamily = Mono, fontSize = 10.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** How long the boot screen waits on the first connection before going in anyway. */
const val BOOT_GRACE_MS = 1200L
