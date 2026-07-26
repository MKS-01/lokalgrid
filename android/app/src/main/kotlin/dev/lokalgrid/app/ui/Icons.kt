package dev.lokalgrid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lokalgrid.app.ui.theme.Lg

/**
 * The app's icons, drawn as vectors in code.
 *
 * Why not Material icons: this is a field instrument, and the stock set is a
 * consumer visual language — rounded, filled, friendly. These are thin-stroke
 * geometric marks that match the monospace type and the wireframes. Drawing them
 * here also means one definition per idea, so an error looks the same on every
 * screen instead of each screen inventing its own dash of red text.
 *
 * Each icon is stroke-only at 1.5 dp on a 24-unit grid, tinted by the caller.
 */
enum class LgIcon {
    /** Something failed and the user has to act. */
    Alert,

    /** Nothing here yet, and that is not a failure. */
    Empty,

    /** Working, with a stated reason elsewhere — never used alone as a spinner. */
    Waiting,

    /** Done, present, connected. */
    Ok,

    /** The radio link out. */
    Radio,

    /** A position with its uncertainty — the app's own mark. */
    Fix,

    /** The map, for offline downloads. */
    Map,

    /** Bluetooth. */
    Bluetooth,
}

@Composable
fun LgIconMark(
    icon: LgIcon,
    tint: Color = Lg.Ink2,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            val u = s / 24f                    // one grid unit
            val stroke = Stroke(width = 1.5f * u)
            fun p(x: Float, y: Float) = Offset(x * u, y * u)

            when (icon) {
                LgIcon.Alert -> {
                    // A triangle with a bar: the shape reads as "attention" without
                    // the exclamation-in-a-circle look of a consumer error dialog.
                    val path = Path().apply {
                        moveTo(12 * u, 3 * u)
                        lineTo(22 * u, 20 * u)
                        lineTo(2 * u, 20 * u)
                        close()
                    }
                    drawPath(path, tint, style = stroke)
                    drawLine(tint, p(12f, 9f), p(12f, 14f), strokeWidth = 1.5f * u)
                    drawLine(tint, p(12f, 16.5f), p(12f, 17.5f), strokeWidth = 2f * u)
                }

                LgIcon.Empty -> {
                    // A dashed square: a place where something would be.
                    drawRoundRect(
                        color = tint,
                        topLeft = p(3f, 3f),
                        size = Size(18 * u, 18 * u),
                        style = Stroke(
                            width = 1.5f * u,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * u, 3f * u)),
                        ),
                    )
                }

                LgIcon.Waiting -> {
                    // A clock face, hands at a fixed angle. Static on purpose: an
                    // animation would be the spinner this project refuses (§6).
                    drawCircle(tint, radius = 9 * u, center = p(12f, 12f), style = stroke)
                    drawLine(tint, p(12f, 12f), p(12f, 7f), strokeWidth = 1.5f * u)
                    drawLine(tint, p(12f, 12f), p(16f, 13f), strokeWidth = 1.5f * u)
                }

                LgIcon.Ok -> {
                    drawCircle(tint, radius = 9 * u, center = p(12f, 12f), style = stroke)
                    val tick = Path().apply {
                        moveTo(7.5f * u, 12.5f * u)
                        lineTo(11f * u, 16f * u)
                        lineTo(16.5f * u, 8.5f * u)
                    }
                    drawPath(tick, tint, style = stroke)
                }

                LgIcon.Radio -> {
                    // A mast with two arcs — the link out, as on the Chat tab.
                    drawLine(tint, p(12f, 20f), p(12f, 10f), strokeWidth = 1.5f * u)
                    drawCircle(tint, radius = 2f * u, center = p(12f, 8f), style = stroke)
                    for (r in listOf(6f, 10f)) {
                        drawArc(
                            color = tint,
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            topLeft = p(12f - r, 8f - r),
                            size = Size(2 * r * u, 2 * r * u),
                            style = stroke,
                        )
                    }
                }

                LgIcon.Fix -> {
                    // The product's own mark: a point inside its uncertainty.
                    drawCircle(tint, radius = 2.5f * u, center = p(12f, 12f))
                    drawCircle(
                        tint, radius = 9 * u, center = p(12f, 12f),
                        style = Stroke(
                            width = 1.5f * u,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * u, 2.5f * u)),
                        ),
                    )
                }

                LgIcon.Map -> {
                    val path = Path().apply {
                        moveTo(3 * u, 6 * u); lineTo(9 * u, 4 * u)
                        lineTo(15 * u, 7 * u); lineTo(21 * u, 5 * u)
                        lineTo(21 * u, 18 * u); lineTo(15 * u, 20 * u)
                        lineTo(9 * u, 17 * u); lineTo(3 * u, 19 * u)
                        close()
                    }
                    drawPath(path, tint, style = stroke)
                    drawLine(tint, p(9f, 4f), p(9f, 17f), strokeWidth = 1f * u)
                    drawLine(tint, p(15f, 7f), p(15f, 20f), strokeWidth = 1f * u)
                }

                LgIcon.Bluetooth -> {
                    val path = Path().apply {
                        moveTo(12 * u, 3 * u); lineTo(12 * u, 21 * u)
                        moveTo(12 * u, 7 * u); lineTo(17 * u, 11 * u)
                        lineTo(7 * u, 17 * u)
                        moveTo(12 * u, 17 * u); lineTo(17 * u, 13 * u)
                        lineTo(7 * u, 7 * u)
                    }
                    drawPath(path, tint, style = stroke)
                }
            }
        }
    }
}
