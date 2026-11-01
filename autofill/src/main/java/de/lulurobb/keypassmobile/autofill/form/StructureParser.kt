package de.lulurobb.keypassmobile.autofill.form

import java.util.Locale

/**
 * Read-only view of one node of an AssistStructure. Implemented over
 * AssistStructure.ViewNode in the service and by fakes in JVM tests, so the
 * whole parsing runs without Android. [Id] is AutofillId on the device.
 * Only structure is exposed, never the current value of a field (SE-07).
 */
internal interface FormNode<out Id : Any> {
    /** Autofill id; null for nodes that cannot be filled. */
    val autofillId: Id?

    /** True for nodes of autofill type text (View.AUTOFILL_TYPE_TEXT). */
    val isTextField: Boolean

    /** True when the node's own visibility is View.VISIBLE (ancestors not considered). */
    val isViewVisible: Boolean

    /** Size in pixels as reported by the app or browser; 0 when not reported. */
    val width: Int
    val height: Int

    val autofillHints: List<String>

    /** Tag of ViewStructure.HtmlInfo, lower case; null for native views. */
    val htmlTag: String?

    /** HtmlInfo attributes with lower case keys. */
    val htmlAttributes: Map<String, String>

    val inputType: Int
    val idEntry: String?
    val hint: String?

    /** Web domain set on this node (browsers and WebViews); inherited by the children. */
    val webDomain: String?
    val webScheme: String?

    val children: List<FormNode<Id>>
}

/** The windows of one AssistStructure plus the package of the requesting app. */
internal interface FormStructure<out Id : Any> {
    val packageName: String
    val windows: List<FormNode<Id>>
}

/** A text field of the structure: its autofill id plus the description used for detection. */
internal class ParsedField<out Id : Any>(val autofillId: Id, val info: FieldInfo)

/**
 * Collects the text fields of a structure in traversal order (document order
 * in browsers) and decides which of them the user can see (AF-11).
 *
 * A field counts as hidden when
 * - the field or any ancestor is not View.VISIBLE (native views; Chromium
 *   marks fields that are not focusable as View.INVISIBLE);
 * - the browser reports the HtmlInfo attribute visibility=invisible. Brave
 *   does so for display:none, visibility:hidden and zero-size inputs
 *   (checked on the emulator with autofill/testpages/login-steps.html);
 * - it is an HTML input of type hidden (Brave does not report those at all);
 * - it has zero width or height. This rule only applies when the structure
 *   reports sizes at all, i.e. at least one text field has a non-zero size;
 *   otherwise a framework that reports no sizes would never get a suggestion.
 *
 * Not detected: opacity 0 and clipping. A field moved off screen
 * (left: -9999px) arrived as hidden in Brave, but that is not guaranteed.
 */
internal object StructureParser {

    /** [focusedId] is FillContext.focusedId, the field that triggered the request. */
    fun <Id : Any> parse(structure: FormStructure<Id>, focusedId: Id?): List<ParsedField<Id>> {
        val raw = mutableListOf<RawField<Id>>()

        fun visit(node: FormNode<Id>, parent: PageContext) {
            // The nearest web domain wins: Chromium sets it on the form and on every field.
            val domain = node.webDomain?.trim()?.takeIf { it.isNotEmpty() }
            val context = PageContext(
                domain = domain ?: parent.domain,
                scheme = if (domain != null) node.webScheme?.trim()?.takeIf { it.isNotEmpty() } else parent.scheme,
                shown = parent.shown && node.isViewVisible,
            )
            val id = node.autofillId
            if (id != null && node.isTextField) {
                raw += RawField(node, id, context, isHtmlHidden(node))
            }
            for (child in node.children) visit(child, context)
        }

        for (window in structure.windows) visit(window, PageContext(null, null, shown = true))

        val sizesReported = raw.any { it.node.width > 0 && it.node.height > 0 }
        return raw.map { field ->
            val node = field.node
            val hasSize = node.width > 0 && node.height > 0
            val visible = field.page.shown && !field.htmlHidden && (hasSize || !sizesReported)
            ParsedField(
                field.id,
                FieldInfo(
                    autofillHints = node.autofillHints,
                    htmlTag = node.htmlTag,
                    htmlAttributes = node.htmlAttributes,
                    inputType = node.inputType,
                    idEntry = node.idEntry,
                    hint = node.hint,
                    isVisible = visible,
                    isFocused = focusedId != null && field.id == focusedId,
                    webDomain = field.page.domain,
                    webScheme = field.page.scheme,
                ),
            )
        }
    }

    private fun isHtmlHidden(node: FormNode<*>): Boolean {
        val attributes = node.htmlAttributes
        val type = attributes["type"]?.trim()?.lowercase(Locale.ROOT)
        val visibility = attributes["visibility"]?.trim()?.lowercase(Locale.ROOT)
        return type == "hidden" || visibility == "invisible" || visibility == "hidden"
    }

    /** Web page of a subtree plus whether all ancestors are visible. */
    private data class PageContext(val domain: String?, val scheme: String?, val shown: Boolean)

    private class RawField<Id : Any>(
        val node: FormNode<Id>,
        val id: Id,
        val page: PageContext,
        val htmlHidden: Boolean,
    )

    /**
     * One log line per field: structure only (ids, hints, HTML attributes),
     * never values. [kind] is null for hidden fields.
     */
    fun describe(index: Int, field: ParsedField<*>, kind: FieldKind?): String {
        val info = field.info
        val attrs = listOf("type", "name", "id", "autocomplete", "computed-autofill-hints", "visibility")
            .mapNotNull { key -> info.htmlAttributes[key]?.takeIf { it.isNotEmpty() }?.let { "$key=$it" } }
            .joinToString(" ")
        return "field[$index] id=${field.autofillId} kind=${kind ?: "HIDDEN"} hints=${info.autofillHints} " +
            "html=${info.htmlTag ?: "-"}{$attrs} inputType=0x${Integer.toHexString(info.inputType)} " +
            "idEntry=${info.idEntry ?: "-"} visible=${info.isVisible} focused=${info.isFocused}"
    }
}
