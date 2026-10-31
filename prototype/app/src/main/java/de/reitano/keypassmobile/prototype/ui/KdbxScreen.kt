package de.reitano.keypassmobile.prototype.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import de.reitano.keypassmobile.prototype.kdbx.Argon2Function
import de.reitano.keypassmobile.prototype.kdbx.Argon2Params
import de.reitano.keypassmobile.prototype.kdbx.Argon2Variant
import de.reitano.keypassmobile.prototype.kdbx.KdbxOpenException
import de.reitano.keypassmobile.prototype.kdbx.KdbxProbe
import de.reitano.keypassmobile.prototype.kdbx.OpenStats
import de.reitano.keypassmobile.prototype.kdbx.OpenedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.CharBuffer
import java.util.Locale

/** File written by the SMB prototype. */
private const val SMB_DOWNLOAD = "smb-download.kdbx"

/** Unlock target from the requirements (section 8). */
private const val TARGET_MILLIS = 2000.0

/** Encrypted KDBX file, held in memory only. */
private class LoadedFile(val name: String, val bytes: ByteArray)

/**
 * Prototype 2: open a KDBX file and measure the unlock time with kotpass'
 * pure Kotlin Argon2 and with native Argon2 (argon2kt). Shows only metadata
 * and timings, never entry contents.
 */
