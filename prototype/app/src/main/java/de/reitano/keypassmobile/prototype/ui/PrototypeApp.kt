package de.reitano.keypassmobile.prototype.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Screens of the test app. Simple state-based navigation, no navigation library. */
enum class Destination { Home, Autofill, Kdbx, Smb }

/** Root composable: theme plus switching between the home screen and the three prototype screens. */
@Composable
fun PrototypeApp() {
    PrototypeTheme {
        var destination by rememberSaveable { mutableStateOf(Destination.Home) }
        val backToHome = { destination = Destination.Home }

        BackHandler(enabled = destination != Destination.Home, onBack = backToHome)

        when (destination) {
            Destination.Home -> HomeScreen(onNavigate = { destination = it })
            Destination.Autofill -> AutofillScreen(onBack = backToHome)
            Destination.Kdbx -> KdbxScreen(onBack = backToHome)
            Destination.Smb -> SmbScreen(onBack = backToHome)
        }
    }
}
