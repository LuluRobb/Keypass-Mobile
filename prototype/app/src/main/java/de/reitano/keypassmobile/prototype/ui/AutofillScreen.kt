package de.reitano.keypassmobile.prototype.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.net.toUri
import de.reitano.keypassmobile.prototype.autofill.KpmAutofillService

/**
 * Prototype 1: Android autofill service.
 * Shows whether our service is the active one, opens the system dialog to
 * select it, explains the Brave setting and offers a test form in the own app.
 */
@Composable
fun AutofillScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val autofillManager = remember { context.getSystemService(AutofillManager::class.java) }
    val component = remember { ComponentName(context, KpmAutofillService::class.java).flattenToShortString() }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val selectService = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
    }
    // Read again after every refresh (button or return from the system dialog).
    val supported = remember(refresh) { autofillManager?.isAutofillSupported == true }
    val ours = remember(refresh) { autofillManager?.hasEnabledAutofillServices() == true }

    ScreenScaffold(title = "Autofill", onBack = onBack) {
        Text("Autofill unterstützt: ${yesNo(supported)}")
        Text(
            "KeyPass Mobile ist aktiver Autofill-Dienst: ${yesNo(ours)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                    .setData("package:${context.packageName}".toUri())
                try {
                    selectService.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    message = "Systemdialog nicht verfügbar. Bitte in den Android-Einstellungen unter " +
                        "\"Passwörter, Passkeys und Autofill\" wählen."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Als Autofill-Dienst auswählen") }
        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
            Text("Status aktualisieren")
        }
        message?.let { Text(it) }
        Text("Komponente (für adb): $component", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Brave", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Oben KeyPass Mobile als Autofill-Dienst auswählen.\n" +
                "2. In Brave: Einstellungen > Autofill > \"Autofill using another service\" wählen.\n" +
                "3. Brave vollständig beenden (aus der App-Übersicht wischen) und neu starten.\n" +
                "4. Anmeldeseite öffnen und in Benutzername oder Passwort tippen: Der Vorschlag " +
                "\"KeyPass Mobile\" erscheint oben in der Tastatur oder als Liste unter dem Feld.",
        )

        HorizontalDivider()
        TestForm()
    }
}

/**
 * Login form in the own app as a quick end-to-end check without a browser.
 * Values are only kept in memory (no rememberSaveable) and never shown.
 */
@Composable
private fun TestForm() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Text("Testformular (eigene App)", style = MaterialTheme.typography.titleMedium)
    Text("Passender Eintrag per App-Name: \"KPM Prototyp (Testformular)\".")
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("Benutzername") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Username },
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Passwort") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
    )
    Text("Ausgefüllt: Benutzername ${username.length} Zeichen, Passwort ${password.length} Zeichen")
    OutlinedButton(
        onClick = {
            username = ""
            password = ""
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Felder leeren") }
}

private fun yesNo(value: Boolean) = if (value) "ja" else "nein"
