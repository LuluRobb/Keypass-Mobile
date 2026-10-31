package de.reitano.keypassmobile.prototype.autofill

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.util.Log
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.reitano.keypassmobile.prototype.ui.PrototypeTheme
import de.reitano.keypassmobile.prototype.ui.ScreenScaffold

/** Context of one fill request, passed from the service to [AutofillSelectActivity]. */
data class SelectRequest(
    /** Web domain of the page (browser), null for apps. */
    val webDomain: String?,
    val packageName: String,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
) {
    fun toIntent(context: Context): Intent =
        Intent(context, AutofillSelectActivity::class.java)
            .putExtra(EXTRA_WEB_DOMAIN, webDomain)
            .putExtra(EXTRA_PACKAGE_NAME, packageName)
            .putExtra(EXTRA_USERNAME_ID, usernameId)
            .putExtra(EXTRA_PASSWORD_ID, passwordId)

    companion object {
        private const val EXTRA_WEB_DOMAIN = "kpm.autofill.WEB_DOMAIN"
        private const val EXTRA_PACKAGE_NAME = "kpm.autofill.PACKAGE_NAME"
        private const val EXTRA_USERNAME_ID = "kpm.autofill.USERNAME_ID"
        private const val EXTRA_PASSWORD_ID = "kpm.autofill.PASSWORD_ID"

        fun fromIntent(intent: Intent): SelectRequest? {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return null
            val usernameId = intent.getParcelableExtra(EXTRA_USERNAME_ID, AutofillId::class.java)
            val passwordId = intent.getParcelableExtra(EXTRA_PASSWORD_ID, AutofillId::class.java)
            if (usernameId == null && passwordId == null) return null
            return SelectRequest(intent.getStringExtra(EXTRA_WEB_DOMAIN), packageName, usernameId, passwordId)
        }
    }
}

/**
 * Picker opened by the "KeyPass Mobile" suggestion (UI-01 in small): header
 * with domain or app (AF-07), matching entries first (AF-05), warning for
 * non-matching entries in the browser (AF-06). Returns the filled dataset.
 *
 * Runs in the task of the requesting app (started for result by the system);
 * manifest: empty taskAffinity and excludeFromRecents, so it never shows up
 * as its own task. It finishes when it goes to the background.
 */
class AutofillSelectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Block screenshots and the recents thumbnail (SE-05).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val request = SelectRequest.fromIntent(intent)
        if (request == null) {
            Log.w(AUTOFILL_LOG_TAG, "picker started without request context")
            finish()
            return
        }
        val appLabel = loadAppLabel(request.packageName)
        Log.i(
            AUTOFILL_LOG_TAG,
            "picker opened: package=${request.packageName} appLabelVisible=${appLabel != null} " +
                "webDomain=${request.webDomain ?: "-"}",
        )
        val ranked = EntryMatcher.rank(TestEntries.ALL, request.webDomain, appLabel)

        setContent {
            PrototypeTheme {
                PickerScreen(
                    request = request,
                    appLabel = appLabel,
                    entries = ranked,
                    onFill = { index -> fill(request, ranked, index) },
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Do not linger in the task of the other app when the user leaves.
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    /** App name of the requesting package; null if Android hides it (package visibility). */
    private fun loadAppLabel(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun fill(request: SelectRequest, ranked: List<RankedEntry>, index: Int) {
        val item = ranked[index]
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, item.entry.title)
        }
        val presentations = Presentations.Builder().setMenuPresentation(presentation).build()
        val builder = Dataset.Builder(presentations)
        request.usernameId?.let { id ->
            builder.setField(id, Field.Builder().setValue(AutofillValue.forText(item.entry.username)).build())
        }
        request.passwordId?.let { id ->
            builder.setField(id, Field.Builder().setValue(AutofillValue.forText(item.entry.password)).build())
        }
        Log.i(
            AUTOFILL_LOG_TAG,
            "entry #$index chosen: matches=${item.matches} fillUsername=${request.usernameId != null} " +
                "fillPassword=${request.passwordId != null}",
        )
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build()))
        finish()
    }
}

@Composable
private fun PickerScreen(
    request: SelectRequest,
    appLabel: String?,
    entries: List<RankedEntry>,
    onFill: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    // Index of a non-matching entry waiting for confirmation (AF-06).
    var pendingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val isBrowser = request.webDomain != null

    ScreenScaffold(title = "KeyPass Mobile") {
        if (isBrowser) {
            Text("Website: ${request.webDomain}", style = MaterialTheme.typography.titleMedium)
            Text("Browser: ${appLabel ?: "?"} (${request.packageName})", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("App: ${appLabel ?: "(Name nicht lesbar)"}", style = MaterialTheme.typography.titleMedium)
            Text("Paket: ${request.packageName}", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "Felder erkannt: Benutzername ${yesNo(request.usernameId != null)}, " +
                "Passwort ${yesNo(request.passwordId != null)}",
            style = MaterialTheme.typography.bodySmall,
        )

        val (matching, others) = entries.withIndex().partition { it.value.matches }
        Text("Passende Einträge", style = MaterialTheme.typography.titleSmall)
        if (matching.isEmpty()) Text("Keine")
        matching.forEach { (index, item) ->
            EntryButton(item.entry) { onFill(index) }
        }
        Text("Weitere Einträge", style = MaterialTheme.typography.titleSmall)
        others.forEach { (index, item) ->
            EntryButton(item.entry) { if (isBrowser) pendingIndex = index else onFill(index) }
        }
        TextButton(onClick = onCancel) { Text("Abbrechen") }
    }

    pendingIndex?.let { index ->
        val entry = entries[index].entry
        AlertDialog(
            onDismissRequest = { pendingIndex = null },
            title = { Text("Warnung: Domain passt nicht") },
            text = {
                Text(
                    "Der Eintrag \"${entry.title}\" gehört zu ${EntryMatcher.hostOf(entry.url) ?: "keiner Website"}, " +
                        "die Seite ist ${request.webDomain}. Das kann eine Phishing-Seite sein. Trotzdem ausfüllen?",
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingIndex = null; onFill(index) }) { Text("Trotzdem ausfüllen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingIndex = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun EntryButton(entry: AutofillEntry, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(entry.title)
            Text("${entry.username} - ${entry.url ?: "ohne URL"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun yesNo(value: Boolean) = if (value) "ja" else "nein"
