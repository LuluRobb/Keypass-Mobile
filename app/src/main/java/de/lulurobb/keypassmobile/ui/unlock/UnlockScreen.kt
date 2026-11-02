package de.lulurobb.keypassmobile.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import de.lulurobb.keypassmobile.ui.components.SecretInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.model.FillTarget
import de.lulurobb.keypassmobile.ui.common.MessageHost
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.common.everyDaysText
import de.lulurobb.keypassmobile.ui.common.formatFileSize
import de.lulurobb.keypassmobile.ui.common.text
import de.lulurobb.keypassmobile.ui.components.BigButton
import de.lulurobb.keypassmobile.ui.components.BusyOverlay
import de.lulurobb.keypassmobile.ui.components.KpmTopBar
import de.lulurobb.keypassmobile.ui.components.NavAction
import de.lulurobb.keypassmobile.ui.components.Notice
import de.lulurobb.keypassmobile.ui.components.NoticeTone
import de.lulurobb.keypassmobile.ui.components.ScreenPadding
import de.lulurobb.keypassmobile.ui.components.SecretField
import de.lulurobb.keypassmobile.ui.components.SetupStep
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons
import kotlinx.coroutines.flow.drop

/** Which way of unlocking the screen offers (EN-01, EN-02). */
enum class UnlockMode {
    /** Fingerprint button first; the system prompt opens right away (not drawn here). */
    Fingerprint,

    /** Master password field. */
    Password,
}

/** Why the master password is asked for although the fingerprint is set up. Shown as a notice. */
sealed interface PasswordReason {
    /** The interval ran out (EN-05). */
    data class Due(val intervalDays: Int) : PasswordReason

    /** A new fingerprint was enrolled, the keystore key is invalid (EN-04). */
    data object FingerprintInvalidated : PasswordReason

    /** The stored hash did not open the file, e.g. password changed on the PC (EN-06). */
    data object StoredKeyRejected : PasswordReason
}

/** The local database cannot be opened at all; shown as a notice instead of the unlock controls. */
enum class UnlockProblem {
    /** No offline copy on the device yet (action: sync). */
    NoDatabase,

    /** The offline copy is damaged (actions: sync, choose the source again). */
    Corrupt,
}

enum class PasswordError {
    Empty,

    /** The password did not open the file. */
    Wrong,
}

/** Extra content when the unlock screen is step 3 of the first-run setup. */
data class FirstRunUnlock(
    val step: SetupStep,
    /** Size of the file just loaded from the share; null (e.g. after process death) hides the line. */
    val fileSizeBytes: Long?,
    /** Interval for the master password (EN-05), for the explanation. */
    val passwordIntervalDays: Int,
    /** State of the switch "Danach mit Fingerabdruck entsperren". */
    val fingerprintWanted: Boolean,
    /** False hides the switch (no fingerprint enrolled or no sensor). */
    val fingerprintAvailable: Boolean,
)

data class UnlockState(
    /** File name of the database, e.g. "Passwords.kdbx". */
    val fileName: String,
    val mode: UnlockMode,
    /** Fingerprint is set up and usable: offers "Doch mit Fingerabdruck" in password mode. */
    val fingerprintUsable: Boolean,
    val passwordReason: PasswordReason? = null,
    val passwordError: PasswordError? = null,
    /** Decrypting: shows the busy screen. */
    val busy: Boolean = false,
    /** Set when unlocking for an autofill request (AF-03): shows where it fills and a close button. */
    val fillTarget: FillTarget? = null,
    /** Set during the first-run setup (step 3). */
    val firstRun: FirstRunUnlock? = null,
    /** Set when the database cannot be opened: hides the password field and the fingerprint button. */
    val problem: UnlockProblem? = null,
    /** Why the app was locked ("Gesperrt.", ...); shown as a notice until the next successful unlock. */
    val lockNotice: UiMessage? = null,
)

/**
 * Unlock screen (EN-01 to EN-06), also step 3 of the setup. The password is
 * typed into [password], which the caller owns and clears after use.
 */
