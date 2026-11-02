package de.lulurobb.keypassmobile.platform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.platform.R

/** The notification "KeyPass Mobile entsperrt" with the action "Sperren" (SE-03). */
internal object UnlockedNotification {
    const val CHANNEL_ID = "platform_unlocked"
    const val NOTIFICATION_ID = 0x4B504D // "KPM"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.platform_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.platform_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** The notification is among the app's visible ones (false while notifications are blocked). */
    fun isShown(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty().any { it.id == NOTIFICATION_ID }
    } catch (_: Exception) {
        false
    }

    fun build(context: Context, idleTimeout: IdleTimeout): Notification {
        val lockIntent = PendingIntent.getService(
            context,
            REQUEST_LOCK,
            Intent(context, UnlockedService::class.java).setAction(UnlockedService.ACTION_LOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.platform_ic_lock)
            .setContentTitle(context.getString(R.string.platform_notification_title))
            .setContentText(context.getString(R.string.platform_notification_text, formatDuration(context, idleTimeout)))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.platform_notification_action_lock), lockIntent).build(),
            )
        // Tap opens the app (its launcher activity).
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            builder.setContentIntent(
                PendingIntent.getActivity(context, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        return builder.build()
    }

    /** "30 Sekunden", "1 Minute", "2 Minuten". */
    fun formatDuration(context: Context, timeout: IdleTimeout): String {
        val seconds = timeout.duration.seconds.toInt()
        return if (seconds < 60 || seconds % 60 != 0) {
            context.resources.getQuantityString(R.plurals.platform_duration_seconds, seconds, seconds)
        } else {
            val minutes = seconds / 60
            context.resources.getQuantityString(R.plurals.platform_duration_minutes, minutes, minutes)
        }
    }

    private const val REQUEST_LOCK = 1
    private const val REQUEST_OPEN = 2
}
