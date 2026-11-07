package de.lulurobb.keypassmobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only hook for the emulator E2E test (lost access to a picked file): releases all persisted
 * URI read grants of the app. Trigger it with
 * adb shell am broadcast -a de.lulurobb.keypassmobile.debug.RELEASE_URI_GRANTS -n de.lulurobb.keypassmobile/.debug.ReleaseUriGrantsReceiver
 *
 * The manifest entry exists only in the debug source set and requires the permission DUMP, which the
 * adb shell holds and normal apps do not. Logs only a count, never a URI.
 */
class ReleaseUriGrantsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val resolver = context.contentResolver
        val grants = resolver.persistedUriPermissions
        var released = 0
        for (grant in grants) {
            val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            try {
                resolver.releasePersistableUriPermission(grant.uri, flags)
                released++
            } catch (e: SecurityException) {
                Log.w(TAG, "Grant could not be released: " + e.javaClass.simpleName)
            }
        }
        Log.i(TAG, "Released $released of ${grants.size} persisted URI grants")
    }

    companion object {
        const val ACTION = "de.lulurobb.keypassmobile.debug.RELEASE_URI_GRANTS"
        private const val TAG = "KpmE2eHooks"
    }
}
