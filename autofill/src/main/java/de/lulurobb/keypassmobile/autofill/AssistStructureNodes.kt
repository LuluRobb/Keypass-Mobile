package de.lulurobb.keypassmobile.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId
import de.lulurobb.keypassmobile.autofill.form.FormNode
import de.lulurobb.keypassmobile.autofill.form.FormStructure
import java.util.Locale

/** [FormStructure] over a real AssistStructure. Reads structure only, never values. */
internal class AssistFormStructure(private val structure: AssistStructure) : FormStructure<AutofillId> {
    override val packageName: String
        get() = structure.activityComponent?.packageName.orEmpty()

    override val windows: List<FormNode<AutofillId>>
        get() = List(structure.windowNodeCount) { ViewNodeAdapter(structure.getWindowNodeAt(it).rootViewNode) }
}

private class ViewNodeAdapter(private val node: AssistStructure.ViewNode) : FormNode<AutofillId> {
    override val autofillId: AutofillId? get() = node.autofillId
    override val isTextField: Boolean get() = node.autofillType == View.AUTOFILL_TYPE_TEXT
    override val isViewVisible: Boolean get() = node.visibility == View.VISIBLE
    override val width: Int get() = node.width
    override val height: Int get() = node.height
    override val autofillHints: List<String> get() = node.autofillHints?.filterNotNull().orEmpty()
    override val htmlTag: String? get() = node.htmlInfo?.tag?.lowercase(Locale.ROOT)

    override val htmlAttributes: Map<String, String> by lazy {
        node.htmlInfo?.attributes.orEmpty()
            .filter { it.first != null }
            .associate { it.first.lowercase(Locale.ROOT) to it.second.orEmpty() }
    }

    override val inputType: Int get() = node.inputType
    override val idEntry: String? get() = node.idEntry
    override val hint: String? get() = node.hint
    override val webDomain: String? get() = node.webDomain
    override val webScheme: String? get() = node.webScheme

    override val children: List<FormNode<AutofillId>>
        get() = List(node.childCount) { ViewNodeAdapter(node.getChildAt(it)) }
}
