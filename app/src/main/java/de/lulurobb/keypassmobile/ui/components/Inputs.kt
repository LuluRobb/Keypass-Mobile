package de.lulurobb.keypassmobile.ui.components

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onImeAction
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/**
 * Compose text input inside [content] asks the keyboard not to learn and not
 * to suggest: adds IME_FLAG_NO_PERSONALIZED_LEARNING to the editor's
 * imeOptions and TYPE_TEXT_FLAG_NO_SUGGESTIONS to its inputType. Compose has
 * no keyboard option for these flags, so the input method request is wrapped.
 * (The secret fields use [SecretField], which sets the flags on its EditText.)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val wrapped = PlatformTextInputMethodRequest { outAttributes: EditorInfo ->
                val connection: InputConnection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                if (outAttributes.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
                    outAttributes.inputType = outAttributes.inputType or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                connection
            }
            nextHandler.startInputMethod(wrapped)
        },
        content = content,
    )
}

/**
 * Modifier for a Compose text field that must never reach a third-party autofill
 * service. Compose 1.11 reports every text field that carries SemanticsActions.OnFillData
 * (foundation sets it on all text fields) to the AutofillManager, including its content,
 * and the window-level importantForAutofill has no effect on it. [clearAndSetSemantics]
 * drops all semantics of the field's layout node, so the field has no fill data, no
 * autofill text action and no content type any more.
 *
 * It sits right behind the caller's [modifier] in the chain. The semantics of a layout node
 * are calculated from the tail to the head and reset when a clearing modifier is met, so
 * everything behind it (the text field's own semantics and the decoration of the Material
 * field) is dropped, while semantics in [modifier] (for example a test tag) are kept.
 *
 * Clearing also wipes the accessibility semantics, including the descendants (label and
 * supporting text of the Material field), so the essentials are added back here: label
 * and [helper] as the content description, value, editable, enabled and focused state,
 * [error], IME action, set text, click and focus actions. Not available any more: everything
 * that needs the selection or the text layout (TextSelectionRange, GetTextLayoutResult,
 * SetSelection, InsertTextAtCursor, InputText, long click and cut, copy and paste in the
 * TalkBack menu), because the String-based API cannot supply them. Never add onFillData,
 * onAutofillText, contentType, contentDataType or fillableData here.
 */
@Composable
private fun withoutAutofill(
    modifier: Modifier,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    performImeAction: () -> Unit,
    enabled: Boolean = true,
    helper: String? = null,
    error: String? = null,
): Modifier {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    return modifier
        .clearAndSetSemantics {
            // The error is announced through error(), the helper only while there is no error.
            contentDescription = if (error == null && helper != null) "$label, $helper" else label
            editableText = AnnotatedString(value)
            isEditable = enabled
            focused = isFocused
            error?.let { error(it) }
            if (enabled) {
                setText { onValueChange(it.text); true }
                onClick { focusRequester.requestFocus(); keyboard?.show(); true }
                requestFocus { focusRequester.requestFocus() }
                onImeAction(imeAction) { performImeAction(); true }
            } else {
                disabled()
            }
        }
        .focusRequester(focusRequester)
        .onFocusChanged { isFocused = it.isFocused }
}

/** Plain single-line field for setup values (host, user, port, domain, share name). */
@Composable
fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    // The same action runs for the keyboard's action key and for the accessibility action.
    val performImeAction: () -> Unit = {
        when (imeAction) {
            ImeAction.Next -> onImeAction?.invoke() ?: focus.moveFocus(FocusDirection.Down)
            ImeAction.Done -> onImeAction?.invoke() ?: focus.clearFocus()
            else -> onImeAction?.invoke()
        }
    }
    NoKeyboardLearning {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = withoutAutofill(
                modifier, label, value, onValueChange, imeAction, performImeAction, enabled, helper, error,
            ).fillMaxWidth(),
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = (error ?: helper)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onNext = { performImeAction() },
                onDone = { performImeAction() },
                onGo = { performImeAction() },
            ),
        )
    }
}

/** Search field as a filled pill (the list's and the picker's top search). */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val placeholder = stringResource(R.string.search_placeholder)
    Surface(
        modifier = modifier.heightIn(min = 52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KpmIcon(KpmIcons.Search, contentDescription = null, modifier = Modifier.size(24.dp))
            NoKeyboardLearning {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = withoutAutofill(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 8.dp)
                            .heightIn(min = 52.dp),
                        label = placeholder,
                        value = query,
                        onValueChange = onQueryChange,
                        imeAction = ImeAction.Search,
                        performImeAction = { focus.clearFocus() },
                    ),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    KpmIcon(KpmIcons.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        }
    }
}
