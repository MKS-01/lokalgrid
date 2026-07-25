package dev.lokalgrid.app.loc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Where *this phone* thinks it is.
 *
 * Until now "share my position" offered the node's own fix — honest for a phone
 * sitting next to the node, and it exercised the whole forward path, but it is
 * not the real thing. This is the real thing.
 *
 * Deliberately `LocationManager` and not Play Services' fused provider: no
 * Google dependency, no Play-services requirement on the device, and the raw
 * providers are what the rest of the project already reasons about (a GNSS fix
 * with an accuracy figure, or nothing). Fused would smooth exactly the
 * uncertainty §6 says must be rendered.
 */
class PhoneLocation(context: Context) {

    private val app = context.applicationContext

    /**
     * One fix from the phone's own GNSS, carrying its uncertainty and its age —
     * same rule as every other position in this app (§6). `accuracyM` is
     * Android's 68 % radius; `-1` means the provider did not state one, which is
     * unknown rather than perfect.
     */
    data class Fix(
        val latDeg: Double,
        val lonDeg: Double,
        val accuracyM: Double,
        val epochS: Long,
        val provider: String,
        /** `elapsedRealtime` at the moment the provider produced it — immune to
         *  wall-clock changes, which is what makes the age trustworthy. */
        val elapsedMs: Long,
    ) {
        val latE7: Int get() = Math.round(latDeg * 1e7).toInt()
        val lonE7: Int get() = Math.round(lonDeg * 1e7).toInt()

        fun ageS(now: Long = SystemClock.elapsedRealtime()): Long =
            ((now - elapsedMs) / 1000).coerceAtLeast(0)
    }

    /**
     * What the phone's GPS is doing, as a state that names itself — never a
     * spinner, never a silent absence (§6).
     */
    sealed interface State {
        /** Not asked yet: the app has no location permission. */
        data object NotGranted : State

        /** Permission granted, but location is switched off system-wide. */
        data object ProvidersOff : State

        /** Listening, nothing yet. A cold GNSS start is a minute outdoors, longer in. */
        data object Waiting : State

        data class Live(val fix: Fix) : State
    }

    fun granted(): Boolean = granted(app)

    /** True only when the *precise* provider is available — worth saying, because
     *  coarse-only changes what the accuracy ring means. */
    fun fineGranted(): Boolean = fineGranted(app)

    /**
     * Fixes for as long as this flow is collected, and nothing when it is not —
     * the GNSS is the second most expensive thing on a phone, so it runs while a
     * screen that shows position is up, not forever. Background sharing belongs
     * to the foreground service, which lands with BLE.
     */
    @SuppressLint("MissingPermission") // checked immediately below, every time
    fun updates(intervalMs: Long = 2_000L): Flow<State> = callbackFlow {
        if (!granted()) {
            trySend(State.NotGranted)
            awaitClose { }
            return@callbackFlow
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm?.isProviderEnabled(it) == true }.getOrDefault(false) }
        if (lm == null || providers.isEmpty()) {
            trySend(State.ProvidersOff)
            awaitClose { }
            return@callbackFlow
        }

        trySend(State.Waiting)

        // Written out rather than as a lambda on purpose: `LocationListener` only
        // gained default methods at API 30, so on an older device the platform
        // calls a method a SAM conversion never implemented.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(State.Live(location.toFix()))
            }

            override fun onProviderDisabled(provider: String) {
                if (providers.none { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }) {
                    trySend(State.ProvidersOff)
                }
            }

            override fun onProviderEnabled(provider: String) {}

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper()) }
        }

        // The last known fix, offered immediately — with its real age, so an hour-old
        // one reads as an hour old rather than as where you are standing.
        providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { trySend(State.Live(it.toFix())) }

        awaitClose { runCatching { lm.removeUpdates(listener) } }
    }

    companion object {
        /**
         * Location is asked for at the moment it is used — the tap on "share my
         * position" — not at first run, because that is when the user can see what
         * it is for. `COARSE` is listed because Android lets the user grant only
         * that, and a ~1 km fix drawn with a ~1 km ring is still a true statement
         * about where someone is (§6).
         */
        val PERMISSIONS: List<String> = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun granted(context: Context): Boolean = PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun fineGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun Location.toFix() = Fix(
        latDeg = latitude,
        lonDeg = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else -1.0,
        epochS = time / 1000,
        provider = provider ?: "unknown",
        elapsedMs = elapsedRealtimeNanos / 1_000_000,
    )
}
