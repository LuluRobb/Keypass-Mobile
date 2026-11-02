package de.lulurobb.keypassmobile.platform

import android.app.Activity
import android.os.Bundle
import android.widget.EditText

/** Test window with a focused text field (soft keyboard open), see ClipboardFocusedDeviceTest. */
class FocusedActivity : Activity() {
    lateinit var field: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this)
        setContentView(field)
        field.requestFocus()
    }
}
