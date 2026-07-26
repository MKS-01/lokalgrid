package dev.lokalgrid.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Which WiFi network to send the node's traffic over.
 *
 * This exists because of a documented Android behaviour that will otherwise look
 * exactly like a broken node (§3, §8): the SoftAP has **no internet route**, so
 * Android marks it unvalidated and quietly routes new sockets over mobile data
 * instead. The WebSocket to `192.168.4.1` then goes nowhere while the phone
 * cheerfully shows itself connected to `lokalgrid`.
 *
 * The fix is to pin the socket to the WiFi `Network` object. Deliberately
 * **per-socket** (`network.socketFactory`) rather than
 * `bindProcessToNetwork`, which would drag every other request in the process
 * onto an AP with no internet — including, later, anything the app wants to do
 * while it is *not* talking to the node.
 */
class WifiBinding(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var current: Network? = null

    @Volatile
    private var lastSeen: String = "no wifi network yet"

    /**
     * Called when the WiFi underneath changes — joined, lost, or revalidated.
     * The socket has to be rebuilt on that edge: joining the node's AP *after*
     * the app started is the normal case, and without this the app sat on a dead
     * socket until it was restarted by hand.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = current != network
            current = network
            lastSeen = "wifi available"
            if (changed) onChanged?.invoke()
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                lastSeen = "wifi lost"
                onChanged?.invoke()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != current) return
            // Worth recording rather than hiding: an AP with no internet is the
            // *expected* state for this node, and it is also the exact condition
            // that makes Android reroute traffic. Naming it keeps a working
            // setup from reading as a fault.
            val next = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                "wifi with internet"
            } else {
                "wifi, no internet route — sockets are pinned to it"
            }
            if (next != lastSeen) {
                lastSeen = next
                onChanged?.invoke()
            }
        }
    }

    init {
        // A callback rather than the deprecated `allNetworks` sweep: this is the
        // API that keeps working, and it tracks the answer instead of guessing it
        // at the moment a socket happens to open.
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
            )
        }
    }

    /** The WiFi network to pin to, or null when the phone is not on WiFi at all. */
    fun network(): Network? = current ?: fallback()

    /** What the binding is doing, in words the Link screen can show. */
    fun describe(): String = if (network() == null) "not bound — no wifi" else lastSeen

    /**
     * If the callback has not fired yet — first launch, or the socket opening in
     * the same instant the app starts — ask directly rather than failing to bind
     * on the one connection attempt that matters most.
     */
    @Suppress("DEPRECATION")
    private fun fallback(): Network? {
        val manager = cm ?: return null
        return runCatching {
            manager.allNetworks.firstOrNull { n ->
                manager.getNetworkCapabilities(n)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()
    }
}