@Composable
fun KdbxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<LoadedFile?>(null) }
    var passwordField by remember { mutableStateOf<EditText?>(null) }
    var measureNative by remember { mutableStateOf(true) }
    var measureKotpass by remember { mutableStateOf(true) }
    var threeRuns by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Keine Datei geladen.") }
    var output by remember { mutableStateOf("") }

    fun load(loader: () -> LoadedFile) {
        scope.launch {
            status = try {
                val loaded = withContext(Dispatchers.IO) { loader() }
                file = loaded
                "Geladen: ${loaded.name}, ${loaded.bytes.size / 1024} KiB (nur im Arbeitsspeicher)"
            } catch (e: Exception) {
                "Fehler beim Laden: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load { readDocument(context, uri) }
    }
    val smbFile = File(context.filesDir, SMB_DOWNLOAD)

    ScreenScaffold(title = "KDBX", onBack = onBack) {
        Text("Datei", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !running) {
            Text("Datei wählen")
        }
        if (smbFile.exists()) {
            Button(onClick = { load { LoadedFile(SMB_DOWNLOAD, smbFile.readBytes()) } }, enabled = !running) {
                Text("Per SMB geladene Datei öffnen")
            }
        }
        Text(status)

        Text("Master-Passwort", style = MaterialTheme.typography.titleMedium)
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx -> createPasswordField(ctx).also { passwordField = it } },
            update = {
                it.setTextColor(textColor)
                it.setHintTextColor(hintColor)
            },
        )

        LabeledCheckbox("Nativ messen (argon2kt)", measureNative) { measureNative = it }
        LabeledCheckbox("kotpass messen (reines Kotlin, kann lange dauern)", measureKotpass) { measureKotpass = it }
        LabeledCheckbox("3 Durchläufe mit Mittelwert", threeRuns) { threeRuns = it }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Zusätzlicher Suchbegriff (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !running && file != null && (measureNative || measureKotpass),
            onClick = {
                val loaded = file ?: return@Button
                val field = passwordField ?: return@Button
                val password = field.takePasswordUtf8()
                val options = Options(measureNative, measureKotpass, if (threeRuns) 3 else 1, query.trim())
                running = true
                output = ""
                scope.launch {
                    output = withContext(Dispatchers.Default) {
                        measure(loaded, password, options) { status = it }
                    }
                    status = "Fertig. Passwort und entschlüsselte Daten verworfen."
                    running = false
                }
            },
        ) {
            Text("Öffnen und messen")
        }

        if (output.isNotEmpty()) {
            Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabeledCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** Password field without suggestions, autocorrect and keyboard learning (EN-01). */
private fun createPasswordField(context: Context) = EditText(context).apply {
    hint = "Master-Passwort"
    // Password variation also makes the field single-line and masked.
    inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
    // Keep other autofill services away from the master password.
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
}

/**
 * Copies the password as UTF-8 bytes without creating a String and empties
 * the field. The Editable is overwritten first; the IME may still keep its
 * own copy, which an app cannot control.
 */
private fun EditText.takePasswordUtf8(): ByteArray {
    val editable = text
    val chars = CharArray(editable.length)
    editable.getChars(0, editable.length, chars, 0)
    val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
    val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
    chars.fill(' ')
    if (encoded.hasArray()) encoded.array().fill(0)
    editable.replace(0, editable.length, " ".repeat(editable.length))
    editable.clear()
    return bytes
}

private fun readDocument(context: Context, uri: Uri): LoadedFile {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: "Dokument"
    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Datei nicht lesbar" }
        .use { it.readBytes() }
    return LoadedFile(name, bytes)
}

private class Options(val native: Boolean, val kotpass: Boolean, val runs: Int, val extraQuery: String)

/**
 * Runs the selected measurements. The first unlock uses the password (and
 * overwrites it), all further ones its SHA-256 hash as fingerprint unlock
 * would (EN-03). Every database is closed right after its run.
 */
private fun measure(file: LoadedFile, password: ByteArray, options: Options, progress: (String) -> Unit): String {
    val out = StringBuilder()
    val runtime = Runtime.getRuntime()
    out.appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    out.appendLine("CPU-Kerne: ${runtime.availableProcessors()}, Heap max: ${runtime.maxMemory() / MIB} MiB")
    out.appendLine("Datei: ${file.name}, ${file.bytes.size / 1024} KiB, Bibliothek: ${KdbxProbe.LIBRARY}")

    val hash = KdbxProbe.passwordHash(password)
    var pendingPassword: ByteArray? = password
    var lastDb: OpenedDatabase? = null
    var headerShown = false
    try {
        val implementations = buildList {
            if (options.native) add("Nativ (argon2kt)" to NativeArgon2)
            if (options.kotpass) add("kotpass (reines Kotlin)" to null)
        }
        for ((label, argon2) in implementations) {
            out.appendLine()
            out.appendLine("== $label")
            val runs = mutableListOf<OpenStats>()
            try {
                for (run in 1..options.runs) {
                    progress("Läuft: $label, Durchlauf $run/${options.runs} ...")
                    val pw = pendingPassword
                    pendingPassword = null
                    val db = if (pw != null) {
                        KdbxProbe.open(file.bytes, pw, argon2)
                    } else {
                        KdbxProbe.openWithPasswordHash(file.bytes, hash.copyOf(), argon2)
                    }
                    val s = db.stats
                    if (!headerShown) {
                        headerShown = true
                        out.insert(0, describeFile(s))
                    }
                    out.appendLine(
                        "Lauf $run: KDF ${ms(s.kdfMillis)}, Entschlüsseln+XML ${ms(s.decodeMillis - s.kdfMillis)}, " +
                            "Liste ${ms(s.indexMillis)}, gesamt ${ms(s.totalMillis)}  [${s.kdfImplementation}]",
                    )
                    runs += s
                    lastDb?.close()
                    lastDb = db
                }
            } catch (e: KdbxOpenException) {
                out.appendLine("Fehler: ${e.message}")
                if (e.reason == KdbxOpenException.Reason.WRONG_KEY) break
            } catch (e: OutOfMemoryError) {
                out.appendLine("Fehler: Arbeitsspeicher reicht nicht (${e.message})")
            } catch (e: Throwable) {
                out.appendLine("Fehler: ${e.javaClass.simpleName}: ${e.message}")
            }
            if (runs.size > 1) {
                out.appendLine(
                    "Mittel: KDF ${ms(runs.map { it.kdfMillis }.average())}, " +
                        "gesamt ${ms(runs.map { it.totalMillis }.average())}",
                )
            }
            if (runs.isNotEmpty()) {
                val avg = runs.map { it.totalMillis }.average()
                val verdict = if (avg <= TARGET_MILLIS) "erreicht" else "verfehlt"
                out.appendLine("Ziel höchstens 2 s bis zur Liste: $verdict (${ms(avg)}, ohne Fingerabdruck-Dialog)")
            }
        }

        lastDb?.let { db ->
            progress("Läuft: Suche ...")
            out.appendLine()
            out.appendLine("== Suche über ${db.entries.size} Einträge (5 Läufe, nur Trefferzahl)")
            val queries = listOf("a", "mail", "zzzz-kein-treffer") + listOfNotNull(options.extraQuery.ifBlank { null })
            for (q in queries) {
                val t = db.measureSearch(q, repetitions = 5)
                out.appendLine("'${t.query}': ${t.hits} Treffer, erster Lauf ${ms(t.firstMillis)}, Mittel ${ms(t.averageMillis)}")
            }
        }
    } finally {
        pendingPassword?.fill(0)
        hash.fill(0)
        lastDb?.close()
    }
    return out.toString()
}

private fun describeFile(s: OpenStats) = buildString {
    appendLine("KDBX ${s.formatVersion}, Verschlüsselung ${s.cipher}")
    appendLine("KDF: ${s.kdf.describe()}")
    appendLine("Einträge: ${s.entryCount}, Gruppen: ${s.groupCount}")
}

private const val MIB = 1024 * 1024

private fun ms(value: Double): String =
    if (value < 10) String.format(Locale.GERMANY, "%.2f ms", value) else String.format(Locale.GERMANY, "%.0f ms", value)

/**
 * Native Argon2 through argon2kt (reference implementation via JNI, uses
 * threads for parallelism > 1). Plugged into kotpass as KDF for KDBX files.
 */
private object NativeArgon2 : Argon2Function {
    private val argon2 by lazy { Argon2Kt() }

    override fun hash(password: ByteArray, params: Argon2Params): ByteArray {
        val result = argon2.hash(
            mode = when (params.variant) {
                Argon2Variant.Argon2d -> Argon2Mode.ARGON2_D
                Argon2Variant.Argon2id -> Argon2Mode.ARGON2_ID
            },
            password = password,
            salt = params.salt,
            tCostInIterations = params.iterations.toInt(),
            mCostInKibibyte = params.memoryKiB.toInt(),
            parallelism = params.parallelism,
            hashLengthInBytes = 32,
            version = if (params.version == 0x10) Argon2Version.V10 else Argon2Version.V13,
        )
        val raw = result.rawHashAsByteArray()
        // The result lives in a direct buffer; overwrite it.
        result.rawHash.let { buffer ->
            buffer.clear()
            while (buffer.hasRemaining()) buffer.put(0)
        }
        return raw
    }
}
