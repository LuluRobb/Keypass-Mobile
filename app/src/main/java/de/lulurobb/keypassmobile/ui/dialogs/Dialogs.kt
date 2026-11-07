package de.lulurobb.keypassmobile.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.ui.common.formatDateTime
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import java.time.LocalDateTime

/**
 * Warning before filling an entry whose URL does not match the page (AF-06).
 * [pageDomain] is the page's registrable domain or host, [entryDomain] the
 * entry's (null when the entry has no URL); both come from :core.
 */
data class PhishingWarning(val entry: VaultEntry, val pageDomain: String, val entryDomain: String?)

/**
 * Phishing warning: the two domains compared, the part where the page differs
 * highlighted. "Fill anyway" is deliberately the weaker button.
 */
@Composable
fun PhishingDialog(warning: PhishingWarning, onFillAnyway: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { KpmIcon(KpmIcons.Warning, contentDescription = null, tint = scheme.error) },
        title = { Text(stringResource(R.string.phishing_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CompareBlock(
                    listOf(
                        AnnotatedString(stringResource(R.string.phishing_this_page)) to
                            highlightDifference(warning.pageDomain, warning.entryDomain.orEmpty()),
                        AnnotatedString(stringResource(R.string.phishing_entry_label, warning.entry.title)) to
                            AnnotatedString(warning.entryDomain ?: stringResource(R.string.phishing_entry_no_url)),
                    ),
                )
                Text(stringResource(R.string.phishing_text))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = {
            TextButton(
                onClick = onFillAnyway,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
            ) { Text(stringResource(R.string.phishing_fill_anyway)) }
        },
    )
}

/** Remembered and current server identity for the "Anderer Server" dialog (DS-04). */
data class OtherServerInfo(
    val host: String,
    val rememberedName: String,
    val rememberedGuid: String,
    /** Name the new server reports, null when unknown. */
    val currentName: String?,
    val currentGuid: String,
)

/** Asks once whether a changed server identity is legitimate (DS-04). */
@Composable
fun OtherServerDialog(info: OtherServerInfo, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { KpmIcon(KpmIcons.Server, contentDescription = null, tint = scheme.error) },
        title = { Text(stringResource(R.string.other_server_title, info.host), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.other_server_text))
                CompareBlock(
                    listOf(
                        AnnotatedString(stringResource(R.string.other_server_remembered)) to
                            AnnotatedString(info.rememberedName + "\n" + shortGuid(info.rememberedGuid)),
                        AnnotatedString(stringResource(R.string.other_server_now)) to buildAnnotatedString {
                            withStyle(SpanStyle(color = scheme.error, fontWeight = FontWeight.SemiBold)) {
                                append(info.currentName ?: stringResource(R.string.other_server_unknown_name))
                                append("\n")
                                append(shortGuid(info.currentGuid))
                            }
                        },
                    ),
                )
                Text(stringResource(R.string.other_server_advice))
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.other_server_reject)) }
        },
        dismissButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.other_server_accept)) }
        },
    )
}

/**
 * The picked file is older than the version that is open (DS-14). Both dates are local
 * date and time; the dialog shows them as "31.10.2026, 21:38".
 */
data class OlderVersionInfo(
    val fileName: String,
    /** Modification date of the picked file. */
    val fileModified: LocalDateTime,
    /** Modification date of the file the open version was read from. */
    val openModified: LocalDateTime,
)

/**
 * Asks whether to use a file that is older than the open version of the same database (DS-14).
 * "Trotzdem verwenden" ([onUseAnyway]) continues the switch, "Abbrechen" ([onCancel]) discards the
 * pick and keeps the source. Dismissing the dialog (back, tap outside) counts as cancel.
 */
@Composable
fun OlderVersionDialog(info: OlderVersionInfo, onUseAnyway: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.older_title)) },
        text = {
            Text(
                stringResource(
                    R.string.older_text,
                    info.fileName,
                    formatDateTime(info.fileModified),
                    formatDateTime(info.openModified),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onUseAnyway) { Text(stringResource(R.string.older_use_anyway)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** First two GUID groups, enough to tell two servers apart in a dialog. */
private fun shortGuid(guid: String): String =
    if (guid.length > 14) guid.substring(0, 13) + "…" else guid

/**
 * Page domain with the part after the common prefix highlighted, as in the
 * draft: "paypa" plain, "1-login.com" marked.
 */
@Composable
fun highlightDifference(page: String, entry: String): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    var i = 0
    while (i < page.length && i < entry.length && page[i] == entry[i]) i++
    return buildAnnotatedString {
        append(page.substring(0, i))
        if (i < page.length) {
            withStyle(SpanStyle(background = scheme.errorContainer, color = scheme.onErrorContainer)) {
                append(page.substring(i))
            }
        }
    }
}

/** Label/value pairs in two aligned columns on a tonal block; values in monospace. */
@Composable
private fun CompareBlock(rows: List<Pair<AnnotatedString, AnnotatedString>>) {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyMedium
    val mono = body.copy(fontFamily = FontFamily.Monospace, color = scheme.onSurface)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHighest,
    ) {
        TwoColumns(
            labels = rows.map { { Text(it.first, style = body, color = scheme.onSurfaceVariant) } },
            values = rows.map { { Text(it.second, style = mono) } },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Grid with a label column as wide as its widest label (at most 45 %). */
@Composable
private fun TwoColumns(
    labels: List<@Composable () -> Unit>,
    values: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf({ labels.forEach { it() } }, { values.forEach { it() } }),
        modifier = modifier,
    ) { (labelMeasurables, valueMeasurables), constraints ->
        val gapX = 12.dp.roundToPx()
        val gapY = 6.dp.roundToPx()
        val maxLabel = (constraints.maxWidth * 0.45f).toInt()
        val labelPlaceables = labelMeasurables.map { it.measure(Constraints(maxWidth = maxLabel)) }
        val labelWidth = labelPlaceables.maxOfOrNull { it.width } ?: 0
        val valueWidth = (constraints.maxWidth - labelWidth - gapX).coerceAtLeast(0)
        val valuePlaceables = valueMeasurables.map { it.measure(Constraints(maxWidth = valueWidth)) }
        val rowHeights = labelPlaceables.indices.map { maxOf(labelPlaceables[it].height, valuePlaceables[it].height) }
        val height = rowHeights.sum() + gapY * (rowHeights.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = 0
            rowHeights.forEachIndexed { i, h ->
                labelPlaceables[i].place(0, y)
                valuePlaceables[i].place(labelWidth + gapX, y)
                y += h + gapY
            }
        }
    }
}
