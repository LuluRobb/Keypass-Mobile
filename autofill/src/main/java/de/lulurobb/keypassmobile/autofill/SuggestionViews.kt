package de.lulurobb.keypassmobile.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.autofill.FillRequest
import android.service.autofill.InlinePresentation
import android.widget.RemoteViews
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/** The one "KeyPass Mobile" suggestion (AF-02): dropdown row and keyboard chip. */
internal object SuggestionViews {

    /** Row in the dropdown below the field; used when the keyboard asks for no inline suggestions. */
    fun menuPresentation(context: Context): RemoteViews =
        RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, context.getString(R.string.kpm_autofill_suggestion_title))
            setContentDescription(android.R.id.text1, context.getString(R.string.kpm_autofill_suggestion_description))
        }

    /**
     * Chip in the keyboard, only if the IME asked for inline suggestions and
     * supports UI version 1 (Samsung Keyboard and Gboard do). Null otherwise,
     * then the system shows [menuPresentation].
     */
    fun inlinePresentation(context: Context, request: FillRequest): InlinePresentation? {
        val inlineRequest = request.inlineSuggestionsRequest ?: return null
        if (inlineRequest.maxSuggestionCount < 1) return null
        val spec = inlineRequest.inlinePresentationSpecs.firstOrNull() ?: return null
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(spec.style)) return null

        // Typed as the public interface: SlicedContent.getSlice() is library-restricted.
        val content: UiVersions.Content = InlineSuggestionUi.newContentBuilder(attribution(context))
            .setTitle(context.getString(R.string.kpm_autofill_suggestion_title))
            .setContentDescription(context.getString(R.string.kpm_autofill_suggestion_description))
            .setStartIcon(appIcon(context))
            .build()
        return InlinePresentation(content.slice, spec, false)
    }

    /** App icon; the key symbol of this library while the app has none. */
    private fun appIcon(context: Context): Icon {
        val appIcon = context.applicationInfo.icon
        return Icon.createWithResource(context, if (appIcon != 0) appIcon else R.drawable.kpm_autofill_ic_key)
    }

    /** Opened by a long press on the chip: the app itself. */
    private fun attribution(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Settings.ACTION_SETTINGS)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}
