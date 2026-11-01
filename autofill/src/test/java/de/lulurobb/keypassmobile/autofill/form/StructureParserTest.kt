package de.lulurobb.keypassmobile.autofill.form

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Visibility rules (AF-11) and field collection. */
class StructureParserTest {

    private fun visibility(structure: FormStructure<String>): Map<String, Boolean> =
        StructureParser.parse(structure, focusedId = null).associate { it.autofillId to it.info.isVisible }

    @Test
    fun collectsTextFieldsInDocumentOrder() {
        val structure = browserPage(
            "example.com",
            input("user"),
            FakeNode(htmlTag = "div", children = listOf(input("pass", type = "password"))),
            FakeNode(autofillId = "toggle", isTextField = false),
        )
        val fields = StructureParser.parse(structure, focusedId = "pass")
        assertEquals(listOf("user", "pass"), fields.map { it.autofillId })
        assertEquals(listOf(false, true), fields.map { it.info.isFocused })
    }

    @Test
    fun invisibleNodeIsHidden() {
        val structure = browserPage("example.com", input("user"), input("pass", type = "password", hidden = true))
        assertEquals(mapOf("user" to true, "pass" to false), visibility(structure))
    }

    @Test
    fun hiddenAncestorHidesItsFields() {
        val step2 = FakeNode(isViewVisible = false, children = listOf(input("pass", type = "password")))
        val structure = appWindow("com.example.app", editText("user"), step2)
        assertEquals(mapOf("user" to true, "pass" to false), visibility(structure))
    }

    @Test
    fun zeroSizeFieldIsHiddenWhenSizesAreReported() {
        val structure = browserPage(
            "example.com",
            input("user"),
            input("pass", type = "password").copy(width = 0),
            input("pin", type = "password").copy(height = 0),
        )
        assertEquals(mapOf("user" to true, "pass" to false, "pin" to false), visibility(structure))
    }

    @Test
    fun zeroSizeIsIgnoredWhenNoFieldReportsASize() {
        val structure = appWindow(
            "com.example.flutter",
            editText("user").copy(width = 0, height = 0),
            editText("pass").copy(width = 0, height = 0),
        )
        assertEquals(mapOf("user" to true, "pass" to true), visibility(structure))
    }

    @Test
    fun htmlTypeHiddenIsHidden() {
        val structure = browserPage("example.com", input("csrf", type = "hidden"), input("user"))
        assertEquals(mapOf("csrf" to false, "user" to true), visibility(structure))
    }

    @Test
    fun chromiumVisibilityAttributeIsHonoured() {
        val structure = browserPage(
            "example.com",
            input("user", extra = mapOf("visibility" to "visible")),
            input("pass", type = "password", extra = mapOf("visibility" to "invisible")),
        )
        assertEquals(mapOf("user" to true, "pass" to false), visibility(structure))
    }

    @Test
    fun webDomainIsInheritedAndFieldDomainWins() {
        val structure = browserPage(
            "www.example.com",
            input("user"),
            input("frame-pass", type = "password", domain = "login.other.net"),
        )
        val fields = StructureParser.parse(structure, focusedId = null).associateBy { it.autofillId }
        assertEquals("www.example.com", fields.getValue("user").info.webDomain)
        assertEquals("https", fields.getValue("user").info.webScheme)
        assertEquals("login.other.net", fields.getValue("frame-pass").info.webDomain)
        assertEquals(null, fields.getValue("frame-pass").info.webScheme)
    }

    @Test
    fun nativeFieldsHaveNoWebDomain() {
        val structure = appWindow("com.example.app", editText("user"))
        val field = StructureParser.parse(structure, focusedId = "user").single()
        assertEquals(null, field.info.webDomain)
        assertTrue(field.info.isFocused)
        assertTrue(field.info.isVisible)
    }

    @Test
    fun goneWindowContentIsHidden() {
        val structure = FakeStructure(
            BRAVE,
            FakeNode(isViewVisible = false, children = listOf(input("user"))),
            FakeNode(children = listOf(input("pass", type = "password"))),
        )
        val fields = StructureParser.parse(structure, focusedId = null)
        assertFalse(fields[0].info.isVisible)
        assertTrue(fields[1].info.isVisible)
    }
}
