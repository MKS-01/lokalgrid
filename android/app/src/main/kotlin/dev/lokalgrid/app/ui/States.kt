package dev.lokalgrid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.ui.theme.Lg

private val Mono = FontFamily.Monospace

/**
 * The four things a screen can be in, as one component each.
 *
 * Before this, every screen wrote its own version of "nothing here" and its own
 * dash of red text for a failure, so the same situation looked different
 * depending on which tab you were on. These enforce the §6 rules in one place:
 *
 *  - a failure **names the failure and offers the next action**
 *  - a wait **states its reason** — never a bare spinner
 *  - an empty state says why it is empty and what would fill it
 *  - none of them lie about what the app knows
 */

/** Something failed. `action` is what the user can do about it, and it appears
 *  only when there genuinely is something to do. */
@Composable
fun ErrorState(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    StateBlock(LgIcon.Alert, Lg.Warn, Lg.WarnBg, title, detail) {
        if (actionLabel != null && onAction != null) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { LgButton(actionLabel, primary = true, onClick = onAction) }
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) { LgButton(secondaryLabel, onClick = onSecondary) }
                }
            }
        }
    }
}

/** Waiting on something real, with the reason stated. */
@Composable
fun WaitingState(title: String, reason: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    StateBlock(LgIcon.Waiting, Lg.Sig, Lg.SigBg, title, reason) {
        if (actionLabel != null && onAction != null) LgButton(actionLabel, onClick = onAction)
    }
}

/** Nothing here, and why — plus what would put something here. */
@Composable
fun EmptyState(title: String, reason: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    StateBlock(LgIcon.Empty, Lg.Ink3, Color.Transparent, title, reason) {
        if (actionLabel != null && onAction != null) LgButton(actionLabel, onClick = onAction)
    }
}

/** A thing that worked, when saying so matters (a finished download, a granted
 *  permission). Deliberately quiet — success is the boring case. */
@Composable
fun DoneState(title: String, detail: String) {
    StateBlock(LgIcon.Ok, Lg.Lock, Lg.LockBg, title, detail) {}
}

@Composable
private fun StateBlock(
    icon: LgIcon,
    tint: Color,
    bg: Color,
    title: String,
    detail: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, if (bg == Color.Transparent) Lg.Rule else tint, RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LgIconMark(icon, tint = tint)
            Spacer(Modifier.width(9.dp))
            Text(title, color = tint, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            detail,
            color = Lg.Ink2,
            fontFamily = Mono,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        content()
    }
}

/**
 * A label/value row with an icon in front — for the places where a state needs to
 * be scannable in a list rather than explained in a block.
 */
@Composable
fun IconRow(icon: LgIcon, tint: Color, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LgIconMark(icon, tint = tint, size = 14.dp)
            Spacer(Modifier.width(7.dp))
            Text(label, color = Lg.Ink2, fontFamily = Mono, fontSize = 11.sp)
        }
        Text(value, color = Lg.Ink, fontFamily = Mono, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().padding(top = 1.dp).background(Lg.Rule2))
}
