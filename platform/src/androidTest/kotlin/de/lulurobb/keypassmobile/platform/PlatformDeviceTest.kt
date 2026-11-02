package de.lulurobb.keypassmobile.platform

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.lulurobb.keypassmobile.core.settings.ClipboardTimeout
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardStatus
import de.lulurobb.keypassmobile.platform.clipboard.CopiedValue
import de.lulurobb.keypassmobile.platform.service.UnlockedNotification
import de.lulurobb.keypassmobile.platform.service.UnlockedService
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SessionState
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.PrefsSettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** The facade with its foreground service, notification and clipboard on the device. */
@RunWith(AndroidJUnit4::class)
class PlatformDeviceTest {

    private fun ourNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == UnlockedNotification.NOTIFICATION_ID }?.notification

    @Test
    fun foregroundServiceNotificationAndClipboardWhileUnlocked() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val platform = KpmPlatform.init(context)
        val offline = File(context.noBackupFilesDir, "sync/offline.kdbx")
        offline.parentFile!!.mkdirs()
        asset("argon2id-aes.kdbx").copyTo(offline, overwrite = true)
        try {
            val outcome = platform.unlockWithPassword(TEST_DB_PASSWORD.toCharArray())
            assertTrue("$outcome", outcome is UnlockOutcome.Unlocked)

            // SE-03: "KeyPass Mobile entsperrt" with "Sperren".
            assertTrue("notification shown", waitUntil { ourNotification() != null })
            val notification = ourNotification()!!
            assertEquals("KeyPass Mobile entsperrt", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Sperrt nach 2 Minuten ohne Nutzung.", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("Sperren", notification.actions.single().title.toString())
            assertTrue(platform.system.areNotificationsAllowed())

            // AF-09: copy, countdown, cleared on lock.
            val entry = platform.unlocked()!!.vault.entries.first()
            assertTrue(platform.copyUsername(entry.id))
            val holding = platform.clipboard.value
            assertTrue("$holding", holding is ClipboardStatus.Holding && holding.value == CopiedValue.USERNAME && holding.secondsLeft in 29..30)
            assertNotNull(platform.revealPassword(entry.id))

            // "Sperren" in the notification locks and removes the notification.
            notification.actions.single().actionIntent.send()
            assertTrue(waitUntil { platform.session.value is SessionState.Locked })
            assertEquals(LockReason.Notification, (platform.session.value as SessionState.Locked).reason)
            assertTrue(
                "clipboard cleared: ${platform.clipboard.value}",
                waitUntil { platform.clipboard.value == ClipboardStatus.Cleared(CopiedValue.USERNAME) },
            )
            assertTrue("notification removed", waitUntil { ourNotification() == null })
            assertEquals(null, platform.revealPassword(entry.id))
        } finally {
            platform.lock()
            File(context.noBackupFilesDir, "sync").deleteRecursively()
        }
    }

    @Test
    fun refreshNotificationChangesNothingWhileItIsShownOrLocked() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val platform = KpmPlatform.init(context)
        val offline = File(context.noBackupFilesDir, "sync/offline.kdbx")
        offline.parentFile!!.mkdirs()
        asset("argon2id-aes.kdbx").copyTo(offline, overwrite = true)
        try {
            // Locked: nothing to show, no service is started for it.
            platform.refreshNotification()
            Thread.sleep(500)
            assertEquals(null, ourNotification())
            assertTrue(!foregroundServiceRunning())

            val outcome = platform.unlockWithPassword(TEST_DB_PASSWORD.toCharArray())
            assertTrue("$outcome", outcome is UnlockOutcome.Unlocked)
            assertTrue("notification shown", waitUntil { ourNotification() != null })

            // Shown already (onResume of an app that was never blocked): nothing changes.
            platform.refreshNotification()
            Thread.sleep(500)
            assertEquals("KeyPass Mobile entsperrt", ourNotification()!!.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(foregroundServiceRunning())
            assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id == UnlockedNotification.NOTIFICATION_ID })

            platform.lock()
            assertTrue("notification removed", waitUntil { ourNotification() == null })
            assertTrue(waitUntil { !foregroundServiceRunning() })
            platform.refreshNotification()
            Thread.sleep(500)
            assertEquals(null, ourNotification())
            assertTrue(!foregroundServiceRunning())
        } finally {
            platform.lock()
            File(context.noBackupFilesDir, "sync").deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundServiceRunning(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == UnlockedService::class.java.name && it.foreground }

    @Test
    fun durationsAreGerman() {
        assertEquals("30 Sekunden", UnlockedNotification.formatDuration(context, IdleTimeout.SECONDS_30))
        assertEquals("1 Minute", UnlockedNotification.formatDuration(context, IdleTimeout.MINUTES_1))
        assertEquals("2 Minuten", UnlockedNotification.formatDuration(context, IdleTimeout.MINUTES_2))
        assertEquals("5 Minuten", UnlockedNotification.formatDuration(context, IdleTimeout.MINUTES_5))
    }

    @Test
    fun settingsSurviveReload() {
        val prefs = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val full = KpmSettings(
            source = SourceConfig("192.168.178.20", 4450, "WORKGROUP", "kpm", "Daten", listOf("Ärzte", "Passwörter.kdbx")),
            trustedServer = TrustedServer(UUID.fromString("74696572-6e61-2d6f-6e61-730000000000"), Instant.ofEpochMilli(1_761_900_000_000)),
            pendingServerGuid = UUID.fromString("9b2f6c1e-3d4a-4e5f-8a7b-0c1d2e3f4a5b"),
            fingerprintEnabled = true,
            passwordInterval = PasswordInterval.THREE_DAYS,
            idleTimeout = IdleTimeout.SECONDS_30,
            clipboardTimeout = ClipboardTimeout.SECONDS_60,
            setupCompleted = true,
            lastPasswordEntry = Instant.ofEpochMilli(1_761_990_000_123),
            intervalAtEntry = PasswordInterval.FOURTEEN_DAYS,
        )
        PrefsSettingsRepository(prefs).update { full }
        assertEquals(full, PrefsSettingsRepository(prefs).settings.value)

        val cleared = full.copy(source = null, trustedServer = null, pendingServerGuid = null, lastPasswordEntry = null, intervalAtEntry = null)
        PrefsSettingsRepository(prefs).update { cleared }
        assertEquals(cleared, PrefsSettingsRepository(prefs).settings.value)
        prefs.edit().clear().commit()
    }
}
