package de.lulurobb.keypassmobile.autofill.form

import android.text.InputType
import java.util.Locale

/** Role of an input field for login filling. */
internal enum class FieldKind { USERNAME, PASSWORD, OTHER }

/**
 * Platform independent description of one text field of an AssistStructure.
 * Built by [StructureParser]; free of Android types so that the detection
 * runs in plain JVM unit tests. Never contains the field value (SE-07).
 */
internal data class FieldInfo(
    /** View autofill hints; for Chromium browsers the tokens of the HTML autocomplete attribute. */
    val autofillHints: List<String> = emptyList(),
    /** Tag from ViewStructure.HtmlInfo (browsers only), lower case. */
    val htmlTag: String? = null,
    /** HtmlInfo attributes such as type, name, id, label; keys lower case. */
    val htmlAttributes: Map<String, String> = emptyMap(),
    val inputType: Int = 0,
    val idEntry: String? = null,
    /** Hint text of the view; for web fields usually the placeholder. */
    val hint: String? = null,
    /**
     * False when the user cannot see the field (AF-11), see [StructureParser].
     * Hidden fields are neither filled nor counted as login fields.
     */
    val isVisible: Boolean = true,
    /** True for the field that triggered the fill request. */
    val isFocused: Boolean = false,
    /** Host of the web page the field belongs to; null in native apps. */
    val webDomain: String? = null,
    /** Scheme of that page ("https") when the browser reports it. */
    val webScheme: String? = null,
)

/** Indices into the list passed to [LoginFieldDetector.find]; at least one is set. */
internal data class LoginFields(val username: Int?, val password: Int?)

/**
 * Detects username and password fields. Pure Kotlin, see [FieldInfo].
 *
 * Order of the rules in [classify]:
 * 1. Not fillable (not an input, button-like or hidden HTML types, one-time codes): other.
 * 2. Password input (HTML type or Android inputType): password.
 * 3. Autofill hints and HTML autocomplete tokens set by the developer.
 * 4. Field types computed by Chromium (HtmlInfo "computed-autofill-hints" etc.).
 * 5. E-mail input (HTML type or Android inputType): username.
 * 6. Words in ids, names, labels, placeholders and hints, German and English.
 */
internal object LoginFieldDetector {

    // Hints and type names, compared after removing everything but letters.
    private val PASSWORD_HINTS = setOf(
        "password", "currentpassword", "newpassword",
        // Chromium field types
        "accountcreationpassword", "notaccountcreationpassword", "confirmationpassword",
        "probablynewpassword", "notnewpassword",
    )
    private val USERNAME_HINTS = setOf(
        "username", "newusername", "email", "emailaddress",
        // Chromium field types
        "singleusername", "usernameandemailaddress",
    )
    /** Hints of fields that look like login fields but must never get a password or username. */
    private val NOT_LOGIN_HINTS = setOf("onetimecode", "smsotp", "otp")

    /** HtmlInfo attributes in which Chromium reports its own field type predictions. */
    private val CHROMIUM_TYPE_ATTRIBUTES = listOf(
        "computed-autofill-hints", "ua-autofill-hints", "crowdsourcing-autofill-hints",
    )

    private val IGNORED_INPUT_TYPES = setOf(
        "hidden", "submit", "button", "reset", "image", "checkbox", "radio", "file",
        "search", "date", "datetime-local", "month", "week", "time", "color", "range",
    )

    // Substrings for the name/id heuristic (lower case).
    private val EXCLUDED_WORDS = listOf("search", "such", "query", "captcha", "otp", "code")
    private val PASSWORD_WORDS = listOf("password", "passwort", "passwd", "kennwort", "pwd")
    private val USERNAME_WORDS = listOf(
        "username", "user", "benutzer", "nutzer", "login", "anmeld", "email", "e-mail", "mail",
        "account", "konto", "kundennummer", "zugangsnummer", "teilnehmernummer", "legitimation",
        "signin", "sign-in",
    )

    fun classify(field: FieldInfo): FieldKind {
        if (!isFillable(field)) return FieldKind.OTHER
        val developerHints = field.autofillHints + autocompleteTokens(field)
        if (normalized(developerHints).any { it in NOT_LOGIN_HINTS }) return FieldKind.OTHER

        val type = htmlType(field)
        if (type == "password" || isPasswordInputType(field.inputType)) return FieldKind.PASSWORD
        kindFromHints(developerHints)?.let { return it }
        kindFromHints(chromiumTypes(field))?.let { return it }
        if (type == "email" || isEmailInputType(field.inputType)) return FieldKind.USERNAME
        return kindFromWords(field)
    }

