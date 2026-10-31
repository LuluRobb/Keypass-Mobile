package de.reitano.keypassmobile.prototype.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/** A text node of the structure: its autofill id plus the description used for detection. */
class ParsedField(val autofillId: AutofillId, val info: FieldInfo)

class ParsedStructure(
    val packageName: String,
    /** Text fields in traversal order (document order for browsers). */
    val fields: List<ParsedField>,
    /** First web domain anywhere in the structure, for diagnostics only. */
    val anyWebDomain: String?,
)

/** Converts an AssistStructure into [ParsedField]s. Reads structure only, never values. */
object StructureParser {

    /** [focusedId] is FillContext.focusedId, the field that triggered the request. */
    fun parse(structure: AssistStructure, focusedId: AutofillId?): ParsedStructure {
        val fields = mutableListOf<ParsedField>()
        var anyWebDomain: String? = null

        // The web domain is set on the root node of the web content and inherited by its children.
        fun visit(node: AssistStructure.ViewNode, inheritedDomain: String?) {
            val domain = node.webDomain?.takeIf { it.isNotBlank() } ?: inheritedDomain
            if (anyWebDomain == null) anyWebDomain = domain
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                fields += ParsedField(id, node.toFieldInfo(domain, focused = id == focusedId))
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i), domain)
        }

        for (i in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(i).rootViewNode, null)
        }
        return ParsedStructure(structure.activityComponent.packageName, fields, anyWebDomain)
    }

    private fun AssistStructure.ViewNode.toFieldInfo(domain: String?, focused: Boolean): FieldInfo {
        val html = htmlInfo
        return FieldInfo(
            autofillHints = autofillHints?.toList().orEmpty(),
            htmlTag = html?.tag?.lowercase(),
            htmlAttributes = html?.attributes.orEmpty()
                .filter { it.first != null }
                .associate { it.first.lowercase() to it.second.orEmpty() },
            inputType = inputType,
            idEntry = idEntry,
            hint = hint,
            isVisible = visibility == View.VISIBLE,
            isFocused = focused,
            webDomain = domain,
        )
    }

    /** One log line per field: structure only (ids, hints, HTML attributes), no values. */
    fun describe(index: Int, field: ParsedField, kind: FieldKind): String {
        val info = field.info
        val attrs = listOf("type", "name", "id", "autocomplete")
            .mapNotNull { key -> info.htmlAttributes[key]?.let { "$key=$it" } }
            .joinToString(" ")
        return "field[$index] id=${field.autofillId} kind=$kind hints=${info.autofillHints} " +
            "html=${info.htmlTag ?: "-"}{$attrs} inputType=0x${Integer.toHexString(info.inputType)} " +
            "idEntry=${info.idEntry ?: "-"} hint=${info.hint ?: "-"} visible=${info.isVisible} " +
            "focused=${info.isFocused} domain=${info.webDomain ?: "-"}"
    }
}
