package de.lulurobb.keypassmobile.ui.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.ui.common.SourceAction
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Texts and action callbacks of every list banner (UI-09, EN-06, DS-04). */
@RunWith(AndroidJUnit4::class)
class ListBannerTest {

    @get:Rule
    val rule = createComposeRule()

    // A day that is not today, so the stamp always reads "03.01., 08:12".
    private val copy = LocalDateTime.of(2020, 1, 3, 8, 12)
    private val stamp = "03.01., 08:12"

    private var details = 0
    private var dismissed = 0
    private val actions = mutableListOf<SourceAction>()

    private fun show(banner: ListBanner) {
        rule.setContent {
            KeyPassTheme {
                ListBannerNotice(
                    banner,
                    onDetails = { details++ },
                    onDismiss = { dismissed++ },
                    onSourceAction = { actions += it },
                )
            }
        }
    }

    private fun assertTexts(title: String, text: String, button: String) {
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithText(button).assertIsDisplayed()
    }

    @Test
    fun fileNotFound() {
        show(ListBanner.SourceMissing(SourceProblem.FILE_NOT_FOUND, copy, null))
        assertTexts(
            "Datei nicht gefunden",
            "Geöffnet ist die Offline-Kopie vom $stamp. Wähl die Datei neu, damit die App wieder abgleichen kann.",
            "Datei neu wählen",
        )
        rule.onNodeWithText("Datei neu wählen").performClick()
        assertEquals(listOf(SourceAction.RepickFile), actions)
    }

    @Test
    fun fileNoAccess() {
        show(ListBanner.SourceMissing(SourceProblem.FILE_NO_ACCESS, copy, null))
        assertTexts(
            "Kein Zugriff mehr auf die Datei",
            "Geöffnet ist die Offline-Kopie vom $stamp. Wähl die Datei neu, damit die App wieder abgleichen kann.",
            "Datei neu wählen",
        )
        rule.onNodeWithText("Datei neu wählen").performClick()
        assertEquals(listOf(SourceAction.RepickFile), actions)
    }

    @Test
    fun fileBannerWithoutDateDropsTheDatePart() {
        show(ListBanner.SourceMissing(SourceProblem.FILE_NOT_FOUND, null, null))
        rule.onNodeWithText("Geöffnet ist die Offline-Kopie. Wähl die Datei neu, damit die App wieder abgleichen kann.")
            .assertIsDisplayed()
    }

