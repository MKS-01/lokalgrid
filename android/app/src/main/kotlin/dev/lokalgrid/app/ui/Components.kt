package dev.lokalgrid.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lokalgrid.app.ui.theme.Lg

// The design vocabulary from the wireframes (§07): sysbar, appbar, lbl, row,
// pill, card, big, bar, btn. Monospace everywhere — this is an instrument, not
// a consumer app.

private val Mono = FontFamily.Monospace

enum class PillKind(val fg: Color, val bg: Color) {
    OK(Lg.Lock, Lg.LockBg),
    NEUTRAL(Lg.Sig, Lg.SigBg),
    LORA(Lg.Lora, Lg.LoraBg),
    WARN(Lg.Warn, Lg.WarnBg),
}

@Composable
fun Pill(text: String, kind: PillKind = PillKind.NEUTRAL) {
    Text(
        text,
        color = kind.fg,
        fontFamily = Mono,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(kind.bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Small dim uppercase section header (.lbl). */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Lg.Ink3,
        fontFamily = Mono,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

/** A label/value line (.row). Value may be plain text or a trailing composable. */
@Composable
fun InfoRow(label: String, value: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Lg.Ink2, fontFamily = Mono, fontSize = 11.sp)
        when {
            trailing != null -> trailing()
            value != null -> Text(value, color = Lg.Ink, fontFamily = Mono, fontSize = 11.sp)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Lg.Rule2))
}

@Composable
fun BigValue(text: String) {
    Text(
        text,
        color = Lg.Ink,
        fontFamily = Mono,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 26.sp,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
fun LgCard(selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Lg.LockBg else Color.Transparent)
            .border(1.dp, if (selected) Lg.Lock else Lg.Rule, RoundedCornerShape(3.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        content = content
    )
}

@Composable
fun CardTitle(text: String) =
    Text(text, color = Lg.Ink, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun CardSub(text: String) =
    Text(text, color = Lg.Ink3, fontFamily = Mono, fontSize = 10.sp)

/** Airtime/usage bar (.bar). */
@Composable
fun MeterBar(fraction: Float, color: Color = Lg.Sig) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(6.dp)
            .background(Lg.Rule2)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color)
        )
    }
}

/** Single-line monospace input (.inp). Styled by hand — Material's TextField
 *  chrome fights the instrument look. */
@Composable
fun LgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, Lg.Rule), RoundedCornerShape(3.dp))
            .padding(horizontal = 9.dp, vertical = 9.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Lg.Ink3, fontFamily = Mono, fontSize = 12.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Lg.Ink, fontFamily = Mono, fontSize = 12.sp),
            cursorBrush = SolidColor(Lg.Lock),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun LgButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val border = if (!enabled) Lg.Rule2 else if (primary) Lg.Lock else Lg.Rule
    val fg = if (!enabled) Lg.Ink3 else if (primary) Lg.Lock else Lg.Ink2
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(3.dp))
            .background(if (primary && enabled) Lg.LockBg else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
