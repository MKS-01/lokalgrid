package dev.lokalgrid.app.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * What the app needs from the phone before it can do its job, and how to ask.
 *
 * Two different kinds of "permission" live here and they behave nothing alike:
 *
 *  1. **Runtime permissions** — Bluetooth, notifications. A dialog, a yes/no, a
 *     stable answer you can query.
 *  2. **Battery exemptions** — One UI's "Deep sleeping apps" and Adaptive
 *     Battery. These have no permission to grant: the OS quietly stops your
 *     background work and never tells the app. §3 names this as a thing to
 *     design around; §6 calls the onboarding screen "not optional" because of it.
 *     All the app can do is take the user there and then *detect* the symptom.
 *
 * BLE itself is Phase 03 — it cannot be mocked (§6). These permissions are
 * requested now so the flow exists and is testable; nothing here opens a GATT
 * connection, and the UI does not pretend otherwise.
 */
object Setup {

    /** BLE permissions, which changed shape at Android 12. */
    val blePermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // `neverForLocation` in the manifest means we can skip location
            // entirely (§5) — honest, because position arrives over GATT after
            // connecting, never from a scan result.
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Pre-12 uses the legacy BLUETOOTH/BLUETOOTH_ADMIN pair, which are
        // install-time and need no dialog.
    }

    /** The foreground service that will carry background sync posts a notification. */
    val notificationPermission: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: List<String>): Boolean =
        permissions.all { granted(context, it) }

    /** Short label for a permission, for a UI that names things plainly. */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "find the node"
        Manifest.permission.BLUETOOTH_CONNECT -> "connect over BLE"
        Manifest.permission.POST_NOTIFICATIONS -> "show sync notifications"
        else -> permission.substringAfterLast('.').lowercase()
    }

    fun why(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN ->
            "declared neverForLocation — the app never derives your position from a scan"
        Manifest.permission.BLUETOOTH_CONNECT ->
            "the always-on link to the node; positions and chat arrive over it"
        Manifest.permission.POST_NOTIFICATIONS ->
            "the background sync service must show one, or Android kills it"
        else -> ""
    }

    /** True when Android is *not* holding this app in a battery-saving bucket. */
    fun batteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The battery-optimisation list. Deliberately the *settings screen*, not the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog: the dialog needs a permission
     * flagged as sensitive, and this is a personal build that does not need it.
     */
    fun openBatterySettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            // One UI puts the real switch under the app's own settings page.
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
        for (i in intents) {
            if (i.resolveActivity(context.packageManager) != null) {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
    }

    /** This app's own settings page — where a twice-denied permission is fixed. */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Is this the device the app was actually designed around? (§3) */
    val isOneUi: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    val deviceLabel: String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
}
