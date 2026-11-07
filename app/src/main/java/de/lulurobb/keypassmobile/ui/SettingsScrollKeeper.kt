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
 * Mobile" or "Quelle verwalten". The settings route runs inside
 * `holder.SaveableStateProvider(SettingsStateKey)`, which saves its scroll position when it leaves
 * and restores it when it returns.
 */
internal const val SettingsStateKey = "settings"

/**
 * True while the settings stay on the stack below [route] (or are the route): their saved state is
 * kept. On any other route (list, detail, unlock after a lock, setup) the settings are gone and the
 * next visit starts at the top.
 */
internal fun keepsSettingsState(route: AppRoute): Boolean =
    route == AppRoute.Settings ||
        route == AppRoute.About ||
        (route is AppRoute.SourceFlow && route.mode == SourceFlowMode.ChangeUnlocked)

/** The holder for [SettingsStateKey]; it forgets the saved settings state as soon as [route] leaves their stack. */
@Composable
internal fun rememberSettingsStateHolder(route: AppRoute): SaveableStateHolder {
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(route) {
        if (!keepsSettingsState(route)) holder.removeState(SettingsStateKey)
    }
    return holder
}
