package de.lulurobb.keypassmobile.ui

import de.lulurobb.keypassmobile.state.AppRoute
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsScrollKeeperTest {

    @Test
    fun `the settings state is kept on the settings and on the screens opened from them`() {
        assertTrue(keepsSettingsState(AppRoute.Settings))
        assertTrue(keepsSettingsState(AppRoute.About))
        assertTrue(keepsSettingsState(AppRoute.Licenses))
        assertTrue(keepsSettingsState(AppRoute.LicenseDetail("com.hierynomus:smbj")))
        assertTrue(keepsSettingsState(AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Choose)))
        assertTrue(keepsSettingsState(AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Browse)))
    }

    @Test
    fun `the about state is kept on the about screen and on the licenses pages only`() {
        assertTrue(keepsAboutState(AppRoute.About))
        assertTrue(keepsAboutState(AppRoute.Licenses))
        assertTrue(keepsAboutState(AppRoute.LicenseDetail("a")))
        assertFalse(keepsAboutState(AppRoute.Settings))
        assertFalse(keepsAboutState(AppRoute.EntryList))
        assertFalse(keepsAboutState(AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Choose)))
        assertFalse(keepsAboutState(AppRoute.Unlock(firstRun = false)))
    }

    @Test
    fun `the licenses list state is kept on the list and on a license page only`() {
        assertTrue(keepsLicensesState(AppRoute.Licenses))
        assertTrue(keepsLicensesState(AppRoute.LicenseDetail("a")))
        assertFalse(keepsLicensesState(AppRoute.About))
        assertFalse(keepsLicensesState(AppRoute.Settings))
        assertFalse(keepsLicensesState(AppRoute.EntryDetail(UUID.randomUUID())))
    }

    @Test
    fun `every other route drops it`() {
        assertFalse(keepsSettingsState(AppRoute.EntryList))
        assertFalse(keepsSettingsState(AppRoute.EntryDetail(UUID.randomUUID())))
        assertFalse(keepsSettingsState(AppRoute.Unlock(firstRun = false)))
        assertFalse(keepsSettingsState(AppRoute.Unlock(firstRun = true)))
        assertFalse(keepsSettingsState(AppRoute.SystemSetup))
        assertFalse(keepsSettingsState(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Choose)))
        assertFalse(keepsSettingsState(AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Choose)))
    }
}
