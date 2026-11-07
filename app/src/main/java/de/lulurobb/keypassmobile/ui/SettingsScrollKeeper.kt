package de.lulurobb.keypassmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import de.lulurobb.keypassmobile.state.AppRoute
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode

/**
 * [AppRoot] shows one screen at a time, so a screen below another one leaves the composition and
 * would lose its remembered state: the settings would come back at the top after "Über KeyPass
 * Mobile" or "Quelle verwalten", the About screen after "Open-Source-Lizenzen", the licenses list
 * after a detail page. These routes run inside `holder.SaveableStateProvider(<key>)`, which saves
 * their scroll position when they leave and restores it when they return.
 */
internal const val SettingsStateKey = "settings"
internal const val AboutStateKey = "about"
internal const val LicensesStateKey = "licenses"

/**
 * True while the settings stay on the stack below [route] (or are the route): their saved state is
 * kept. On any other route (list, detail, unlock after a lock, setup) the settings are gone and the
 * next visit starts at the top.
 */
internal fun keepsSettingsState(route: AppRoute): Boolean =
    route == AppRoute.Settings ||
        keepsAboutState(route) ||
        (route is AppRoute.SourceFlow && route.mode == SourceFlowMode.ChangeUnlocked)

/** True while the About screen is the route or stays on the stack below the licenses pages. */
internal fun keepsAboutState(route: AppRoute): Boolean =
    route == AppRoute.About || keepsLicensesState(route)

/** True while the licenses list is the route or stays on the stack below a detail page. */
internal fun keepsLicensesState(route: AppRoute): Boolean =
    route == AppRoute.Licenses || route is AppRoute.LicenseDetail

/** The holder for [SettingsStateKey], [AboutStateKey] and [LicensesStateKey]; each state is forgotten as soon as [route] leaves its stack. */
@Composable
internal fun rememberScreenStateHolder(route: AppRoute): SaveableStateHolder {
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(route) {
        if (!keepsSettingsState(route)) holder.removeState(SettingsStateKey)
        if (!keepsAboutState(route)) holder.removeState(AboutStateKey)
        if (!keepsLicensesState(route)) holder.removeState(LicensesStateKey)
    }
    return holder
}
