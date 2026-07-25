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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lokalgrid.app.onboarding.OnboardingScreen
import dev.lokalgrid.app.ui.AppShell
import dev.lokalgrid.app.ui.BOOT_GRACE_MS
import dev.lokalgrid.app.ui.BootScreen
import dev.lokalgrid.app.ui.theme.Lg
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

        val prefs = Prefs(this)

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

                    LaunchedEffect(Unit) {
                        if (!prefs.onboarded) route = Route.ONBOARDING
                    }

                    if (route == Route.ONBOARDING) {
                        OnboardingScreen(prefs) { chosen ->
                            url = chosen
                            route = Route.BOOT
                        }
                    } else {
                        // Keyed on the URL: changing which node to talk to builds a
                        // new ViewModel and a new socket, instead of quietly leaving
                        // the old connection running under a new label. The socket
                        // opens here, *under* the boot screen — so the splash is
                        // covering real work rather than padding a timer.
                        key(url) {
                            val vm: LiveViewModel = viewModel(factory = LiveViewModelFactory(url))
                            val state by vm.state.collectAsStateWithLifecycle()

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
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hands the chosen node URL to the ViewModel. */
private class LiveViewModelFactory(private val url: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveViewModel(url) as T
}
