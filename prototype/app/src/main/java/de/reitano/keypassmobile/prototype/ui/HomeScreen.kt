package de.reitano.keypassmobile.prototype.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Start screen with one button per prototype. */
@Composable
fun HomeScreen(onNavigate: (Destination) -> Unit) {
    ScreenScaffold(title = "KPM Prototyp") {
        Text("Prototypen für KeyPass Mobile (Abschnitt 10, Schritt 0).")
        Button(onClick = { onNavigate(Destination.Autofill) }, modifier = Modifier.fillMaxWidth()) {
            Text("1 - Autofill")
        }
        Button(onClick = { onNavigate(Destination.Kdbx) }, modifier = Modifier.fillMaxWidth()) {
            Text("2 - KDBX öffnen")
        }
        Button(onClick = { onNavigate(Destination.Smb) }, modifier = Modifier.fillMaxWidth()) {
            Text("3 - SMB-Download")
        }
    }
}