    @Test
    fun smbNotFound() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_NOT_FOUND, copy, "kpmtest"))
        assertTexts(
            "Datei nicht gefunden",
            "Auf dem Share liegt sie nicht mehr. Geöffnet ist die Offline-Kopie vom $stamp. Wähl die Datei neu, damit die App wieder abgleichen kann.",
            "Datei neu wählen",
        )
        rule.onNodeWithText("Datei neu wählen").performClick()
        assertEquals(listOf(SourceAction.EditSmb), actions)
    }

    @Test
    fun smbNoAccess() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_NO_ACCESS, copy, "kpmtest"))
        assertTexts(
            "Kein Zugriff auf die Datei",
            "Der Server lässt kpmtest die Datei nicht mehr lesen. Geöffnet ist die Offline-Kopie vom $stamp. Prüf die Rechte auf dem Share oder wähl eine andere Datei.",
            "SMB-Freigabe ändern",
        )
        rule.onNodeWithText("SMB-Freigabe ändern").performClick()
        assertEquals(listOf(SourceAction.EditSmb), actions)
    }

    @Test
    fun smbLoginFailed() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_LOGIN_FAILED, copy, "kpmtest"))
        assertTexts(
            "Anmeldung fehlgeschlagen",
            "Der Server hat die Anmeldung von kpmtest abgelehnt. Geöffnet ist die Offline-Kopie vom $stamp. Gib die Zugangsdaten neu ein, damit die App wieder abgleichen kann.",
            "SMB-Freigabe ändern",
        )
        rule.onNodeWithText("SMB-Freigabe ändern").performClick()
        assertEquals(listOf(SourceAction.EditSmb), actions)
    }

    @Test
    fun smbBannersWithoutDateDropTheDatePart() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_NO_ACCESS, null, "kpmtest"))
        rule.onNodeWithText(
            "Der Server lässt kpmtest die Datei nicht mehr lesen. Geöffnet ist die Offline-Kopie. Prüf die Rechte auf dem Share oder wähl eine andere Datei.",
        ).assertIsDisplayed()
    }

    @Test
    fun otherServerKeepsItsDetailsButton() {
        show(ListBanner.OtherServer("10.0.0.5"))
        assertTexts(
            "Sync gestoppt: anderer Server",
            "Unter 10.0.0.5 antwortet ein anderer Server. Die App hat sich dort nicht angemeldet.",
            "Details",
        )
        rule.onNodeWithText("Details").performClick()
        assertEquals(1, details)
        assertEquals(emptyList<SourceAction>(), actions)
    }

    @Test
    fun rejectedVersionOfAShare() {
        show(ListBanner.NewVersionRejected(copy, SourceKind.SMB))
        assertTexts(
            "Neue Version ließ sich nicht öffnen",
            "Die Datei vom Share passt nicht zu diesem Master-Passwort. Geöffnet ist die Offline-Kopie vom $stamp. Hast du das Passwort am PC geändert, sperr die App und entsperr sie mit dem neuen.",
            "OK",
        )
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun rejectedVersionOfAFile() {
        show(ListBanner.NewVersionRejected(copy, SourceKind.FILE))
        rule.onNodeWithText(
            "Die neue Version der Datei passt nicht zu diesem Master-Passwort. Geöffnet ist die Offline-Kopie vom $stamp. Hast du das Passwort am PC geändert, sperr die App und entsperr sie mit dem neuen.",
        ).assertIsDisplayed()
    }

    @Test
    fun rejectedVersionOfAFileWithoutDate() {
        show(ListBanner.NewVersionRejected(null, SourceKind.FILE))
        rule.onNodeWithText(
            "Die neue Version der Datei passt nicht zu diesem Master-Passwort. Geöffnet ist die Offline-Kopie. Hast du das Passwort am PC geändert, sperr die App und entsperr sie mit dem neuen.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Details").assertDoesNotExist()
    }

    // ---- today: "von heute, 00:33", never "vom 00:33" ----

    private val today = LocalDateTime.of(LocalDate.now(), LocalTime.of(0, 33))

    private fun assertBodyText(text: String) {
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun fileBannerOfTodayReadsVonHeute() {
        show(ListBanner.SourceMissing(SourceProblem.FILE_NOT_FOUND, today, null))
        assertBodyText("Geöffnet ist die Offline-Kopie von heute, 00:33. Wähl die Datei neu, damit die App wieder abgleichen kann.")
    }

    @Test
    fun smbBannersOfTodayReadVonHeute() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_NOT_FOUND, today, "kpmtest"))
        assertBodyText(
            "Auf dem Share liegt sie nicht mehr. Geöffnet ist die Offline-Kopie von heute, 00:33. Wähl die Datei neu, damit die App wieder abgleichen kann.",
        )
    }

    @Test
    fun smbUserBannersOfTodayReadVonHeute() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_NO_ACCESS, today, "kpmtest"))
        assertBodyText(
            "Der Server lässt kpmtest die Datei nicht mehr lesen. Geöffnet ist die Offline-Kopie von heute, 00:33. Prüf die Rechte auf dem Share oder wähl eine andere Datei.",
        )
    }

    @Test
    fun smbLoginBannerOfTodayReadsVonHeute() {
        show(ListBanner.SourceMissing(SourceProblem.SMB_LOGIN_FAILED, today, "kpmtest"))
        assertBodyText(
            "Der Server hat die Anmeldung von kpmtest abgelehnt. Geöffnet ist die Offline-Kopie von heute, 00:33. Gib die Zugangsdaten neu ein, damit die App wieder abgleichen kann.",
        )
    }

    @Test
    fun rejectedVersionBannersOfTodayReadVonHeute() {
        show(ListBanner.NewVersionRejected(today, SourceKind.FILE))
        assertBodyText(
            "Die neue Version der Datei passt nicht zu diesem Master-Passwort. Geöffnet ist die Offline-Kopie von heute, 00:33. Hast du das Passwort am PC geändert, sperr die App und entsperr sie mit dem neuen.",
        )
    }

    @Test
    fun rejectedVersionBannerOfAShareToday() {
        show(ListBanner.NewVersionRejected(today, SourceKind.SMB))
        assertBodyText(
            "Die Datei vom Share passt nicht zu diesem Master-Passwort. Geöffnet ist die Offline-Kopie von heute, 00:33. Hast du das Passwort am PC geändert, sperr die App und entsperr sie mit dem neuen.",
        )
    }
}
