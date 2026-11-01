package de.lulurobb.keypassmobile.autofill

/**
 * Contract between the autofill service of this library and the app.
 *
 * Picker activity (to be written in :app):
 * - Class [PICKER_ACTIVITY_CLASS] in the app package. The service starts it
 *   through the authentication IntentSender of the "KeyPass Mobile"
 *   suggestion; the requesting app starts it for result in its own task.
 * - Manifest: android:exported="false" (the PendingIntent runs with our
 *   identity, so no other app can start the picker with forged extras),
 *   android:excludeFromRecents="true" and android:taskAffinity="" so it never
 *   shows up as a task of its own. Set FLAG_SECURE on its window (SE-05).
 * - onCreate: AutofillRequest.from(this, intent). Null means the intent is not
 *   from the service: call AutofillRequest.cancel(this) and return.
 * - Header: AutofillRequest.target (domain or package, AF-06, AF-07) and
 *   AutofillRequest.browserLabel.
 * - Entry picked: setResult(RESULT_OK, request.buildResultIntent(this, username, password)),
 *   then wipe the password array and call finish().
 * - Closed without a pick (back, close button, leaving the screen):
 *   AutofillRequest.cancel(this).
 * - The intent survives configuration changes and process death, so calling
 *   AutofillRequest.from again in a recreated activity is fine.
 */
object KpmAutofill {

    /** Fully qualified class name of the picker activity in :app. */
    const val PICKER_ACTIVITY_CLASS = "de.lulurobb.keypassmobile.AutofillPickerActivity"

    /**
     * Optional hook for SE-02: called on the main thread each time the service
     * offers the "KeyPass Mobile" suggestion for a login form of another app,
     * so the app can count it as use. Carries no data about the form. Must
     * return quickly and must not throw.
     */
    @Volatile
    var onSuggestionOffered: (() -> Unit)? = null
}