@Composable
fun UnlockScreen(
    state: UnlockState,
    password: SecretInput,
    onFingerprint: () -> Unit,
    onUsePassword: () -> Unit,
    onSubmitPassword: () -> Unit,
    modifier: Modifier = Modifier,
    onPasswordEdited: () -> Unit = {},
    onFingerprintWantedChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
    onCancelFill: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onChooseSource: () -> Unit = {},
    message: UiMessage? = null,
    onMessageDismissed: () -> Unit = {},
) {
    val first = state.firstRun
    val showPassword = state.mode == UnlockMode.Password || first != null
    val problem = state.problem
    val edited by rememberUpdatedState(onPasswordEdited)
    LaunchedEffect(password) {
        snapshotFlow { password.length }.drop(1).collect { edited() }
    }
    // After the empty error the field has the focus and the keyboard is open, ready for the next try. Not after a wrong
    // password: the field was disabled meanwhile and the keyboard binds to it anew, a focus request in between loses keys.
    // Waits for the end of the busy state: a disabled field cannot take the focus.
    LaunchedEffect(state.passwordError, state.busy) {
        if (state.passwordError == PasswordError.Empty && !state.busy) password.focus()
    }
    Box(modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                when {
                    state.fillTarget != null -> Box(Modifier.statusBarsPadding().padding(start = 4.dp, top = 8.dp)) {
                        IconButton(onClick = onCancelFill) {
                            KpmIcon(KpmIcons.Close, stringResource(R.string.action_cancel))
                        }
                    }
                    first != null -> KpmTopBar(
                        title = stringResource(R.string.setup_title),
                        navigation = NavAction(KpmIcons.Back, stringResource(R.string.action_back), onBack),
                        step = first.step,
                    )
                    else -> Box(Modifier.statusBarsPadding())
                }
            },
            snackbarHost = { MessageHost(message, onMessageDismissed) },
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(horizontal = ScreenPadding)
                            .padding(top = 24.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
                    ) {
                        UnlockHead(state)
                        state.fillTarget?.let { FillContext(it) }
                        state.lockNotice?.let { LockNotice(it) }
                        if (problem != null) {
                            ProblemNotice(problem, onSyncNow, onChooseSource)
                        } else {
                            ReasonNotice(state.passwordReason)
                        }
                        if (first != null) {
                            // Follows the current availability: the fingerprint may be enrolled while the step is open.
                            Notice(
                                tone = NoticeTone.Neutral,
                                icon = KpmIcons.Info,
                                text = if (first.fingerprintAvailable) {
                                    stringResource(
                                        R.string.unlock_first_run_info,
                                        everyDaysText(first.passwordIntervalDays),
                                    )
                                } else {
                                    stringResource(R.string.unlock_first_run_info_no_fingerprint)
                                },
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding)
                        .padding(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (problem != null) {
                        // The notice above carries the actions.
                    } else if (!showPassword) {
                        BigButton(
                            text = stringResource(R.string.unlock_with_fingerprint),
                            onClick = onFingerprint,
                            icon = KpmIcons.Fingerprint,
                        )
                        TextButton(onClick = onUsePassword) {
                            Text(stringResource(R.string.unlock_enter_password))
                        }
                    } else {
                        SecretField(
                            input = password,
                            label = stringResource(R.string.unlock_password_field),
                            isError = state.passwordError != null,
                            supportingText = when (state.passwordError) {
                                PasswordError.Empty -> stringResource(R.string.unlock_error_empty)
                                PasswordError.Wrong -> stringResource(R.string.unlock_error_wrong)
                                null -> null
                            },
                            enabled = !state.busy,
                            imeAction = ImeAction.Go,
                            onImeAction = onSubmitPassword,
                        )
                        if (first != null && first.fingerprintAvailable) {
                            SwitchRow(
                                text = stringResource(R.string.unlock_first_run_fingerprint),
                                checked = first.fingerprintWanted,
                                onCheckedChange = onFingerprintWantedChange,
                            )
                        }
                        BigButton(
                            text = stringResource(R.string.unlock_button),
                            onClick = onSubmitPassword,
                        )
                        if (first == null && state.fingerprintUsable && state.passwordReason == null) {
                            TextButton(onClick = onFingerprint) {
                                KpmIcon(KpmIcons.Fingerprint, null, Modifier.size(18.dp))
                                Text(
                                    stringResource(R.string.unlock_fingerprint_instead),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    // Normal unlock only: not in the setup, and not in the autofill picker (the source cannot be
                    // changed from there). The "Offline-Kopie beschädigt" notice has its own action for it.
                    if (first == null && state.fillTarget == null && problem != UnlockProblem.Corrupt) {
                        TextButton(onClick = onChooseSource) {
                            Text(stringResource(R.string.unlock_choose_source))
                        }
                    }
                }
            }
        }
        if (state.busy) BusyOverlay(stringResource(R.string.unlock_busy))
    }
}

@Composable
private fun UnlockHead(state: UnlockState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            state.fileName,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.firstRun?.fileSizeBytes?.let { size ->
            Text(
                stringResource(R.string.unlock_loaded_from_share, formatFileSize(size)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the request fills (web: domain, app: package name, AF-07). */
@Composable
private fun FillContext(target: FillTarget) {
    val where = when (target) {
        is FillTarget.Web -> target.host
        is FillTarget.App -> target.packageName
    }
    val template = stringResource(R.string.unlock_fill_context, "\u0000")
    val text = buildAnnotatedString {
        val i = template.indexOf('\u0000')
        append(template.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(where) }
        append(template.substring(i + 1))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KpmIcon(KpmIcons.Key, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The lock message ("Gesperrt.", ...) as a notice in the style of the password reasons. */
@Composable
private fun LockNotice(message: UiMessage) {
    Notice(
        tone = NoticeTone.Warning,
        icon = KpmIcons.Lock,
        text = message.text(),
    )
}

@Composable
private fun ReasonNotice(reason: PasswordReason?) {
    when (reason) {
        is PasswordReason.Due -> Notice(
            tone = NoticeTone.Warning,
            icon = KpmIcons.Info,
            title = stringResource(R.string.unlock_due_title),
            text = stringResource(R.string.unlock_due_text, everyDaysText(reason.intervalDays)),
        )
        PasswordReason.FingerprintInvalidated -> Notice(
            tone = NoticeTone.Warning,
            icon = KpmIcons.Fingerprint,
            title = stringResource(R.string.unlock_invalidated_title),
            text = stringResource(R.string.unlock_invalidated_text),
        )
        PasswordReason.StoredKeyRejected -> Notice(
            tone = NoticeTone.Warning,
            icon = KpmIcons.Info,
            title = stringResource(R.string.unlock_stored_rejected_title),
            text = stringResource(R.string.unlock_stored_rejected_text),
        )
        null -> Unit
    }
}

@Composable
private fun ProblemNotice(problem: UnlockProblem, onSyncNow: () -> Unit, onChooseSource: () -> Unit) {
    val tone = if (problem == UnlockProblem.Corrupt) NoticeTone.Error else NoticeTone.Warning
    val content = if (problem == UnlockProblem.Corrupt) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val colors = ButtonDefaults.textButtonColors(contentColor = content)
    Notice(
        tone = tone,
        icon = if (problem == UnlockProblem.Corrupt) KpmIcons.Error else KpmIcons.Warning,
        title = stringResource(
            if (problem == UnlockProblem.Corrupt) R.string.unlock_corrupt_title else R.string.unlock_no_db_title,
        ),
        text = stringResource(
            if (problem == UnlockProblem.Corrupt) R.string.unlock_corrupt_text else R.string.unlock_no_db_text,
        ),
        actions = {
            TextButton(onClick = onSyncNow, colors = colors) { Text(stringResource(R.string.unlock_sync_action)) }
            if (problem == UnlockProblem.Corrupt) {
                TextButton(onClick = onChooseSource, colors = colors) {
                    Text(stringResource(R.string.unlock_corrupt_action))
                }
            }
        },
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

