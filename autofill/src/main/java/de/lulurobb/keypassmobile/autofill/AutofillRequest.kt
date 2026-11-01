package de.lulurobb.keypassmobile.autofill

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import de.lulurobb.keypassmobile.autofill.form.FillPlan
import de.lulurobb.keypassmobile.autofill.form.fillTargetOf
import de.lulurobb.keypassmobile.model.FillTarget
import java.util.UUID

/**
 * One fill request as seen by the picker activity of :app: where to fill and
 * which visible fields get the values. Carried in the extras of the intent
 * that opens the picker; see [KpmAutofill] for the full calling contract.
 *
 * ```
 * val request = AutofillRequest.from(this, intent) ?: return AutofillRequest.cancel(this)
 * // ... user picks an entry ...
 * val password = vault.revealPassword(entry.id)
 * try {
 *     setResult(Activity.RESULT_OK, request.buildResultIntent(this, entry.username, password))
 * } finally {
 *     password.fill('\u0000')
 * }
 * finish()
 * ```
 */
class AutofillRequest private constructor(
    /** Web page (host and browser package) or app (package and label), AF-05 to AF-07. */
    val target: FillTarget,
    /** Label of the browser for web targets ("Brave"); null for apps or when unknown. */
    val browserLabel: String?,
    /** Scheme of the web page ("https") when the browser reported it; null for apps. */
    val webScheme: String?,
    private val usernameId: AutofillId?,
    private val passwordId: AutofillId?,
) {
    /** True when a visible username field was found. */
    val fillsUsername: Boolean get() = usernameId != null

    /** True when a visible password field was found. */
    val fillsPassword: Boolean get() = passwordId != null

    /**
     * Result intent for the picked entry. The activity passes it to
     * setResult(RESULT_OK, intent) and then calls finish(); the system fills
     * the values into the requesting app at once (AF-04).
     *
     * Empty values are not filled, so an entry without user name keeps what
     * the user typed. If nothing is left to fill, the intent carries no
     * dataset and the system fills nothing.
     *
     * The caller still owns [password] and wipes it right after this call:
     * the values are copied, because the system parcels the result only when
     * the activity finishes. The copy is a String, which the autofill
     * framework needs anyway (AutofillValue holds a CharSequence that is
     * written to the parcel as a String).
     */
    fun buildResultIntent(context: Context, username: String?, password: CharArray?): Intent {
        val intent = Intent()
        val fields = buildList {
            if (usernameId != null && !username.isNullOrEmpty()) {
                add(usernameId to AutofillValue.forText(username))
            }
            if (passwordId != null && password != null && password.isNotEmpty()) {
                add(passwordId to AutofillValue.forText(String(password)))
            }
        }
        if (fields.isEmpty()) return intent

        val presentations = Presentations.Builder()
            .setMenuPresentation(SuggestionViews.menuPresentation(context))
            .build()
        val dataset = Dataset.Builder(presentations).apply {
            fields.forEach { (id, value) -> setField(id, Field.Builder().setValue(value).build()) }
        }.build()
        return intent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
    }

    companion object {
        private const val EXTRA_PREFIX = "de.lulurobb.keypassmobile.autofill.extra."
        private const val EXTRA_PACKAGE = EXTRA_PREFIX + "PACKAGE"
        private const val EXTRA_WEB_DOMAIN = EXTRA_PREFIX + "WEB_DOMAIN"
        private const val EXTRA_WEB_SCHEME = EXTRA_PREFIX + "WEB_SCHEME"
        private const val EXTRA_USERNAME_ID = EXTRA_PREFIX + "USERNAME_ID"
        private const val EXTRA_PASSWORD_ID = EXTRA_PREFIX + "PASSWORD_ID"

        /**
         * Reads the request from the intent that started the picker. Returns
         * null when the intent was not created by the service. Looks up the
         * app or browser label; the requesting app is visible to us without
         * QUERY_ALL_PACKAGES because it started the picker for result.
         */
        @JvmStatic
        fun from(context: Context, intent: Intent?): AutofillRequest? {
            if (intent == null) return null
            val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotEmpty() } ?: return null
            val usernameId = intent.getParcelableExtra(EXTRA_USERNAME_ID, AutofillId::class.java)
            val passwordId = intent.getParcelableExtra(EXTRA_PASSWORD_ID, AutofillId::class.java)
            if (usernameId == null && passwordId == null) return null

            val label = appLabel(context.packageManager, packageName)
            val target = fillTargetOf(packageName, intent.getStringExtra(EXTRA_WEB_DOMAIN), label)
            val isWeb = target is FillTarget.Web
            return AutofillRequest(
                target = target,
                browserLabel = if (isWeb) label else null,
                webScheme = if (isWeb) intent.getStringExtra(EXTRA_WEB_SCHEME) else null,
                usernameId = usernameId,
                passwordId = passwordId,
            )
        }

        /**
         * Closes the picker without filling: setResult(RESULT_CANCELED) and
         * finish(). The suggestion stays available, so the user can tap it again.
         */
        @JvmStatic
        fun cancel(activity: Activity) {
            activity.setResult(Activity.RESULT_CANCELED)
            activity.finish()
        }

        /**
         * IntentSender of the suggestion: opens the picker with [plan] in the
         * extras. Immutable, so nobody can change the extras; unique per
         * response (intent identifier), so an old suggestion can never open
         * the picker with the data of a newer request.
         */
        internal fun authentication(context: Context, plan: FillPlan<AutofillId>): IntentSender {
            val intent = Intent()
                .setClassName(context.packageName, KpmAutofill.PICKER_ACTIVITY_CLASS)
                .setIdentifier(UUID.randomUUID().toString())
                .putExtra(EXTRA_PACKAGE, plan.packageName)
                .putExtra(EXTRA_WEB_DOMAIN, plan.webDomain)
                .putExtra(EXTRA_WEB_SCHEME, plan.webScheme)
                .putExtra(EXTRA_USERNAME_ID, plan.usernameId)
                .putExtra(EXTRA_PASSWORD_ID, plan.passwordId)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE).intentSender
        }

        private fun appLabel(packageManager: PackageManager, packageName: String): String? = try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            packageManager.getApplicationLabel(info).toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
