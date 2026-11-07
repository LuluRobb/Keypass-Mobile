package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import java.util.UUID

/** Which screen the app shows. Derived from the platform state by [computeRoute], never stored. */
sealed interface AppRoute {
    /** Source choice, server form or file browser of a source flow (setup steps 1 and 2, "Quelle verwalten"). */
    data class SourceFlow(val mode: SourceFlowMode, val screen: SourceFlowScreen) : AppRoute

    /** Setup step 4. */
    data object SystemSetup : AppRoute

    /** Unlock screen; [firstRun] is setup step 3. */
    data class Unlock(val firstRun: Boolean) : AppRoute

    data object EntryList : AppRoute

    data class EntryDetail(val id: UUID) : AppRoute

    data object Settings : AppRoute

    /** "Über KeyPass Mobile", opened from the settings. */
    data object About : AppRoute

    /** "Open-Source-Lizenzen", opened from the About screen. */
    data object Licenses : AppRoute

    /** The license page of one library ([id] is its unique id), opened from the licenses list. */
    data class LicenseDetail(val id: String) : AppRoute
}

/** What the user opened on top of the entry list while unlocked; the only navigation state kept by hand. */
sealed interface InAppScreen {
    data class Detail(val id: UUID) : InAppScreen

    data object Settings : InAppScreen

    /** "Über KeyPass Mobile" on top of the settings. */
    data object About : InAppScreen

    /** "Open-Source-Lizenzen" on top of the About screen. */
    data object Licenses : InAppScreen

    /** The license page of the library [id] on top of the licenses list. */
    data class LicenseDetail(val id: String) : InAppScreen

    /**
     * "Quelle verwalten" from the settings or the SMB banner action (a [SourceFlowMode.ChangeUnlocked]
     * flow). [start] is the first screen of the flow: Choose, or Server for "SMB-Freigabe ändern".
     */
    data class ChangeSource(val start: SourceFlowScreen) : InAppScreen
}

/** A source flow that takes the screen by itself: [SourceFlowMode.FirstRun] or [SourceFlowMode.ChangeLocked]. [screen] null = it shows nothing. */
data class ActiveFlow(val mode: SourceFlowMode, val screen: SourceFlowScreen?)

/**
 * The route for the given platform state, first match wins:
 * 1. an [ActiveFlow] that shows a screen: that screen
 * 2. no source: setup step 1 (the source choice)
 * 3. unlocked, setup not completed: setup step 4
 * 4. not unlocked (locked or unlocking): the unlock screen
 * 5. otherwise the top of [stack]; the entry list when it is empty
 *
 * [changeScreen] is the screen of the unlocked source change, used when [InAppScreen.ChangeSource] is on top
 * (before the flow exists, the start screen of that entry).
 */
fun computeRoute(
    unlocked: Boolean,
    hasSource: Boolean,
    setupCompleted: Boolean,
    flow: ActiveFlow?,
    stack: List<InAppScreen>,
    changeScreen: SourceFlowScreen?,
): AppRoute {
    val flowScreen = flow?.screen
    if (flow != null && flowScreen != null) return AppRoute.SourceFlow(flow.mode, flowScreen)
    if (!hasSource) return AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Choose)
    if (unlocked && !setupCompleted) return AppRoute.SystemSetup
    if (!unlocked) return AppRoute.Unlock(firstRun = !setupCompleted)
    return when (val top = stack.lastOrNull()) {
        null -> AppRoute.EntryList
        is InAppScreen.Detail -> AppRoute.EntryDetail(top.id)
        InAppScreen.Settings -> AppRoute.Settings
        InAppScreen.About -> AppRoute.About
        InAppScreen.Licenses -> AppRoute.Licenses
        is InAppScreen.LicenseDetail -> AppRoute.LicenseDetail(top.id)
        is InAppScreen.ChangeSource ->
            AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, changeScreen ?: top.start)
    }
}
