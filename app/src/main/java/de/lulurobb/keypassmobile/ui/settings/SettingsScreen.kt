package de.lulurobb.keypassmobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.common.breakAfter
import de.lulurobb.keypassmobile.ui.common.formatDate
import de.lulurobb.keypassmobile.ui.common.formatDayMonth
import de.lulurobb.keypassmobile.ui.common.formatStamp
import de.lulurobb.keypassmobile.ui.common.relativeStamp
import de.lulurobb.keypassmobile.ui.components.ChoiceRow
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SectionLabel
import de.lulurobb.keypassmobile.ui.components.SettingsRow
import de.lulurobb.keypassmobile.ui.components.SettingsSubline
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import java.time.LocalDate

/** The configured SMB source (DS-01). */
data class SourceInfo(val host: String, val share: String, val path: List<String>, val user: String) {
    /** UNC form for display: \\host\share\folder\file.kdbx */
    val unc: String get() = "\\\\" + (listOf(host, share) + path).joinToString("\\")
}

/** Remembered server identity (DS-04). */
data class ServerIdentity(val name: String, val rememberedOn: LocalDate, val guid: String)

enum class FingerprintAvailability { Available, NoneEnrolled, NoHardware }

data class SettingsState(
    val source: SourceInfo,
    val server: ServerIdentity?,
    val sync: SyncState,
    val fingerprintEnabled: Boolean,
    val fingerprintAvailability: FingerprintAvailability = FingerprintAvailability.Available,
    /** Date when the master password is due again (EN-05); null when fingerprint is off. */
    val passwordDueOn: LocalDate?,
    val passwordIntervalDays: Int,
    val autoLockSeconds: Int,
    val clipboardSeconds: Int,
    val autofillSelected: Boolean,
    val notificationsAllowed: Boolean,
    val versionName: String,
) {
    companion object {
        val PasswordIntervals = listOf(1, 3, 7, 14)
        val AutoLockTimes = listOf(30, 60, 120, 300)
        val ClipboardTimes = listOf(15, 30, 60)
    }
}

