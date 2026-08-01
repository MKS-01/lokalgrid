package dev.lokalgrid.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The architecture doc's palette (docs/architecture.html :root), ported verbatim so
 * the app and the design doc are the same dark, mono, field-instrument look.
 * Colours carry meaning: lock = present/ok, sig = neutral/queued, lora = the
 * radio link, warn = failure. Keep these in sync with the HTML if it changes.
 */
object Lg {
    val Paper = Color(0xFF101614)
    val Deep = Color(0xFF0A0F0E)
    val Card = Color(0xFF171F1C)
    val Ink = Color(0xFFE4EBE8)
    val Ink2 = Color(0xFFA9B8B2)
    val Ink3 = Color(0xFF7C8B85)
    val Rule = Color(0xFF33403C)
    val Rule2 = Color(0xFF222C29)

    val Lock = Color(0xFF4EC2A6); val LockBg = Color(0xFF123129)
    val Sig = Color(0xFFE39A4E); val SigBg = Color(0xFF372511)
    val Lora = Color(0xFFA99BDB); val LoraBg = Color(0xFF262038)
    val Warn = Color(0xFFE4776A); val WarnBg = Color(0xFF3B1B15)
}
