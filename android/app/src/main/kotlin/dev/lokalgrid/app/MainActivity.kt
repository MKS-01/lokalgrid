package dev.lokalgrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lokalgrid.app.loc.PhoneLocation
import dev.lokalgrid.app.net.BleClient
import dev.lokalgrid.app.net.WifiBinding
import dev.lokalgrid.app.onboarding.OnboardingScreen
import dev.lokalgrid.app.ui.AppShell
import dev.lokalgrid.app.ui.BOOT_GRACE_MS
import dev.lokalgrid.app.ui.BootScreen
import dev.lokalgrid.app.ui.theme.Lg
import org.maplibre.android.MapLibre
import kotlinx.coroutines.delay

/** Where the app is in its own startup, as a state rather than a guess. */
private enum class Route { BOOT, ONBOARDING, RUNNING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch theme paints the app's own dark paper before the first frame;
        // from here on Compose owns the window.
        setTheme(R.style.Theme_Lokalgrid)
        enableEdgeToEdge()

        // MapLibre has to be initialised before *anything* touches its file
        // source — the offline manager does, and it is built by the Map screen
        // before the map view itself gets a chance to call this. Doing it here,
        // once, is the only place that is unambiguously early enough.
        MapLibre.getInstance(applicationContext)

        val prefs = Prefs(this)
        // One source per process, held by the application context: the fixes belong
        // to the phone, not to whichever node the ViewModel is currently keyed on.
        val gps = PhoneLocation(this)
        // One binding per process: it watches for the WiFi network the node's
        // SoftAP provides, so each socket can be pinned to it.
        val binding = WifiBinding(this)
        // The BLE transport. One per process, like the others — a GATT client that
        // gets rebuilt per screen would leak connections.
        val ble = BleClient(this)

        setContent {
            // Fixed dark scheme from the master-plan palette — not dynamic colour;
            // this app has a deliberate field-instrument identity.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Lg.Lock,
                    background = Lg.Paper,
                    surface = Lg.Card,
                    onBackground = Lg.Ink,
                    onSurface = Lg.Ink,
                )
            ) {
                Box(Modifier.fillMaxSize().background(Lg.Paper)) {
                    var route by remember { mutableStateOf(Route.BOOT) }
                    var url by remember { mutableStateOf(prefs.nodeUrl) }
                    // Changing transport rebuilds the session, exactly like changing
                    // node: a new wire is a new connection, never a relabelled one.
                    var transport by remember { mutableStateOf(prefs.transport) }
                    var bleAddress by remember { mutableStateOf(prefs.bleAddress) }

                    LaunchedEffect(Unit) {
                        if (!prefs.onboarded) route = Route.ONBOARDING
                    }

                    if (route == Route.ONBOARDING) {
                        OnboardingScreen(prefs) { chosen ->
                            url = chosen
                            route = Route.BOOT
                        }
                    } else {
                        // One ViewModel for the process, re-aimed when the target
                        // changes. It cannot be *rebuilt* per target: `key()` keys
                        // the composition, while `viewModel()` looks its instance up
                        // in the Activity's store by class name and returns the
                        // cached one — factory and new arguments ignored. Wrapping
                        // this in `key(url, transport, bleAddress)` therefore did
                        // nothing at all, and picking a node over BLE only took
                        // effect after the app was force-stopped.
                        val vm: LiveViewModel = viewModel(
                            factory = LiveViewModelFactory(
                                url, prefs, gps, binding, ble, transport, bleAddress,
                            )
                        )
                        val state by vm.state.collectAsStateWithLifecycle()

                        // The change of target, applied where it can actually reach
                        // the running session. No-op on first composition, since the
                        // ViewModel was just built with exactly these values.
                        LaunchedEffect(url, transport, bleAddress) {
                            vm.retarget(url, transport, bleAddress)
                        }

                        // Hold the boot screen until the first connection attempt
                        // resolves, or the grace period runs out. A node that is
                        // down costs you a second, never the app.
                        LaunchedEffect(url, state.connected) {
                            if (state.connected) {
                                route = Route.RUNNING
                            } else if (route == Route.BOOT) {
                                delay(BOOT_GRACE_MS)
                                route = Route.RUNNING
                            }
                        }

                        if (route == Route.BOOT) {
                            val failed = !state.connected && state.status.startsWith("error")
                            BootScreen(
                                url = url,
                                status = if (failed) state.status else "opening the link…",
                                failed = failed,
                            )
                        } else {
                            AppShell(
                                state = state,
                                onSendChat = vm::sendChat,
                                onSharePosition = vm::shareMyPosition,
                                onRename = vm::setName,
                                onResetTrack = vm::resetTrack,
                                onWriteConfig = vm::writeConfig,
                                onReopenSetup = {
                                    prefs.onboarded = false
                                    route = Route.ONBOARDING
                                },
                                onReconnect = vm::reconnect,
                                // Location can be granted or revoked outside the
                                // app, so the GPS flow is rebuilt on demand rather
                                // than trusted from startup.
                                onLocationChanged = vm::watchGps,
                                ble = ble,
                                onUseBle = { address ->
                                    prefs.bleAddress = address
                                    prefs.transport = Prefs.TRANSPORT_BLE
                                    bleAddress = address
                                    transport = Prefs.TRANSPORT_BLE
                                },
                                onUseWifi = {
                                    prefs.transport = Prefs.TRANSPORT_WIFI
                                    transport = Prefs.TRANSPORT_WIFI
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hands the chosen node URL to the ViewModel, along with the means to read and
 * write the position cursor *per node* — so reopening the app resumes a delta
 * rather than re-streaming everything, and switching nodes cannot carry a cursor
 * across. The cursor is looked up rather than passed because the ViewModel
 * outlives any one target (see `LiveViewModel.retarget`).
 *
 * Used only when the ViewModel is first created; every later change of target
 * goes through `retarget`, because `ViewModelProvider` never calls a factory for
 * an instance it already holds.
 */
private class LiveViewModelFactory(
    private val url: String,
    private val prefs: Prefs,
    private val gps: PhoneLocation,
    private val binding: WifiBinding,
    private val ble: BleClient,
    private val transport: String,
    private val bleAddress: String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveViewModel(
        url = url,
        cursorFor = { prefs.posCursor(it) },
        onCursor = { node, seq -> prefs.setPosCursor(node, seq) },
        gps = gps,
        binding = binding,
        ble = ble,
        transport = transport,
        bleAddress = bleAddress,
    ) as T
}