/** Settings (UI-03, DS-01, SY-07, SY-08, EN-05, SE-02, AF-09). Every control saves itself. */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onEditSource: () -> Unit,
    onSyncNow: () -> Unit,
    onFingerprintChange: (Boolean) -> Unit,
    onPasswordIntervalChange: (Int) -> Unit,
    onAutoLockChange: (Int) -> Unit,
    onClipboardChange: (Int) -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        topBar = {
            KpmTopBar(
                title = stringResource(R.string.settings_title),
                navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
            )
        },
        // The Scaffold knows only the horizontal insets here (the content scrolls behind the system bars),
        // so the snackbar lifts itself above the navigation bar and the keyboard.
        snackbarHost = {
            MessageHost(
                message,
                onMessageDismissed,
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = scheme.surface,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()),
        ) {
            SectionLabel(stringResource(R.string.settings_section_database), topPadding = 8.dp)
            SettingsRow(
                icon = KpmIcons.Server,
                title = stringResource(R.string.settings_source),
                sublines = listOf(
                    SettingsSubline(breakAfter(state.source.unc, "\\"), monospace = true),
                    SettingsSubline(stringResource(R.string.settings_source_user, state.source.user)),
                ),
                onClick = onEditSource,
                trailing = { KpmIcon(KpmIcons.ChevronRight, null, tint = scheme.onSurfaceVariant) },
            )
            state.server?.let { server ->
                SettingsRow(
                    icon = KpmIcons.Key,
                    title = stringResource(R.string.settings_server),
                    sublines = listOf(
                        SettingsSubline(stringResource(R.string.settings_server_remembered, server.name, formatDate(server.rememberedOn))),
                        SettingsSubline(breakAfter(server.guid, "-"), monospace = true),
                    ),
                )
            }

            SectionLabel(stringResource(R.string.settings_section_sync))
            SyncRow(state.sync)
            OutlinedButton(
                onClick = onSyncNow,
                enabled = !state.sync.syncing,
                // While syncing the button stays readable; it only stops taking taps.
                colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = scheme.primary.copy(alpha = 0.7f)),
                modifier = Modifier.padding(start = ScreenPadding + 40.dp, top = 4.dp),
            ) {
                if (state.sync.syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    KpmIcon(KpmIcons.Sync, null, Modifier.size(18.dp))
                }
                Text(
                    stringResource(if (state.sync.syncing) R.string.sync_chip_syncing else R.string.settings_sync_now),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionLabel(stringResource(R.string.settings_section_unlock))
            val available = state.fingerprintAvailability == FingerprintAvailability.Available
            SettingsRow(
                icon = KpmIcons.Fingerprint,
                title = stringResource(R.string.settings_fingerprint),
                sublines = listOf(
                    SettingsSubline(
                        when {
                            state.fingerprintAvailability == FingerprintAvailability.NoneEnrolled ->
                                stringResource(R.string.settings_fingerprint_none_enrolled)
                            state.fingerprintAvailability == FingerprintAvailability.NoHardware ->
                                stringResource(R.string.settings_fingerprint_no_hardware)
                            state.fingerprintEnabled && state.passwordDueOn != null ->
                                stringResource(R.string.settings_fingerprint_due, formatDayMonth(state.passwordDueOn))
                            state.fingerprintEnabled -> ""
                            else -> stringResource(R.string.settings_fingerprint_off)
                        },
                    ),
                ).filter { it.text.isNotEmpty() },
                checked = state.fingerprintEnabled && available,
                onCheckedChange = onFingerprintChange,
                enabled = available,
                trailing = {
                    Switch(checked = state.fingerprintEnabled && available, onCheckedChange = null, enabled = available)
                },
            )
            if (state.fingerprintEnabled && available) {
                Text(
                    stringResource(R.string.settings_password_interval),
                    modifier = Modifier.padding(start = ScreenPadding + 40.dp, end = ScreenPadding, top = 2.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChoiceRow(
                    options = SettingsState.PasswordIntervals,
                    selected = state.passwordIntervalDays,
                    onSelect = onPasswordIntervalChange,
                    label = { pluralStringResource(R.plurals.after_days, it, it) },
                )
            }
            SettingsRow(icon = KpmIcons.Lock, title = stringResource(R.string.settings_auto_lock))
            ChoiceRow(
                options = SettingsState.AutoLockTimes,
                selected = state.autoLockSeconds,
                onSelect = onAutoLockChange,
                label = {
                    if (it < 60) stringResource(R.string.seconds_short, it) else stringResource(R.string.minutes_short, it / 60)
                },
            )

            SectionLabel(stringResource(R.string.settings_section_clipboard))
            SettingsRow(icon = KpmIcons.Copy, title = stringResource(R.string.settings_clipboard))
            ChoiceRow(
                options = SettingsState.ClipboardTimes,
                selected = state.clipboardSeconds,
                onSelect = onClipboardChange,
                label = { stringResource(R.string.seconds_short, it) },
            )
            // Keyboard clipboard histories ignore the sensitive flag and the deletion.
            Text(
                stringResource(R.string.settings_clipboard_keyboard_hint),
                modifier = Modifier.padding(start = ScreenPadding + 40.dp, end = ScreenPadding, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            SectionLabel(stringResource(R.string.settings_section_system))
            SettingsRow(
                icon = KpmIcons.Keyboard,
                title = stringResource(R.string.settings_autofill),
                sublines = listOf(
                    SettingsSubline(
                        stringResource(if (state.autofillSelected) R.string.settings_autofill_on else R.string.settings_autofill_off),
                    ),
                ),
                onClick = onOpenAutofillSettings,
                trailing = { KpmIcon(KpmIcons.ChevronRight, null, tint = scheme.onSurfaceVariant) },
            )
            SettingsRow(
                icon = KpmIcons.Notifications,
                title = stringResource(R.string.settings_notifications),
                sublines = listOf(
                    SettingsSubline(
                        stringResource(
                            if (state.notificationsAllowed) R.string.settings_notifications_on else R.string.settings_notifications_off,
                        ),
                    ),
                ),
                onClick = onOpenNotificationSettings,
                trailing = { KpmIcon(KpmIcons.ChevronRight, null, tint = scheme.onSurfaceVariant) },
            )

            Text(
                stringResource(R.string.settings_footer, state.versionName),
                modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 28.dp, bottom = 32.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncRow(sync: SyncState) {
    val status = sync.status
    val (icon, title, detail) = when (status) {
        is SyncStatus.Current -> Triple(
            KpmIcons.CheckCircle,
            stringResource(R.string.settings_sync_current),
            status.loadedAt
                ?.let { stringResource(R.string.settings_sync_current_text, relativeStamp(it)) }
                ?: stringResource(R.string.settings_sync_current_text_undated),
        )
        is SyncStatus.OfflineCopy -> Triple(
            KpmIcons.CloudOff,
            stringResource(
                when (status.reason) {
                    null -> R.string.sync_chip_syncing
                    OfflineReason.ShareUnreachable -> R.string.settings_sync_offline
                    OfflineReason.OtherServer -> R.string.settings_sync_other_server
                    OfflineReason.NewVersionRejected -> R.string.banner_candidate_title
                    OfflineReason.LoginFailed -> R.string.settings_sync_login_failed
                    OfflineReason.FileNotFound -> R.string.settings_sync_file_not_found
                    OfflineReason.NoAccess -> R.string.settings_sync_no_access
                },
            ),
            status.copyFrom
                ?.let { stringResource(R.string.settings_sync_offline_text, formatStamp(it)) }
                ?: stringResource(R.string.settings_sync_offline_text_undated),
        )
    }
    SettingsRow(
        icon = icon,
        title = title,
        sublines = listOf(SettingsSubline(detail)),
    )
}
