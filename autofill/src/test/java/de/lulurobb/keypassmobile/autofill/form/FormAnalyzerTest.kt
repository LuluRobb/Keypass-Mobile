package de.lulurobb.keypassmobile.autofill.form

import android.text.InputType
import de.lulurobb.keypassmobile.model.FillTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Whole request analysis: own package, login detection with visibility, target. */
class FormAnalyzerTest {

    private fun analyze(structure: FormStructure<String>, focused: String? = null) =
        FormAnalyzer.analyze(structure, focused, OWN_PACKAGE)

    @Test
    fun usernameAndPasswordPageInBrowser() {
        val structure = browserPage(
            "accounts.example.com",
            input("q", type = "search"),
            input("user", autocomplete = "username"),
            input("pass", type = "password", autocomplete = "current-password"),
        )
        val plan = assertNotNull(analyze(structure, focused = "user"))
        assertEquals("user", plan.usernameId)
        assertEquals("pass", plan.passwordId)
        assertEquals("accounts.example.com", plan.webDomain)
        assertEquals("https", plan.webScheme)
        assertEquals(FillTarget.Web("accounts.example.com", BRAVE), plan.target(appLabel = "Brave"))
    }

    @Test
    fun pageWithoutAutocompleteAttributes() {
        val structure = browserPage("example.org", input("f1"), input("f2", type = "password"))
        val plan = assertNotNull(analyze(structure, focused = "f2"))
        assertEquals("f1", plan.usernameId)
        assertEquals("f2", plan.passwordId)
    }

    @Test
    fun passwordOnlyPage() {
        val structure = browserPage("example.org", input("pw", type = "password", label = "Passwort"))
        val plan = assertNotNull(analyze(structure, focused = "pw"))
        assertNull(plan.usernameId)
        assertEquals("pw", plan.passwordId)
    }

    @Test
    fun twoStepLoginFirstStepFillsOnlyTheVisibleUsername() {
        val structure = browserPage(
            "login.example.com",
            input("identifier", type = "email", autocomplete = "username"),
            input("password", type = "password", autocomplete = "current-password", hidden = true),
        )
        val plan = assertNotNull(analyze(structure, focused = "identifier"))
        assertEquals("identifier", plan.usernameId)
        assertNull(plan.passwordId)
    }

    @Test
    fun twoStepLoginSecondStepFillsOnlyThePassword() {
        val structure = browserPage(
            "login.example.com",
            input("identifier", type = "email", autocomplete = "username", hidden = true),
            input("password", type = "password", autocomplete = "current-password"),
        )
        val plan = assertNotNull(analyze(structure, focused = "password"))
        assertNull(plan.usernameId)
        assertEquals("password", plan.passwordId)
    }

    @Test
    fun hiddenPasswordWithChromiumVisibilityAttributeIsNotFilled() {
        val structure = browserPage(
            "example.com",
            input("user", label = "Benutzername"),
            input("pass", type = "password", extra = mapOf("visibility" to "invisible")).copy(isViewVisible = true),
        )
        val plan = assertNotNull(analyze(structure, focused = "user"))
        assertEquals("user", plan.usernameId)
        assertNull(plan.passwordId)
    }

    @Test
    fun hiddenTypeInputsAreNeverFilled() {
        val structure = browserPage(
            "example.com",
            input("username", type = "hidden"),
            input("email", type = "email"),
            input("pw", type = "password"),
        )
        val plan = assertNotNull(analyze(structure, focused = "pw"))
        assertEquals("email", plan.usernameId)
        assertEquals("pw", plan.passwordId)
    }

    @Test
    fun zeroSizeHoneypotPasswordIsSkipped() {
        val structure = browserPage(
            "example.com",
            input("trap", type = "password").copy(width = 0, height = 0),
            input("user", label = "E-Mail"),
            input("pass", type = "password"),
        )
        val plan = assertNotNull(analyze(structure))
        assertEquals("user", plan.usernameId)
        assertEquals("pass", plan.passwordId)
    }

    @Test
    fun germanLabelsInBrowser() {
        val structure = browserPage(
            "banking.example.de",
            input("f1", label = "Anmeldename"),
            input("f2", label = "PIN", type = "password"),
        )
        val plan = assertNotNull(analyze(structure, focused = "f1"))
        assertEquals("f1", plan.usernameId)
        assertEquals("f2", plan.passwordId)
    }

    @Test
    fun ownPackageIsIgnored() {
        val structure = appWindow(
            OWN_PACKAGE,
            editText("pw", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
        assertNull(analyze(structure, focused = "pw"))
    }

    @Test
    fun ownPackageWebViewIsIgnoredToo() {
        val structure = browserPage("example.com", input("user"), input("pass", type = "password"), packageName = OWN_PACKAGE)
        assertNull(analyze(structure, focused = "pass"))
    }

    @Test
    fun noLoginFieldsGiveNoSuggestion() {
        val structure = browserPage(
            "shop.example.com",
            input("q", type = "search"),
            input("comment", label = "Kommentar"),
        )
        assertNull(analyze(structure, focused = "comment"))
    }

    @Test
    fun appTargetUsesPackageAndLabel() {
        val structure = appWindow(
            "com.paypal.android.p2pmobile",
            editText("email", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            editText("pw", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
        val plan = assertNotNull(analyze(structure, focused = "email"))
        assertNull(plan.webDomain)
        assertEquals(FillTarget.App("com.paypal.android.p2pmobile", "PayPal"), plan.target(appLabel = "PayPal"))
        assertEquals(FillTarget.App("com.paypal.android.p2pmobile", null), plan.target(appLabel = "  "))
    }

    @Test
    fun appWithHiddenPasswordStep() {
        val structure = appWindow(
            "de.example.bank",
            editText("user", idEntry = "login_name"),
            editText("pw", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, visible = false),
        )
        val plan = assertNotNull(analyze(structure, focused = "user"))
        assertEquals("user", plan.usernameId)
        assertNull(plan.passwordId)
    }

    @Test
    fun webViewInsideAnAppIsAWebTarget() {
        val structure = FakeStructure(
            "de.example.shop",
            FakeNode(
                children = listOf(
                    FakeNode(
                        webDomain = "login.example.de",
                        children = listOf(input("u", autocomplete = "username"), input("p", type = "password")),
                    ),
                ),
            ),
        )
        val plan = assertNotNull(analyze(structure, focused = "p"))
        assertEquals(FillTarget.Web("login.example.de", "de.example.shop"), plan.target(appLabel = "Shop"))
        assertNull(plan.webScheme)
    }

    @Test
    fun fieldsOfDifferentOriginsAreNotFilledTogether() {
        val structure = browserPage(
            "www.example.com",
            input("user", autocomplete = "username"),
            input("pass", type = "password", domain = "evil.example.net"),
        )
        val fromUser = assertNotNull(analyze(structure, focused = "user"))
        assertEquals("user", fromUser.usernameId)
        assertNull(fromUser.passwordId)
        assertEquals("www.example.com", fromUser.webDomain)

        val fromPassword = assertNotNull(analyze(structure, focused = "pass"))
        assertNull(fromPassword.usernameId)
        assertEquals("pass", fromPassword.passwordId)
        assertEquals("evil.example.net", fromPassword.webDomain)
    }

    @Test
    fun blankWebDomainMeansApp() {
        assertEquals(FillTarget.App("com.example", "Example"), fillTargetOf("com.example", " ", "Example"))
        assertEquals(FillTarget.Web("example.com", "com.example"), fillTargetOf("com.example", "example.com", "Example"))
    }
}
