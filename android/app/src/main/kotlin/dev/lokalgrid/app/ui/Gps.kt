package dev.lokalgrid.app.ui

import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.loc.PhoneLocation

/**
 * How the phone's own GPS is rendered, in one place, so the Live tab, the Link
 * screen and Diagnostics cannot drift into three different accounts of the same
 * fact.
 *
 * Every branch names a state. There is no "…" and no spinner: a cold GNSS start
 * genuinely takes a minute, and saying so is the difference between waiting and
 * wondering whether the app is broken (§6).
 */
fun gpsLabel(state: LiveState): String = when (val g = state.gps) {
    is PhoneLocation.State.NotGranted -> "not granted — tap share to allow"
    is PhoneLocation.State.ProvidersOff -> "location is off on this phone"
    is PhoneLocation.State.Waiting -> "waiting for the first fix"
    is PhoneLocation.State.Live -> {
        val age = g.fix.ageS()
        val acc = if (g.fix.accuracyM > 0) "±%.0f m".format(g.fix.accuracyM) else "accuracy unknown"
        "$acc · ${if (age < 5) "now" else "${age} s ago"} · ${g.fix.provider}"
    }
}

/** OK only while the fix is both present and fresh — age is what makes a position
 *  a claim about now rather than about earlier. */
fun gpsKind(state: LiveState): PillKind {
    val fix = (state.gps as? PhoneLocation.State.Live)?.fix ?: return PillKind.NEUTRAL
    return when {
        fix.ageS() < 90 -> PillKind.OK
        fix.ageS() < 900 -> PillKind.NEUTRAL
        else -> PillKind.WARN
    }
}

/**
 * Your own dot: the phone's fix when there is a usable one, otherwise nothing.
 * Deliberately *not* the node's fix — the node's position is already drawn as the
 * node's, and re-labelling it "you" would put a dot where you are not.
 */
fun myPeer(state: LiveState): Peer? {
    val fix = state.myFix ?: return null
    return Peer(
        id = -1,
        name = "you (${state.selfName})",
        latDeg = fix.latDeg,
        lonDeg = fix.lonDeg,
        // No stated accuracy means unknown, so draw it wide rather than tight.
        accuracyM = if (fix.accuracyM > 0) fix.accuracyM else 50.0,
        ageSec = fix.ageS(),
        // Ink, not Lock: the node's own dot owns the green, and two green dots on
        // one map would read as one thing in two places.
        colorHex = "#E4EBE8",
        kind = PillKind.OK,
    )
}
