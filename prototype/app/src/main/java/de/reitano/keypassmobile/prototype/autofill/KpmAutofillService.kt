package de.reitano.keypassmobile.prototype.autofill

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.widget.RemoteViews
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import de.reitano.keypassmobile.prototype.MainActivity
import de.reitano.keypassmobile.prototype.R
import java.util.concurrent.atomic.AtomicInteger

/** Logcat tag of the autofill prototype. Logs carry structure only, never values. */
const val AUTOFILL_LOG_TAG = "KPM-Autofill"

/**
 * Autofill service of the prototype. For every request with login fields it
 * returns exactly one dataset "KeyPass Mobile" without values (AF-02, AF-04).
 * The dataset requires authentication: tapping it opens
 * [AutofillSelectActivity], which returns the filled dataset. No SaveInfo (AF-08).
 */
class KpmAutofillService : AutofillService() {

    private val requestCodes = AtomicInteger(1)

    override fun onConnected() {
        Log.d(AUTOFILL_LOG_TAG, "service connected")
    }

    override fun onDisconnected() {
        Log.d(AUTOFILL_LOG_TAG, "service disconnected")
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val fillContext = request.fillContexts.lastOrNull()
        if (fillContext == null) {
            Log.i(AUTOFILL_LOG_TAG, "fill request id=${request.id}: no fill context")
            callback.onSuccess(null)
            return
        }
        val parsed = StructureParser.parse(fillContext.structure, fillContext.focusedId)
        Log.i(
            AUTOFILL_LOG_TAG,
            "fill request id=${request.id} flags=0x${Integer.toHexString(request.flags)} " +
                "package=${parsed.packageName} webDomain=${parsed.anyWebDomain ?: "-"} " +
                "focusedId=${fillContext.focusedId} textFields=${parsed.fields.size} " +
                describeInlineRequest(request),
        )
        parsed.fields.forEachIndexed { index, field ->
            Log.d(AUTOFILL_LOG_TAG, StructureParser.describe(index, field, LoginFieldDetector.classify(field.info)))
        }

        val login = LoginFieldDetector.find(parsed.fields.map { it.info })
        if (login == null) {
            Log.i(AUTOFILL_LOG_TAG, "no login fields, no suggestion")
            callback.onSuccess(null)
            return
        }
        val username = login.username?.let { parsed.fields[it] }
        val password = login.password?.let { parsed.fields[it] }
        val selectRequest = SelectRequest(
            webDomain = (password ?: username)?.info?.webDomain,
            packageName = parsed.packageName,
            usernameId = username?.autofillId,
            passwordId = password?.autofillId,
        )

        val inline = createInlinePresentation(request)
        val presentations = Presentations.Builder()
            .setMenuPresentation(createMenuPresentation())
            .apply { if (inline != null) setInlinePresentation(inline) }
            .build()
        val dataset = Dataset.Builder()
            .apply {
                listOfNotNull(selectRequest.usernameId, selectRequest.passwordId).forEach { id ->
                    setField(id, Field.Builder().setPresentations(presentations).build())
                }
            }
            .setAuthentication(createAuthIntent(selectRequest).intentSender)
            .build()

        Log.i(
            AUTOFILL_LOG_TAG,
            "suggestion offered: username=${login.username?.let { "field[$it]" } ?: "-"} " +
                "password=${login.password?.let { "field[$it]" } ?: "-"} " +
                "domain=${selectRequest.webDomain ?: "-"} inline=${inline != null} menu=true",
        )
        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Never triggered: responses carry no SaveInfo (AF-08).
        callback.onSuccess()
    }

    private fun createAuthIntent(selectRequest: SelectRequest): PendingIntent {
        val intent = selectRequest.toIntent(this)
        // Unique request code per response; immutable because the system adds nothing we need.
        return PendingIntent.getActivity(
            this,
            requestCodes.getAndIncrement(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Dropdown entry below the field. */
    private fun createMenuPresentation(): RemoteViews =
        RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, getString(R.string.autofill_suggestion_title))
        }

    /** Chip in the keyboard, only if the IME asked for inline suggestions with UI version 1. */
    private fun createInlinePresentation(request: FillRequest): InlinePresentation? {
        val inlineRequest = request.inlineSuggestionsRequest ?: return null
        if (inlineRequest.maxSuggestionCount < 1) return null
        val spec = inlineRequest.inlinePresentationSpecs.firstOrNull() ?: return null
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(spec.style)) return null

        // Long press on the chip opens the prototype app.
        val attribution = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Typed as the public interface: SlicedContent.getSlice() is library-restricted.
        val content: UiVersions.Content = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(getString(R.string.autofill_suggestion_title))
            .setContentDescription(getString(R.string.autofill_suggestion_description))
            .setStartIcon(Icon.createWithResource("android", android.R.drawable.ic_lock_lock))
            .build()
        return InlinePresentation(content.slice, spec, false)
    }

    private fun describeInlineRequest(request: FillRequest): String {
        val inlineRequest = request.inlineSuggestionsRequest ?: return "inlineRequest=none"
        val specs = inlineRequest.inlinePresentationSpecs
        val v1 = specs.firstOrNull()?.let { UiVersions.INLINE_UI_VERSION_1 in UiVersions.getVersions(it.style) }
        return "inlineRequest=yes host=${inlineRequest.hostPackageName} " +
            "max=${inlineRequest.maxSuggestionCount} specs=${specs.size} uiV1=$v1"
    }
}
