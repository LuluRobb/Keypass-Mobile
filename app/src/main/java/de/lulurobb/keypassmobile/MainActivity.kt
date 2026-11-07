package de.lulurobb.keypassmobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.state.AppViewModel
import de.lulurobb.keypassmobile.ui.AppRoot
import de.lulurobb.keypassmobile.ui.setup.OpenKdbxDocument
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme
import kotlinx.coroutines.launch

/**
 * The one activity of the app. All state and navigation live in [AppViewModel]
 * (see AppController); this class only hosts the Compose content and does what needs an
 * Activity: window flags, the idle timer, the system pages and the permission request.
 *
 * Launch mode is singleTop: the notification tap starts the launcher intent (in debug builds
 * it resolves to MAIN/INFO and would stack a second instance on top of this one). The open
 * screen lives in the ViewModel, so onNewIntent has nothing to do.
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels {
        val app = applicationContext
        viewModelFactory {
            initializer { AppViewModel(app, KpmPlatform.get(app)) }
        }
    }

    /** True between onResume and onPause: the fingerprint prompt opens only in the foreground (EN-02). */
    private var resumed by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotificationRequest()
    }

    /** The system file picker for the database file (DS-08); the result goes to the file pick controller. */
    private val pickDocument = registerForActivityResult(OpenKdbxDocument()) { uri ->
        vm.controller.filePick.onPicked(uri?.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SE-05: no screenshots and no preview in the recents, also for dialogs and popups.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val prompter = vm.adapter.prompter(this)
        // After a process death the picker result arrives without a pick in the new process: D10 needs the launch mode.
        vm.controller.filePick.restoreLaunchState(savedInstanceState?.getString(STATE_PICK_MODE))
        launchPickerRequests()
        setContent {
            KeyPassTheme {
                AppRoot(
                    controller = vm.controller,
                    resumed = resumed,
                    prompter = prompter,
                    onOpenAutofillSettings = ::openAutofillSettings,
                    onRequestNotifications = ::requestNotifications,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onExit = { moveTaskToBack(true) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.controller.platform.checkIdle()
        // Notifications may have been allowed in the system settings meanwhile.
        vm.controller.platform.refreshNotification()
        resumed = true
        // The user may come back from the system pages.
        vm.controller.refreshSystem()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Not for a rotation: the user is still in the app then.
        if (!isChangingConfigurations) vm.controller.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Saved while the system picker is open (the activity is stopped then): if the process dies meanwhile,
        // the result arrives in a new process that does not know how the pick started (D10).
        vm.controller.filePick.launchState()?.let { outState.putString(STATE_PICK_MODE, it) }
    }

    // The notification tap (the launcher intent) may arrive here; the open screen lives in the ViewModel.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    // SE-02: every touch and key counts as use, also the ones that no screen handles.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        vm.controller.platform.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        vm.controller.platform.touch()
        return super.dispatchKeyEvent(event)
    }

    /**
     * Opens the system picker for every request of the file pick controller while the activity is
     * started. A request that arrives in the background waits until the activity is visible again.
     */
    private fun launchPickerRequests() {
        val filePick = vm.controller.filePick
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                filePick.request.collect { request ->
                    if (request == null) return@collect
                    filePick.onLaunched(request.id)
                    try {
                        pickDocument.launch(request.initialUri)
                    } catch (_: ActivityNotFoundException) {
                        // A device without a file picker: like going back, no message.
                        filePick.onPicked(null)
                    }
                }
            }
        }
    }

    private fun openAutofillSettings() = start(vm.system.autofillSettingsIntent())

    private fun openNotificationSettings() = start(vm.system.notificationSettingsIntent())

    /** Asks for the permission; when it is (still) not enough, the notification settings page opens. */
    private fun requestNotifications() {
        if (checkSelfPermission(vm.system.notificationPermission) == PackageManager.PERMISSION_GRANTED) {
            afterNotificationRequest()
        } else {
            notificationPermission.launch(vm.system.notificationPermission)
        }
    }

    private fun afterNotificationRequest() {
        vm.controller.refreshSystem()
        if (!vm.controller.platform.areNotificationsAllowed()) openNotificationSettings()
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // A device without that settings page: nothing to open.
        }
    }

    private companion object {
        /** Instance state key: launch mode of the file pick in progress (FilePickController.launchState). */
        const val STATE_PICK_MODE = "filePickMode"
    }
}
