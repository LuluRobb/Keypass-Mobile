package de.lulurobb.keypassmobile.autofill.form

import android.text.InputType

/** Fake of an AssistStructure node; ids are plain strings in tests. */
internal data class FakeNode(
    override val autofillId: String? = null,
    override val isTextField: Boolean = autofillId != null,
    override val isViewVisible: Boolean = true,
    override val width: Int = 600,
    override val height: Int = 120,
    override val autofillHints: List<String> = emptyList(),
    override val htmlTag: String? = null,
    override val htmlAttributes: Map<String, String> = emptyMap(),
    override val inputType: Int = 0,
    override val idEntry: String? = null,
    override val hint: String? = null,
    override val webDomain: String? = null,
    override val webScheme: String? = null,
    override val children: List<FakeNode> = emptyList(),
) : FormNode<String>

internal class FakeStructure(
    override val packageName: String,
    vararg windows: FakeNode,
) : FormStructure<String> {
    override val windows: List<FakeNode> = windows.toList()
}

internal const val BRAVE = "com.brave.browser"
internal const val OWN_PACKAGE = "de.lulurobb.keypassmobile"

/**
 * An HTML input as Chromium (Brave) reports it: tag "input", attributes name,
 * type, id, label; the autocomplete tokens become autofill hints.
 * Hidden inputs (display:none, visibility:hidden) come as INVISIBLE with zero size.
 */
internal fun input(
    id: String,
    type: String = "text",
    name: String = id,
    label: String = "",
    autocomplete: String? = null,
    placeholder: String? = null,
    hidden: Boolean = false,
    extra: Map<String, String> = emptyMap(),
    domain: String? = null,
) = FakeNode(
    autofillId = id,
    isViewVisible = !hidden,
    width = if (hidden) 0 else 600,
    height = if (hidden) 0 else 120,
    autofillHints = autocomplete?.split(" ")?.filter { it.isNotBlank() }.orEmpty(),
    htmlTag = "input",
    htmlAttributes = mapOf("name" to name, "type" to type, "id" to id, "label" to label) + extra,
    hint = placeholder,
    webDomain = domain,
)

/** Browser window: Brave's content view with a form node carrying the web domain. */
internal fun browserPage(
    host: String,
    vararg fields: FakeNode,
    scheme: String? = "https",
    packageName: String = BRAVE,
) = FakeStructure(
    packageName,
    FakeNode(
        children = listOf(
            FakeNode(
                htmlTag = "form",
                webDomain = host,
                webScheme = scheme,
                children = fields.toList(),
            ),
        ),
    ),
)

/** Native EditText. */
internal fun editText(
    id: String,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    idEntry: String? = null,
    hint: String? = null,
    hints: List<String> = emptyList(),
    visible: Boolean = true,
) = FakeNode(
    autofillId = id,
    isViewVisible = visible,
    inputType = inputType,
    idEntry = idEntry,
    hint = hint,
    autofillHints = hints,
)

internal fun appWindow(packageName: String, vararg children: FakeNode) =
    FakeStructure(packageName, FakeNode(children = children.toList()))
