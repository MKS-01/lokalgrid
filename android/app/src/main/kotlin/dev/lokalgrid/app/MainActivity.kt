package dev.lokalgrid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lokalgrid.app.ui.AppShell
import dev.lokalgrid.app.ui.theme.Lg

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    val vm: LiveViewModel = viewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    AppShell(state)
                }
            }
        }
    }
}
