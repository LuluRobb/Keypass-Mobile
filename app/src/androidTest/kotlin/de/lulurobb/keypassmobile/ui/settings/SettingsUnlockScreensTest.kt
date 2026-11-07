package de.lulurobb.keypassmobile.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.SourceAction
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.components.rememberSecretInput
import de.lulurobb.keypassmobile.ui.list.SourceProblem
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import de.lulurobb.keypassmobile.ui.unlock.FirstRunUnlock
import de.lulurobb.keypassmobile.ui.unlock.UnlockMode
import de.lulurobb.keypassmobile.ui.unlock.UnlockProblem
import de.lulurobb.keypassmobile.ui.unlock.UnlockScreen
import de.lulurobb.keypassmobile.ui.unlock.UnlockState
import de.lulurobb.keypassmobile.ui.components.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/** Section "Quelle" and sync status of the settings (UI-08, UI-09) and the source line of the unlock screen (EN-07). */
@RunWith(AndroidJUnit4::class)
class SettingsUnlockScreensTest {

    @get:Rule
    val rule = createComposeRule()

    // A day that is not today, so the stamp always reads "03.01., 08:12".
    private val copy = LocalDateTime.of(2020, 1, 3, 8, 12)

    private val smb = SourceEntry.Smb(
        SourceInfo("nas.local", "daten", listOf("KeePass", "pw.kdbx"), "luca"),
        ServerIdentity("reitano-nas", LocalDate.of(2026, 10, 31), "74696572-6e61-2d6f-6e61-730000000000"),
    )
    private val file = SourceEntry.File("Passwoerter.kdbx", "Interner Speicher / Syncthing / KeePass")

    private var editSource = 0
    private var aboutOpened = 0
    private val actions = mutableListOf<SourceAction>()

    private fun settings(
        source: SourceEntry,
        reason: OfflineReason? = null,
        problem: SourceProblem? = null,
        kind: SourceKind = if (source is SourceEntry.File) SourceKind.FILE else SourceKind.SMB,
    ) {
        val status = if (reason == null) SyncStatus.Current(null) else SyncStatus.OfflineCopy(copy, reason)
        rule.setContent {
            KeyPassTheme {
                SettingsScreen(
                    state = SettingsState(
                        source = source,
                        sync = SyncState(status, kind = kind),
                        sourceProblem = problem,
                        fingerprintEnabled = false,
                        passwordDueOn = null,
                        passwordIntervalDays = 7,
                        autoLockSeconds = 120,
                        autofillSelected = true,
                        notificationsAllowed = true,
                        versionName = "0.1.0",
                    ),
                    onBack = {},
                    onEditSource = { editSource++ },
                    onSyncNow = {},
                    onFingerprintChange = {},
                    onPasswordIntervalChange = {},
                    onAutoLockChange = {},
                    onOpenAutofillSettings = {},
                    onOpenNotificationSettings = {},
                    onOpenAbout = { aboutOpened++ },
                    onSourceAction = { actions += it },
                )
            }
        }
    }

    @Test
    fun smbEntryIsReadOnlyAndManageRowOpensTheManageScreen() {
        settings(smb)
        rule.onNodeWithText("Quelle").assertIsDisplayed()
        rule.onNodeWithText("SMB-Freigabe").assertIsDisplayed().assertHasNoClickAction()
        rule.onNodeWithText("Benutzer luca").assertIsDisplayed()
        rule.onNodeWithText("Server reitano-nas, gemerkt am 31.10.2026").assertIsDisplayed()
        rule.onNodeWithText("Quelle verwalten").performClick()
        assertEquals(1, editSource)
    }

    @Test
    fun fileEntryShowsNameAndPlace() {
        settings(file)
        rule.onNodeWithText("Datei auf dem Handy").assertIsDisplayed().assertHasNoClickAction()
        rule.onNodeWithText("Passwoerter.kdbx").assertIsDisplayed()
        rule.onNodeWithText("Interner Speicher / Syncthing / KeePass").assertIsDisplayed()
        rule.onAllNodesWithText("Benutzer luca").assertCountEquals(0)
    }

    @Test
    fun aboutRowAtTheBottomShowsTheVersionAndOpensTheAboutScreen() {
        settings(smb)
        rule.onNodeWithText("Über KeyPass Mobile").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Version 0.1.0").assertIsDisplayed()
        rule.onNodeWithText("Über KeyPass Mobile").performClick()
        assertEquals(1, aboutOpened)
    }

    @Test
    fun backgroundLineFollowsTheSourceType() {
        settings(smb)
        rule.onNodeWithText("Im Hintergrund alle 4 Stunden, wenn der Share erreichbar ist.").assertExists()
    }

    @Test
    fun backgroundLineOfAFile() {
        settings(file)
        rule.onNodeWithText("Im Hintergrund alle 4 Stunden.").assertExists()
        rule.onNodeWithText("Version aus der Datei.").assertExists()
    }

    @Test
    fun fileNotFoundShowsReasonAndRepickButton() {
        settings(file, OfflineReason.LocalFileNotFound, SourceProblem.FILE_NOT_FOUND)
        rule.onNodeWithText("Datei nicht gefunden").assertExists()
        rule.onNodeWithText(
            "Geöffnet ist die Offline-Kopie vom 03.01., 08:12. Die Datei wurde verschoben, umbenannt oder gelöscht.",
        ).assertExists()
        rule.onNodeWithText("Datei neu wählen").performScrollTo().performClick()
        assertEquals(listOf(SourceAction.RepickFile), actions)
    }

