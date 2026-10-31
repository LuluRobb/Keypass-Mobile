package de.reitano.keypassmobile.prototype.autofill

import android.text.InputType

/** Role of an input field for login filling. */
enum class FieldKind { USERNAME, PASSWORD, OTHER }

/**
 * Platform independent description of one text node of an AssistStructure.
 * Built by [StructureParser]; free of Android types so that the detection
 * runs in plain JVM unit tests. Never contains the field value.
 */
data class FieldInfo(
    val autofillHints: List<String> = emptyList(),
    /** Tag from ViewStructure.HtmlInfo (browsers only), lower case. */
    val htmlTag: String? = null,
    /** HtmlInfo attributes such as type, name, id, autocomplete; keys lower case. */
    val htmlAttributes: Map<String, String> = emptyMap(),
    val inputType: Int = 0,
    val idEntry: String? = null,
    /** Hint text of the view; for web fields usually the placeholder. */
    val hint: String? = null,
    val isVisible: Boolean = true,
    /** True for the field that triggered the fill request. */
    val isFocused: Boolean = false,
    val webDomain: String? = null,
)

/** Indices into the list passed to [LoginFieldDetector.find]; at least one is set. */
data class LoginFields(val username: Int?, val password: Int?)

/** Detects username and password fields. Pure Kotlin, see [FieldInfo]. */
object LoginFieldDetector {

    // Hints and autocomplete tokens, compared after removing everything but letters.
    private val PASSWORD_HINTS = setOf("password", "currentpassword", "newpassword")
    private val USERNAME_HINTS = setOf("username", "newusername", "email", "emailaddress")

    private val IGNORED_INPUT_TYPES = setOf(
        "hidden", "submit", "button", "reset", "image", "checkbox", "radio", "file",
        "search", "date", "datetime-local", "month", "week", "time", "color", "range",
    )

    // Substrings for the name/id heuristic (lower case).
    private val EXCLUDED_WORDS = listOf("search", "suche", "query", "captcha", "otp", "code")
    private val PASSWORD_WORDS = listOf("password", "passwort", "passwd", "kennwort", "pwd")
    private val USERNAME_WORDS = listOf(
        "username", "user", "benutzer", "nutzer", "login", "anmeld", "email", "e-mail", "mail",
        "account", "konto",
    )

    fun classify(field: FieldInfo): FieldKind {
        if (!isFillable(field)) return FieldKind.OTHER
        val type = field.htmlAttributes["type"]?.lowercase()

        if (type == "password" || isPasswordInputType(field.inputType)) return FieldKind.PASSWORD
        kindFromHints(field.autofillHints + autocompleteTokens(field))?.let { return it }
        if (type == "email" || isEmailInputType(field.inputType)) return FieldKind.USERNAME
        return kindFromWords(field)
    }

    /**
     * Picks the login fields. If the focused field belongs to a login form,
     * that form is used (a page may contain several forms). Otherwise: the
     * first password field and the username field in front of it. Without a
     * recognisable username field, the nearest text field before the password
     * is taken. Returns null without any login field.
     */
    fun find(fields: List<FieldInfo>): LoginFields? {
        val kinds = fields.map(::classify)

        val focused = fields.indexOfFirst { it.isFocused }
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

        val password = pick(fields, kinds, FieldKind.PASSWORD)
        val username = password?.let { usernameFor(it, fields, kinds) } ?: pick(fields, kinds, FieldKind.USERNAME)
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

    /** First field of [kind], visible fields preferred. */
    private fun pick(fields: List<FieldInfo>, kinds: List<FieldKind>, kind: FieldKind): Int? {
        val all = kinds.indices.filter { kinds[it] == kind }
        return all.firstOrNull { fields[it].isVisible } ?: all.firstOrNull()
    }

    private fun isFillable(field: FieldInfo): Boolean {
        if (field.htmlTag != null && field.htmlTag != "input") return false
        val type = field.htmlAttributes["type"]?.lowercase()
        return type == null || type !in IGNORED_INPUT_TYPES
    }

    private fun isUsernameFallback(field: FieldInfo): Boolean =
        field.isVisible && isFillable(field) && words(field).none { w -> EXCLUDED_WORDS.any { it in w } }

    private fun autocompleteTokens(field: FieldInfo): List<String> =
        field.htmlAttributes["autocomplete"]?.split(' ', '\t')?.filter { it.isNotBlank() }.orEmpty()

    private fun kindFromHints(hints: List<String>): FieldKind? {
        val normalized = hints.map { hint -> hint.lowercase().filter { it in 'a'..'z' } }
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
        ).map { it.lowercase() }

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
