package de.lulurobb.keypassmobile

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.lulurobb.keypassmobile.autofill.AutofillRequest
import de.lulurobb.keypassmobile.model.VaultEntry
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.state.FingerprintPrompter
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.ui.picker.PickerRoute
import de.lulurobb.keypassmobile.ui.picker.PickerViewModel
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import de.lulurobb.keypassmobile.ui.unlock.UnlockRoute

/**
 * Autofill picker (AF-03 to AF-07): started by the autofill service for the requesting app,
 * unlocks first when needed, lets the user pick an entry and returns it as the fill result.
 * See KpmAutofill in :autofill for the contract. Not exported; own window with FLAG_SECURE (SE-05).
 *
 * Every way out without a pick cancels the request: back, close button and leaving the
 * screen (onStop), so the picker never waits in the background.
 */
class AutofillPickerActivity : ComponentActivity() {

    private lateinit var request: AutofillRequest
    private var model: PickerViewModel? = null

    private var resumed by mutableStateOf(false)

    /** The system fingerprint prompt is up; the activity may stop while it shows. */
    private var promptShowing = false
    private var stopped = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        request = AutofillRequest.from(this, intent) ?: return AutofillRequest.cancel(this)
        val settings = KpmPlatform.get(this).settings.value
        if (!settings.setupCompleted || settings.source == null) {
            Toast.makeText(this, R.string.picker_not_set_up, Toast.LENGTH_LONG).show()
            return AutofillRequest.cancel(this)
        }

        val model = ViewModelProvider(
            this,
            viewModelFactory { initializer { PickerViewModel(application, request) } },
        )[PickerViewModel::class.java]
        this.model = model
        model.fillHandler = ::fill

        val prompter = guarded(model.platform.prompter(this))
        setContent {
            KeyPassTheme {
                val session by model.platform.session.collectAsState()
                BackHandler { cancelRequest() }
                if (session is SessionView.Unlocked) {
                    PickerRoute(model.picker, onClose = ::cancelRequest)
                } else {
                    UnlockRoute(
                        model = model.unlock,
                        messages = model.messages,
                        resumed = resumed,
                        prompter = prompter,
                        onBack = null,
                        onCancelFill = ::cancelRequest,
                        // The source cannot be changed from here: the request ends and the app opens.
                        onChooseSource = ::openApp,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        model?.platform?.checkIdle()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        stopped = true
        super.onStop()
        // The biometric dialog may stop the activity for a moment: the prompt wrapper decides then.
        if (!isChangingConfigurations && !isFinishing && !promptShowing) cancelRequest()
    }

    override fun onDestroy() {
        model?.fillHandler = null
        super.onDestroy()
    }

    // SE-02: every touch and key counts as use for the idle timer.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        model?.platform?.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        model?.platform?.touch()
        return super.dispatchKeyEvent(event)
    }

    private fun cancelRequest() = AutofillRequest.cancel(this)

    /** Ends the request like a cancel and brings the app to the front (own task, the picker has none). */
    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        cancelRequest()
    }

    /**
     * Marks the prompt as showing, so onStop does not cancel the request meanwhile. When the
     * prompt ends while the activity is already stopped (home during the prompt), the
     * request is cancelled then.
     */
    private fun guarded(prompter: FingerprintPrompter) = FingerprintPrompter {
        promptShowing = true
        try {
            prompter.prompt()
        } finally {
            promptShowing = false
            if (stopped && !isChangingConfigurations && !isFinishing) cancelRequest()
        }
    }

    /** An entry was picked (and confirmed for a mismatching web page): hand its values to the system. */
    private fun fill(entry: VaultEntry) {
        // Null when the session was locked meanwhile: the session flow already shows the unlock.
        val password = model?.platform?.revealPassword(entry.id) ?: return
        try {
            setResult(RESULT_OK, request.buildResultIntent(this, entry.username, password))
        } finally {
            password.fill('\u0000')
        }
        finish()
    }
}