    @Test
    fun offlineCopyOfTodayReadsVonHeute() {
        val today = LocalDateTime.of(LocalDate.now(), java.time.LocalTime.of(0, 33))
        val status = SyncStatus.OfflineCopy(today, OfflineReason.LocalFileNotFound)
        rule.setContent {
            KeyPassTheme {
                SettingsScreen(
                    state = SettingsState(
                        source = file,
                        sync = SyncState(status, kind = SourceKind.FILE),
                        sourceProblem = SourceProblem.FILE_NOT_FOUND,
                        fingerprintEnabled = false,
                        passwordDueOn = null,
                        passwordIntervalDays = 7,
                        autoLockSeconds = 120,
                        autofillSelected = true,
                        notificationsAllowed = true,
                        versionName = "0.1.0",
                    ),
                    onBack = {},
                    onEditSource = {},
                    onSyncNow = {},
                    onFingerprintChange = {},
                    onPasswordIntervalChange = {},
                    onAutoLockChange = {},
                    onOpenAutofillSettings = {},
                    onOpenNotificationSettings = {},
                    onOpenAbout = { aboutOpened++ },
                    onSourceAction = {},
                )
            }
        }
        rule.onNodeWithText(
            "Geöffnet ist die Offline-Kopie von heute, 00:33. Die Datei wurde verschoben, umbenannt oder gelöscht.",
        ).assertExists()
    }

    @Test
    fun fileNoAccessTitle() {
        settings(file, OfflineReason.LocalFileNoAccess, SourceProblem.FILE_NO_ACCESS)
        rule.onNodeWithText("Kein Zugriff mehr").assertExists()
        rule.onNodeWithText("Datei neu wählen").assertExists()
    }

    @Test
    fun smbLoginFailedOffersTheSmbFix() {
        settings(smb, OfflineReason.LoginFailed, SourceProblem.SMB_LOGIN_FAILED)
        rule.onNodeWithText("Anmeldung fehlgeschlagen").assertExists()
        rule.onNodeWithText(
            "Geöffnet ist die Offline-Kopie vom 03.01., 08:12. Der Server hat die Anmeldung von luca abgelehnt.",
        ).assertExists()
        rule.onNodeWithText("SMB-Freigabe ändern").performScrollTo().performClick()
        assertEquals(listOf(SourceAction.EditSmb), actions)
    }

    @Test
    fun otherFileProblemShowsOfflineCopyWithoutReasonOrFixButton() {
        settings(file, OfflineReason.LocalFileProblem)
        rule.onNodeWithText("Offline-Kopie").assertExists()
        rule.onNodeWithText("Geöffnet ist die Offline-Kopie vom 03.01., 08:12.").assertExists()
        rule.onAllNodesWithText("Datei neu wählen").assertCountEquals(0)
        rule.onAllNodesWithText("SMB-Freigabe ändern").assertCountEquals(0)
    }

    @Test
    fun otherServerHasNoFixButton() {
        settings(smb, OfflineReason.OtherServer)
        rule.onNodeWithText("Sync gestoppt: anderer Server").assertExists()
        rule.onAllNodesWithText("SMB-Freigabe ändern").assertCountEquals(0)
    }

    private fun unlock(state: UnlockState) {
        rule.setContent {
            KeyPassTheme {
                UnlockScreen(
                    state = state,
                    password = rememberSecretInput(),
                    onFingerprint = {},
                    onUsePassword = {},
                    onSubmitPassword = {},
                )
            }
        }
    }

    private fun unlockState(
        place: String?,
        modified: LocalDateTime?,
        kind: SourceKind = SourceKind.SMB,
        problem: UnlockProblem? = null,
        firstRun: FirstRunUnlock? = null,
    ) = UnlockState(
        fileName = "Passwoerter.kdbx",
        mode = UnlockMode.Password,
        fingerprintUsable = false,
        problem = problem,
        firstRun = firstRun,
        sourceKind = kind,
        place = place,
        modified = modified,
    )

    @Test
    fun unlockLineShowsPlaceAndDate() {
        unlock(unlockState("Interner Speicher / Syncthing / KeePass", LocalDateTime.of(2026, 10, 31, 21, 38), SourceKind.FILE))
        rule.onNodeWithText("Interner Speicher / Syncthing / KeePass · geändert 31.10.2026, 21:38").assertIsDisplayed()
    }

    @Test
    fun unlockLineDropsUnknownParts() {
        unlock(unlockState(null, LocalDateTime.of(2026, 10, 31, 21, 38)))
        rule.onNodeWithText("geändert 31.10.2026, 21:38").assertIsDisplayed()
    }

    @Test
    fun unlockLineWithPlaceOnly() {
        unlock(unlockState("\\\\nas.local\\daten\\KeePass", null))
        rule.onNodeWithText("\\\\nas.local\\daten\\KeePass").assertIsDisplayed()
    }

    @Test
    fun unlockProblemTextFollowsTheSourceType() {
        unlock(unlockState("x", null, SourceKind.FILE, UnlockProblem.NoDatabase))
        rule.onNodeWithText(
            "Es liegt noch keine Kopie der Datenbank auf diesem Gerät. Hol sie aus der Datei, danach lässt sich die App entsperren.",
        ).assertIsDisplayed()
    }

    @Test
    fun firstRunStepHasNoInfoCardAndNoSizeLine() {
        val first = FirstRunUnlock(SetupStep(3), 7, fingerprintWanted = true, fingerprintAvailable = true)
        unlock(unlockState("Interner Speicher", null, SourceKind.FILE, firstRun = first))
        rule.onAllNodes(hasText("Die App speichert", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("Vom Share geladen", substring = true)).assertCountEquals(0)
    }
}
