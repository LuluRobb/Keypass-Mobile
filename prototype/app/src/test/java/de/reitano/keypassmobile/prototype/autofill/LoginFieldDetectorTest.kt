package de.reitano.keypassmobile.prototype.autofill

import android.text.InputType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginFieldDetectorTest {

    private fun html(vararg attributes: Pair<String, String>) =
        FieldInfo(htmlTag = "input", htmlAttributes = attributes.toMap())

    @Test
    fun webFormWithAutocomplete() {
        val fields = listOf(
            html("type" to "text", "name" to "q", "autocomplete" to "off"),
            html("type" to "text", "name" to "f1", "autocomplete" to "username"),
            html("type" to "password", "name" to "f2", "autocomplete" to "current-password"),
        )
        assertEquals(LoginFields(username = 1, password = 2), LoginFieldDetector.find(fields))
    }

    @Test
    fun webFormWithoutHintsUsesTextFieldBeforePassword() {
        val fields = listOf(
            html("type" to "text", "name" to "feld1", "id" to "feld1"),
            html("type" to "password", "name" to "feld2", "id" to "feld2"),
        )
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(fields))
    }

    @Test
    fun webFormNameHeuristicSkipsSearchField() {
        val fields = listOf(
            html("type" to "text", "name" to "login"),
            html("type" to "text", "name" to "search"),
            html("type" to "password", "name" to "kennwort"),
        )
        assertEquals(LoginFields(username = 0, password = 2), LoginFieldDetector.find(fields))
    }

    @Test
    fun hiddenAndButtonInputsAreIgnored() {
        val fields = listOf(
            html("type" to "hidden", "name" to "username"),
            html("type" to "email", "name" to "x"),
            html("type" to "password", "name" to "y"),
            html("type" to "submit", "name" to "login"),
        )
        assertEquals(LoginFields(username = 1, password = 2), LoginFieldDetector.find(fields))
    }

    @Test
    fun usernameOnlyStepIsALoginForm() {
        val fields = listOf(html("type" to "email", "autocomplete" to "section-login username webauthn"))
        assertEquals(LoginFields(username = 0, password = null), LoginFieldDetector.find(fields))
    }

    @Test
    fun formWithoutLoginFieldsReturnsNull() {
        val fields = listOf(
            html("type" to "search", "name" to "q"),
            html("type" to "text", "name" to "comment"),
            FieldInfo(htmlTag = "textarea", htmlAttributes = mapOf("name" to "password")),
        )
        assertNull(LoginFieldDetector.find(fields))
    }

    @Test
    fun otpFieldIsNotAUsername() {
        assertEquals(FieldKind.OTHER, LoginFieldDetector.classify(html("type" to "text", "name" to "otp_code")))
    }

    @Test
    fun nativeAutofillHints() {
        val fields = listOf(
            FieldInfo(autofillHints = listOf("emailAddress")),
            FieldInfo(autofillHints = listOf("password")),
        )
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(fields))
    }

    @Test
    fun newPasswordHintCountsAsPassword() {
        assertEquals(FieldKind.PASSWORD, LoginFieldDetector.classify(html("autocomplete" to "new-password")))
        assertEquals(FieldKind.PASSWORD, LoginFieldDetector.classify(FieldInfo(autofillHints = listOf("newPassword"))))
    }

    @Test
    fun inputTypePasswordFlags() {
        val text = InputType.TYPE_CLASS_TEXT
        listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).forEach { type ->
            assertEquals(FieldKind.PASSWORD, LoginFieldDetector.classify(FieldInfo(inputType = type)), "inputType=$type")
        }
        assertEquals(
            FieldKind.USERNAME,
            LoginFieldDetector.classify(FieldInfo(inputType = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)),
        )
        assertEquals(FieldKind.OTHER, LoginFieldDetector.classify(FieldInfo(inputType = text)))
    }

    @Test
    fun nativeIdEntryAndHintHeuristic() {
        val fields = listOf(
            FieldInfo(idEntry = "et_benutzer"),
            FieldInfo(idEntry = "et_secret", hint = "Passwort"),
        )
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(fields))
    }

    /** Both forms of testpages/login.html in one structure. */
    private fun twoForms(focused: Int?) = listOf(
        html("type" to "text", "name" to "username", "autocomplete" to "username"),
        html("type" to "password", "name" to "password", "autocomplete" to "current-password"),
        html("type" to "text", "name" to "f1"),
        html("type" to "password", "name" to "f2"),
    ).mapIndexed { index, field -> field.copy(isFocused = index == focused) }

    @Test
    fun focusedFieldSelectsItsForm() {
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(twoForms(focused = null)))
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(twoForms(focused = 0)))
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(twoForms(focused = 1)))
        assertEquals(LoginFields(username = 2, password = 3), LoginFieldDetector.find(twoForms(focused = 2)))
        assertEquals(LoginFields(username = 2, password = 3), LoginFieldDetector.find(twoForms(focused = 3)))
    }

    @Test
    fun focusedUsernameWithoutPasswordAfterIt() {
        val fields = listOf(
            html("type" to "email", "name" to "email").copy(isFocused = true),
            html("type" to "text", "name" to "nickname", "autocomplete" to "username"),
            html("type" to "password", "name" to "pass"),
        )
        assertEquals(LoginFields(username = 0, password = null), LoginFieldDetector.find(fields))
    }

    @Test
    fun focusedUnrelatedFieldFallsBackToFirstLoginForm() {
        val fields = listOf(
            html("type" to "text", "name" to "user"),
            html("type" to "password", "name" to "pass"),
            html("type" to "text", "name" to "q", "id" to "search-box").copy(isFocused = true),
        )
        assertEquals(LoginFields(username = 0, password = 1), LoginFieldDetector.find(fields))
    }

    @Test
    fun visiblePasswordFieldPreferred() {
        val fields = listOf(
            html("type" to "password", "name" to "decoy").copy(isVisible = false),
            html("type" to "text", "name" to "user"),
            html("type" to "password", "name" to "pass"),
        )
        assertEquals(LoginFields(username = 1, password = 2), LoginFieldDetector.find(fields))
    }
}
