package de.lulurobb.keypassmobile.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillId
import de.lulurobb.keypassmobile.autofill.form.FormAnalyzer
import de.lulurobb.keypassmobile.autofill.form.LoginFieldDetector
import de.lulurobb.keypassmobile.autofill.form.ParsedField
import de.lulurobb.keypassmobile.autofill.form.StructureParser

/**
 * Logcat tag. Logs carry structure only, never values (SE-07). Per-field
 * details only with `adb shell setprop log.tag.KPM-Autofill DEBUG`.
 */
internal const val LOG_TAG = "KPM-Autofill"

/**
 * Autofill service (AF-01). For every request from another app with visible
 * login fields it returns exactly one dataset "KeyPass Mobile" without values
 * (AF-02, AF-04): as a chip in the keyboard when the IME asks for inline
 * suggestions, otherwise as a dropdown row. The dataset requires
 * authentication, so tapping it opens the picker activity of the app
 * ([KpmAutofill.PICKER_ACTIVITY_CLASS]), which returns the filled dataset.
 * Responses never carry SaveInfo, so Android never offers to save (AF-08).
 *
 * The service needs no access to the vault; it works the same while locked.
 */
class KpmAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val response = try {
            buildResponse(request)
        } catch (e: RuntimeException) {
            // The service shares the process with the unlocked vault: never crash it.
            // Only the exception class is logged; messages could quote page content.
            Log.w(LOG_TAG, "fill request failed: ${e.javaClass.name}")
            null
        }
        callback.onSuccess(response)
        if (response != null) {
            try {
                KpmAutofill.onSuggestionOffered?.invoke()
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "suggestion hook failed: ${e.javaClass.name}")
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Never triggered: responses carry no SaveInfo (AF-08).
        callback.onSuccess()
    }

    private fun buildResponse(request: FillRequest): FillResponse? {
        val fillContext = request.fillContexts.lastOrNull() ?: return null
        val structure = AssistFormStructure(fillContext.structure)
        val plan = FormAnalyzer.analyze(structure, fillContext.focusedId, packageName)
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(
                LOG_TAG,
                "request ${request.id}: flags=0x${Integer.toHexString(request.flags)} " +
                    "package=${structure.packageName} domain=${plan?.webDomain ?: "-"} " +
                    "scheme=${plan?.webScheme ?: "-"} login=${plan?.login}",
            )
            logFields(plan?.fields ?: StructureParser.parse(structure, fillContext.focusedId))
        }
        if (plan == null) {
            Log.i(LOG_TAG, "request ${request.id}: no suggestion (own app or no visible login field)")
            return null
        }

        val inline = SuggestionViews.inlinePresentation(this, request)
        val presentations = Presentations.Builder()
            .setMenuPresentation(SuggestionViews.menuPresentation(this))
            .apply { if (inline != null) setInlinePresentation(inline) }
            .build()
        // Fields without values: the values come only from the picker (AF-04).
        val dataset = Dataset.Builder()
            .apply {
                listOfNotNull(plan.usernameId, plan.passwordId).forEach { id ->
                    setField(id, Field.Builder().setPresentations(presentations).build())
                }
            }
            .setAuthentication(AutofillRequest.authentication(this, plan))
            .build()
        Log.i(
            LOG_TAG,
            "request ${request.id}: suggestion offered, username=${plan.usernameId != null} " +
                "password=${plan.passwordId != null} web=${plan.webDomain != null} inline=${inline != null}",
        )
        return FillResponse.Builder().addDataset(dataset).build()
    }

    /** Debug only: one line per text field, structure only. */
    private fun logFields(fields: List<ParsedField<AutofillId>>) {
        fields.forEachIndexed { index, field ->
            val kind = if (field.info.isVisible) LoginFieldDetector.classify(field.info) else null
            Log.d(LOG_TAG, StructureParser.describe(index, field, kind))
        }
    }
}
