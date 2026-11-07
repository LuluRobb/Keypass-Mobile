package de.lulurobb.keypassmobile.ui.common

/**
 * What the button of a source problem does (UI-09): the list banner and the sync
 * status in the settings offer the same action. The screens only report the
 * action; the app root decides how to run it (navigation is not a screen's job).
 */
enum class SourceAction {
    /**
     * Pick the file again with the system file picker, starting in the folder of the
     * current file (DS-10). Runs like a source change (DS-13, DS-14).
     */
    RepickFile,

    /**
     * Open the SMB server form for the current source. The password field is empty
     * (DS-03). Back returns to the screen the action came from.
     */
    EditSmb,
}
