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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.state.AppViewModel
import de.lulurobb.keypassmobile.ui.AppRoot
import de.lulurobb.keypassmobile.ui.theme.KeyPassTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SE-05: no screenshots and no preview in the recents, also for dialogs and popups.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val prompter = vm.adapter.prompter(this)
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
}
