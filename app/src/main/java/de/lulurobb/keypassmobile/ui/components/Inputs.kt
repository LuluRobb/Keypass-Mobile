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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
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
    NoKeyboardLearning {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
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
                onNext = { onImeAction?.invoke() ?: focus.moveFocus(FocusDirection.Down) },
                onDone = { onImeAction?.invoke() ?: focus.clearFocus() },
                onGo = { onImeAction?.invoke() },
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
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp)
                        .heightIn(min = 52.dp),
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
                                    stringResource(R.string.search_placeholder),
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
