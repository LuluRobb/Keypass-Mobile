package de.lulurobb.keypassmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing goes through key events (performKeyInput) and not performTextInput: the latter needs the
 * InsertTextAtCursor action, which the cleared semantics deliberately do not offer (the cursor
 * position is not known to the String-based callers).
 *
 * The plain Compose text fields must not carry any autofill semantics: Compose 1.11 reports every
 * node with OnFillData to the AutofillManager, content included. All checks run on the unmerged
 * tree, so a node cannot hide its actions behind merging. The control test shows that a stock
 * BasicTextField does carry them, so the other tests cannot pass vacuously.
 */
@RunWith(AndroidJUnit4::class)
class AutofillSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    // OnAutofillText is deprecated in favor of OnFillData but still reported by older code paths.
    @Suppress("DEPRECATION")
    private fun SemanticsNodeInteractionsProvider.assertNoAutofillSemantics() {
        val autofillRelated = listOf(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnFillData),
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnAutofillText),
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentType),
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDataType),
            SemanticsMatcher.keyIsDefined(SemanticsProperties.FillableData),
        )
        for (matcher in autofillRelated) {
            onAllNodes(matcher, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun stockTextFieldCarriesFillData() {
        rule.setContent { BasicTextField(value = "x", onValueChange = {}) }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnFillData), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun plainFieldHasNoAutofillSemantics() {
        rule.setContent { PlainField(value = "smb.example", onValueChange = {}, label = "Host") }
        rule.assertNoAutofillSemantics()
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.assertContentDescriptionEquals("Host")
        assertEquals(AnnotatedString("smb.example"), field.fetchSemanticsNode().config[SemanticsProperties.EditableText])
    }

    @Test
    fun plainFieldWithErrorAndEmailKeyboardHasNoAutofillSemantics() {
        rule.setContent {
            PlainField(
                value = "a@b.c",
                onValueChange = {},
                label = "User",
                error = "Pflichtfeld",
                keyboardType = KeyboardType.Email,
            )
        }
        rule.assertNoAutofillSemantics()
        val config = rule.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals("Pflichtfeld", config[SemanticsProperties.Error])
    }

    @Test
    fun plainFieldAnnouncesHelperOnlyWithoutError() {
        rule.setContent {
            Column {
                PlainField(value = "", onValueChange = {}, label = "User", helper = "Hilfe", modifier = Modifier.testTag("helper"))
                PlainField(
                    value = "", onValueChange = {}, label = "User", helper = "Hilfe", error = "Fehler",
                    modifier = Modifier.testTag("error"),
                )
            }
        }
        rule.onNode(hasTestTag("helper"), useUnmergedTree = true).assertContentDescriptionEquals("User, Hilfe")
        rule.onNode(hasTestTag("error"), useUnmergedTree = true).assertContentDescriptionEquals("User")
    }

    @Test
    fun editableFlagFollowsEnabledState() {
        rule.setContent {
            Column {
                PlainField(value = "", onValueChange = {}, label = "On", modifier = Modifier.testTag("on"))
                PlainField(value = "", onValueChange = {}, label = "Off", enabled = false, modifier = Modifier.testTag("off"))
            }
        }
        val on = rule.onNode(hasTestTag("on"), useUnmergedTree = true).fetchSemanticsNode().config
        val off = rule.onNode(hasTestTag("off"), useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue(on[SemanticsProperties.IsEditable])
        assertTrue(!off[SemanticsProperties.IsEditable])
    }

    @Test
    fun callerTestTagSurvivesAndFieldStaysWithoutAutofillSemantics() {
        rule.setContent {
            PlainField(value = "", onValueChange = {}, label = "Host", modifier = Modifier.testTag("plain"))
        }
        rule.assertNoAutofillSemantics()
        val plain = rule.onNode(hasTestTag("plain"), useUnmergedTree = true)
        plain.assertContentDescriptionEquals("Host")
        assertTrue(SemanticsActions.SetText in plain.fetchSemanticsNode().config)
    }

    @Test
    fun imeActionGoInvokesCallback() {
        var calls = 0
        rule.setContent {
            PlainField(value = "", onValueChange = {}, label = "Go", imeAction = ImeAction.Go, onImeAction = { calls++ })
        }
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performImeAction()
        rule.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun imeActionNextMovesFocusToNextField() {
        rule.setContent {
            Column {
                PlainField(value = "", onValueChange = {}, label = "First", modifier = Modifier.testTag("first"))
                PlainField(value = "", onValueChange = {}, label = "Second", modifier = Modifier.testTag("second"))
            }
        }
        val first = rule.onNode(hasTestTag("first"), useUnmergedTree = true)
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.assertIsFocused()
        first.performImeAction()
        rule.waitForIdle()
        rule.onNode(hasTestTag("second"), useUnmergedTree = true).assertIsFocused()
        first.assertIsNotFocused()
    }

    @Test
    fun imeActionDoneClearsFocus() {
        rule.setContent {
            PlainField(value = "", onValueChange = {}, label = "Done", imeAction = ImeAction.Done)
        }
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.assertIsFocused()
        field.performImeAction()
        rule.waitForIdle()
        field.assertIsNotFocused()
    }

    @Test
    fun plainFieldTypingUpdatesValueAndFocuses() {
        var value by mutableStateOf("")
        rule.setContent { PlainField(value = value, onValueChange = { value = it }, label = "Port") }
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performKeyInput { pressKey(Key.Four); pressKey(Key.Four); pressKey(Key.Five) }
        rule.waitForIdle()
        assertEquals("445", value)
        field.assertIsFocused()
        assertEquals(AnnotatedString("445"), field.fetchSemanticsNode().config[SemanticsProperties.EditableText])
    }

    @Test
    fun plainFieldSetTextActionUpdatesValue() {
        var value by mutableStateOf("")
        rule.setContent { PlainField(value = value, onValueChange = { value = it }, label = "Share") }
        rule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("docs")) }
        rule.waitForIdle()
        assertEquals("docs", value)
    }

    @Test
    fun plainFieldClickActionFocusesField() {
        rule.setContent { PlainField(value = "", onValueChange = {}, label = "Domain") }
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        field.assertIsFocused()
    }

    @Test
    fun disabledPlainFieldIsNotEnabledAndHasNoSetText() {
        rule.setContent { PlainField(value = "x", onValueChange = {}, label = "Host", enabled = false) }
        rule.assertNoAutofillSemantics()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
            .assertIsNotEnabled()
        rule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun searchFieldHasNoAutofillSemanticsAndStillEdits() {
        var query by mutableStateOf("")
        rule.setContent { SearchField(query = query, onQueryChange = { query = it }) }
        rule.assertNoAutofillSemantics()
        val field = rule.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performKeyInput { pressKey(Key.B); pressKey(Key.A); pressKey(Key.N); pressKey(Key.K) }
        rule.waitForIdle()
        assertEquals("bank", query)
        assertEquals(AnnotatedString("bank"), field.fetchSemanticsNode().config[SemanticsProperties.EditableText])
        rule.assertNoAutofillSemantics()
    }
}