    /**
     * Picks the login fields among the visible fields; hidden fields are
     * ignored completely (AF-11). If the focused field belongs to a login
     * form, that form is used (a page may contain several forms). Otherwise:
     * the first password field and the username field in front of it.
     * Without a recognisable username field, the nearest plain text field
     * before the password is taken. Returns null without any login field.
     */
    fun find(fields: List<FieldInfo>): LoginFields? {
        val kinds = fields.map { if (it.isVisible) classify(it) else FieldKind.OTHER }

        val focused = fields.indexOfFirst { it.isFocused && it.isVisible }
        if (focused >= 0) {
            when (kinds[focused]) {
                FieldKind.PASSWORD -> return LoginFields(usernameFor(focused, fields, kinds), focused)
                FieldKind.USERNAME -> return LoginFields(focused, passwordAfter(focused, kinds))
                FieldKind.OTHER -> {
                    val password = passwordAfter(focused, kinds)
                    if (password != null && usernameFor(password, fields, kinds) == focused) {
                        return LoginFields(focused, password)
                    }
                }
            }
        }

        val password = kinds.indexOfFirst { it == FieldKind.PASSWORD }.takeIf { it >= 0 }
        val username = password?.let { usernameFor(it, fields, kinds) }
            ?: kinds.indexOfFirst { it == FieldKind.USERNAME }.takeIf { it >= 0 }
        if (username == null && password == null) return null
        return LoginFields(username, password)
    }

    /**
     * Username for the password at [password]: searched backwards up to the
     * previous password field. A recognised username field wins, otherwise the
     * nearest plain text field.
     */
    private fun usernameFor(password: Int, fields: List<FieldInfo>, kinds: List<FieldKind>): Int? {
        val previousPassword = (password - 1 downTo 0).firstOrNull { kinds[it] == FieldKind.PASSWORD } ?: -1
        val range = password - 1 downTo previousPassword + 1
        return range.firstOrNull { kinds[it] == FieldKind.USERNAME }
            ?: range.firstOrNull { kinds[it] == FieldKind.OTHER && isUsernameFallback(fields[it]) }
    }

    /** Next password field after [index], unless another username field comes first. */
    private fun passwordAfter(index: Int, kinds: List<FieldKind>): Int? {
        for (i in index + 1 until kinds.size) {
            when (kinds[i]) {
                FieldKind.PASSWORD -> return i
                FieldKind.USERNAME -> return null
                FieldKind.OTHER -> Unit
            }
        }
        return null
    }

    private fun isFillable(field: FieldInfo): Boolean {
        if (field.htmlTag != null && field.htmlTag != "input") return false
        val type = htmlType(field)
        return type == null || type !in IGNORED_INPUT_TYPES
    }

    private fun isUsernameFallback(field: FieldInfo): Boolean =
        field.isVisible && isFillable(field) &&
            normalized(field.autofillHints + autocompleteTokens(field)).none { it in NOT_LOGIN_HINTS } &&
            words(field).none { w -> EXCLUDED_WORDS.any { it in w } }

    private fun htmlType(field: FieldInfo): String? =
        field.htmlAttributes["type"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun autocompleteTokens(field: FieldInfo): List<String> = tokens(field.htmlAttributes["autocomplete"])

    private fun chromiumTypes(field: FieldInfo): List<String> =
        CHROMIUM_TYPE_ATTRIBUTES.flatMap { tokens(field.htmlAttributes[it]) }

    private fun tokens(value: String?): List<String> =
        value?.split(' ', '\t', ',')?.filter { it.isNotBlank() }.orEmpty()

    private fun normalized(hints: List<String>): List<String> =
        hints.map { hint -> hint.lowercase(Locale.ROOT).filter { it in 'a'..'z' } }

    private fun kindFromHints(hints: List<String>): FieldKind? {
        val normalized = normalized(hints)
        return when {
            normalized.any { it in PASSWORD_HINTS } -> FieldKind.PASSWORD
            normalized.any { it in USERNAME_HINTS } -> FieldKind.USERNAME
            else -> null
        }
    }

    private fun kindFromWords(field: FieldInfo): FieldKind {
        val words = words(field)
        return when {
            words.any { w -> EXCLUDED_WORDS.any { it in w } } -> FieldKind.OTHER
            words.any { w -> PASSWORD_WORDS.any { it in w } } -> FieldKind.PASSWORD
            words.any { w -> USERNAME_WORDS.any { it in w } } -> FieldKind.USERNAME
            else -> FieldKind.OTHER
        }
    }

    private fun words(field: FieldInfo): List<String> =
        listOfNotNull(
            field.idEntry,
            field.hint,
            field.htmlAttributes["name"],
            field.htmlAttributes["id"],
            field.htmlAttributes["placeholder"],
            field.htmlAttributes["label"],
            field.htmlAttributes["aria-label"],
        ).map { it.lowercase(Locale.ROOT) }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isEmailInputType(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }
}
