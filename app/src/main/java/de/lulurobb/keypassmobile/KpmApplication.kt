package de.lulurobb.keypassmobile

import android.app.Application
import de.lulurobb.keypassmobile.autofill.KpmAutofill
import de.lulurobb.keypassmobile.platform.KpmPlatform

/** Initializes the platform facade and connects the autofill service to the session timer. */
class KpmApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val platform = KpmPlatform.init(this)
        KpmAutofill.onSuggestionOffered = { platform.touch() }
    }
}
