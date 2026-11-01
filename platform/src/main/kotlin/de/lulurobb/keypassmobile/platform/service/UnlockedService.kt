package de.lulurobb.keypassmobile.platform.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import de.lulurobb.keypassmobile.platform.KpmPlatform
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service while unlocked (SE-03), type specialUse. It keeps the
 * process out of the cached-app freezer, so the idle lock (SE-02) and the
 * clipboard clearing (AF-09) of the platform keep running in the background,
 * locks when the app is swiped away (SE-01) and when the screen turns on
 * after the idle time passed in deep sleep. Shows "KeyPass Mobile entsperrt"
 * with "Sperren".
 *
 * Started by the platform on unlock; it stops itself when the session is
 * locked. It always calls startForeground first, so a lock right after the
 * start never breaks the startForegroundService contract.
 */
class UnlockedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var screenReceiverRegistered = false

    /** Id of the newest start request; stopping with it keeps a newer pending start alive. */
    private var lastStartId = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            KpmPlatform.get(context).checkIdle()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val platform = KpmPlatform.get(this)
        if (intent?.action == ACTION_LOCK) {
            platform.lock(LockReason.Notification)
            stop()
            return START_NOT_STICKY
        }
        UnlockedNotification.ensureChannel(this)
        val notification = UnlockedNotification.build(this, platform.settings.value.idleTimeout)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(UnlockedNotification.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // Android 13 has no service types to declare at runtime.
                startForeground(UnlockedNotification.NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Not allowed now (e.g. started from the background): the platform's timers still run.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (platform.session.value is SessionState.Locked) {
            stop()
            return START_NOT_STICKY
        }
        registerScreenReceiver()
        if (observer == null) {
            observer = scope.launch {
                launch {
                    platform.session.collect { state ->
                        if (state is SessionState.Locked) stop()
                    }
                }
                launch {
                    platform.settings.map { it.idleTimeout }.distinctUntilChanged().collect { timeout ->
                        if (platform.session.value is SessionState.Unlocked) {
                            getSystemService(android.app.NotificationManager::class.java)?.notify(
                                UnlockedNotification.NOTIFICATION_ID,
                                UnlockedNotification.build(this@UnlockedService, timeout),
                            )
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** The user swiped the app away from the recents (SE-01). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        KpmPlatform.get(this).lock(LockReason.TaskRemoved)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        unregisterScreenReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        }
        screenReceiverRegistered = false
    }

    internal companion object {
        const val ACTION_LOCK = "de.lulurobb.keypassmobile.platform.action.LOCK"

        /** Starts the service; failures (background start not allowed) are ignored. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, UnlockedService::class.java))
            } catch (_: Exception) {
                // ForegroundServiceStartNotAllowedException or similar: timers run in-process anyway.
            }
        }
    }
}
