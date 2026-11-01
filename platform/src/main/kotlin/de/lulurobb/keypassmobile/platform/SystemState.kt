package de.lulurobb.keypassmobile.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.biometric.FingerprintPrompt
import de.lulurobb.keypassmobile.platform.service.UnlockedNotification

/**
 * System settings the app cannot change itself (UI-06, UI-03). Query them
 * again when the user returns from the system page (onResume).
 */
class SystemState internal constructor(private val context: Context) {

    /** Runtime permission to request with the activity result API before falling back to [notificationSettingsIntent]. */
    val notificationPermission: String = Manifest.permission.POST_NOTIFICATIONS

    /** Strong biometrics usable (EN-02), for the fingerprint switch. */
    fun fingerprintAvailability(): FingerprintAvailability = FingerprintPrompt.availability(context)

    /** KeyPass Mobile is the selected autofill service (AF-01). */
    fun isAutofillServiceSelected(): Boolean = try {
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    } catch (_: Exception) {
        false
    }

    /**
     * POST_NOTIFICATIONS granted and the "Entsperrt" channel not switched off,
     * so "KeyPass Mobile entsperrt" is visible (SE-03).
     */
    fun areNotificationsAllowed(): Boolean {
        if (context.checkSelfPermission(notificationPermission) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(UnlockedNotification.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Opens the system dialog to choose KeyPass Mobile as autofill service.
     * Start it from an Activity; falls back to the general settings if the
     * device has no such page.
     */
    fun autofillSettingsIntent(): Intent {
        val request = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .setData(Uri.parse("package:" + context.packageName))
        return if (request.resolveActivity(context.packageManager) != null) request else Intent(Settings.ACTION_SETTINGS)
    }

    /** The app's notification settings page. */
    fun notificationSettingsIntent(): Intent {
        UnlockedNotification.ensureChannel(context)
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}
