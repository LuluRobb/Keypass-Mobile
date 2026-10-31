package de.reitano.keypassmobile.prototype.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.reitano.keypassmobile.prototype.smb.SmbDownloadResult
import de.reitano.keypassmobile.prototype.smb.SmbProbe
import de.reitano.keypassmobile.prototype.smb.SmbProbeException
import de.reitano.keypassmobile.prototype.smb.SmbServerInfo
import de.reitano.keypassmobile.prototype.smb.SmbSettings
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "smb_prototype"
private const val KEY_GUID = "last_guid"
private val FIELDS = listOf("host", "port", "share", "path", "user", "domain")

/** Target file; the KDBX prototype opens exactly this file. */
fun smbDownloadTarget(context: Context) = File(context.filesDir, "smb-download.kdbx")

/**
 * Prototype 3: SMB download, reading the server GUID before authentication.
 * Connection fields and the last GUID are kept in SharedPreferences, the
 * password only in memory (not even in the saved instance state).
 */
@Composable
fun SmbScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf(prefs.getString("host", "") ?: "") }
    var port by rememberSaveable { mutableStateOf(prefs.getString("port", "445") ?: "445") }
    var share by rememberSaveable { mutableStateOf(prefs.getString("share", "") ?: "") }
    var path by rememberSaveable { mutableStateOf(prefs.getString("path", "") ?: "") }
    var user by rememberSaveable { mutableStateOf(prefs.getString("user", "") ?: "") }
    var domain by rememberSaveable { mutableStateOf(prefs.getString("domain", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var expectedGuid by rememberSaveable { mutableStateOf(prefs.getString(KEY_GUID, "") ?: "") }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    var output by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun rememberGuid(guid: UUID) {
        prefs.edit().putString(KEY_GUID, guid.toString()).apply()
        expectedGuid = guid.toString()
    }

    /** Runs [action] on a background thread and shows its lines. Wipes the password copy afterwards. */
    fun run(action: (SmbSettings) -> List<String>) {
        val values = listOf(host, port, share, path, user, domain).map { it.trim() }
        prefs.edit().apply { FIELDS.zip(values).forEach { (k, v) -> putString(k, v) } }.apply()
        val portNumber = values[1].toIntOrNull()
        if (values[0].isEmpty() || portNumber == null || portNumber !in 1..65535) {
            output = "Fehler: Host und gültigen Port angeben."
            return
        }
        val secret = password.toCharArray()
        val settings = SmbSettings(
            host = values[0],
            port = portNumber,
            share = values[2],
            path = values[3],
            username = values[4],
            password = secret,
            domain = values[5],
            encryptIfOffered = encrypt,
        )
        busy = true
        output = "Läuft ..."
        scope.launch {
            val lines = try {
                withContext(Dispatchers.IO) { action(settings) }
            } catch (e: SmbProbeException) {
                listOf("Fehler (${e.kind}): ${e.message}")
            } catch (e: Exception) {
                // Only the type: messages of foreign exceptions are not checked for secrets.
                listOf("Fehler: ${e.javaClass.simpleName}")
            } finally {
                secret.fill('\u0000')
                busy = false
            }
            output = lines.joinToString("\n")
        }
    }

    ScreenScaffold(title = "SMB", onBack = onBack) {
        Text("Im Emulator ist der Host-PC unter 10.0.2.2 erreichbar. Docker-Testserver: Port 1445, Freigabe kpm, Pfad test.kdbx, Benutzer kpmtest.")
        TextButton(onClick = {
            host = "10.0.2.2"; port = "1445"; share = "kpm"; path = "test.kdbx"; user = "kpmtest"; domain = ""
        }) { Text("Testserver-Werte einsetzen (ohne Passwort)") }

        Field("Host", host) { host = it }
        Field("Port", port, KeyboardOptions(keyboardType = KeyboardType.Number)) { port = it }
        Field("Freigabe", share) { share = it }
        Field("Pfad", path) { path = it }
        Field("Benutzer", user) { user = it }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Passwort") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Field("Domäne (optional)", domain) { domain = it }
        Field("Erwartete GUID (leer = keine Prüfung)", expectedGuid) { expectedGuid = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = encrypt, onCheckedChange = { encrypt = it })
            Text("SMB-Verschlüsselung nutzen, wenn der Server sie anbietet")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                run { settings ->
                    val info = SmbProbe(settings).readServerInfo()
                    scope.launch { rememberGuid(info.serverGuid) }
                    describe(info) + "Keine Anmeldung durchgeführt."
                }
            }) { Text("Server-GUID lesen") }

            Button(enabled = !busy, onClick = {
                val expected = expectedGuid.trim().takeIf { it.isNotEmpty() }?.let {
                    runCatching { UUID.fromString(it) }.getOrElse {
                        output = "Fehler: Erwartete GUID ist keine gültige GUID."
                        return@Button
                    }
                }
                val target = smbDownloadTarget(context)
                run { settings ->
                    when (val result = SmbProbe(settings).download(expected, target)) {
                        is SmbDownloadResult.GuidMismatch -> listOf(
                            "Abgebrochen: GUID passt nicht, keine Anmeldung durchgeführt.",
                            "Erwartet: ${result.expectedGuid}",
                        ) + describe(result.server) + "Dauer gesamt: ${result.totalMillis} ms"

                        is SmbDownloadResult.Downloaded -> {
                            scope.launch { rememberGuid(result.server.serverGuid) }
                            describe(result) + ("GUID-Prüfung: " + if (expected == null) "keine" else "bestanden")
                        }
                    }
                }
            }) { Text("Herunterladen") }
        }

        Text(output, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun yesNo(b: Boolean) = if (b) "ja" else "nein"

private fun describe(info: SmbServerInfo) = listOf(
    "Server-GUID: ${info.serverGuid}",
    "Dialekt: ${info.dialect}",
    "Signierung: angeboten ${yesNo(info.signingEnabled)}, vom Server verlangt ${yesNo(info.signingRequired)}",
    "Verschlüsselung: " + if (info.encryptionOffered) "angeboten (${info.cipher ?: "?"})" else "nicht angeboten",
    "Verbindung + Negotiate: ${info.connectMillis} ms",
)

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

private fun describe(r: SmbDownloadResult.Downloaded): List<String> {
    val t = r.timings
    return describe(r.server) + listOf(
        "Sitzung: signiert ${yesNo(r.sessionSigned)}, verschlüsselt ${yesNo(r.sessionEncrypted)}",
        "Größe: ${r.size} Bytes",
        "Geändert: ${DATE.format(r.lastModified)}",
        "Dauer: Anmeldung ${t.authMillis} ms, Öffnen ${t.openMillis} ms, Übertragung ${t.transferMillis} ms, gesamt ${t.totalMillis} ms",
        "Durchsatz: " + String.format(Locale.GERMANY, "%.1f MiB/s", t.throughputMiBPerSecond(r.size)),
        "Gespeichert: ${r.target.absolutePath}",
    )
}
