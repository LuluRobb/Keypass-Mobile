package de.lulurobb.keypassmobile.ui.components

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.lulurobb.keypassmobile.R
import de.lulurobb.keypassmobile.ui.icons.KpmIcon
import de.lulurobb.keypassmobile.ui.icons.KpmIcons

/**
 * Holder for a secret typed into a [SecretField] (master password, SMB
 * password). The characters live only in the field's own edit buffer, never
 * in a Kotlin String (SE-04). Read them with [toCharArray], overwrite that
 * array after use and call [clear] when done.
 */
@Stable
class SecretInput {
    internal var editText: EditText? = null

    /** Number of typed characters (observable, for validation and labels). */
    var length: Int by mutableIntStateOf(0)
        internal set

    val isEmpty: Boolean get() = length == 0

    /** Fresh copy of the typed characters. The caller overwrites it after use. */
    fun toCharArray(): CharArray {
        val text = editText?.text ?: return CharArray(0)
        val out = CharArray(text.length)
        text.getChars(0, text.length, out, 0)
        return out
    }

    private var pending: CharArray? = null

    /** Puts [chars] into the field (copied; the caller may wipe its array afterwards). */
    fun set(chars: CharArray) {
        val edit = editText
        if (edit == null) {
            pending?.fill('\u0000')
            pending = chars.copyOf()
            length = chars.size
        } else {
            clear()
            edit.setText(chars, 0, chars.size)
            edit.setSelection(chars.size)
        }
    }

    internal fun attach(edit: EditText) {
        editText = edit
        pending?.let {
            edit.setText(it, 0, it.size)
            it.fill('\u0000')
            pending = null
        }
    }

    /** Overwrites the edit buffer in place, then empties it. */
    fun clear() {
        pending?.fill('\u0000')
        pending = null
        editText?.wipe()
    }
}

/** Overwrites the characters of the edit buffer in place, then empties it. */
private fun EditText.wipe() {
    val buffer = text ?: return
    val n = buffer.length
    if (n > 0) {
        buffer.replace(0, n, String(CharArray(n)))
        buffer.clear()
    }
}

@Composable
fun rememberSecretInput(): SecretInput = remember { SecretInput() }

/**
 * Password field in the Material 3 outlined style, built on a platform
 * EditText because Compose text fields are always offered to autofill
 * services. The EditText:
 * - has no autofill type and is not important for autofill, so no autofill
 *   service is asked to fill it,
 * - sets IME_FLAG_NO_PERSONALIZED_LEARNING and TYPE_TEXT_FLAG_NO_SUGGESTIONS
 *   (no learning, no suggestions, no autocorrect, EN-01),
 * - does not save its text into the instance state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretField(
    input: SecretInput,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val action by rememberUpdatedState(onImeAction)
    val scheme = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val textColor = (if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)).toArgb()
    val cursorColor = (if (isError) scheme.error else scheme.primary).toArgb()
    val selectionColor = scheme.primary.copy(alpha = 0.3f).toArgb()
    val cursorWidth = with(density) { 2.dp.roundToPx() }
    val imeOption = when (imeAction) {
        ImeAction.Go -> EditorInfo.IME_ACTION_GO
        ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
        else -> EditorInfo.IME_ACTION_DONE
    }

    // Tapping anywhere in the field focuses the EditText and opens the keyboard.
    val focusEdit = {
        input.editText?.let { edit ->
            if (edit.isEnabled && edit.requestFocus()) {
                edit.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, 0)
            }
        }
        Unit
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = focusEdit),
    ) {
    OutlinedTextFieldDefaults.DecorationBox(
        // Only emptiness matters here (floating label); the secret is never passed.
        value = if (input.isEmpty) "" else " ",
        innerTextField = {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    NoAutofillEditText(context).apply {
                        background = null
                        setPadding(0, 0, 0, 0)
                        isSingleLine = true
                        isSaveEnabled = false
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        // Read by screen readers; invisible, the decoration draws the label.
                        hint = label
                        setHintTextColor(android.graphics.Color.TRANSPARENT)
                        var focusInteraction: FocusInteraction.Focus? = null
                        setOnFocusChangeListener { _, focused ->
                            if (focused) {
                                focusInteraction = FocusInteraction.Focus().also { interaction.tryEmit(it) }
                            } else {
                                focusInteraction?.let { interaction.tryEmit(FocusInteraction.Unfocus(it)) }
                                focusInteraction = null
                            }
                        }
                        // Go is handled here; Done and Next keep their default behavior
                        // (close the keyboard, move to the next field) after the callback.
                        setOnEditorActionListener { _, id, _ ->
                            when (id) {
                                EditorInfo.IME_ACTION_GO -> { action(); true }
                                EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT -> { action(); false }
                                else -> false
                            }
                        }
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                input.length = s?.length ?: 0
                            }
                        })
                        input.attach(this)
                    }
                },
                update = { edit ->
                    if (input.editText !== edit) input.attach(edit)
                    val variation = if (revealed) {
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    val type = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    if (edit.inputType != type) {
                        val selection = edit.selectionEnd
                        edit.inputType = type
                        if (selection >= 0) edit.setSelection(selection)
                    }
                    // setInputType switches password fields to monospace; keep the text face.
                    edit.typeface = Typeface.DEFAULT
                    edit.imeOptions = imeOption or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                        EditorInfo.IME_FLAG_NO_FULLSCREEN
                    edit.isEnabled = enabled
                    edit.setTextColor(textColor)
                    edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, textStyle.fontSize.value)
                    edit.letterSpacing = textStyle.letterSpacing.value / textStyle.fontSize.value
                    edit.highlightColor = selectionColor
                    edit.textCursorDrawable = GradientDrawable().apply {
                        setColor(cursorColor)
                        setSize(cursorWidth, edit.lineHeight)
                    }
                },
                // The view is released before the owner's DisposableEffect runs clear(): wipe it here, a
                // detached EditText would keep the typed password in its buffer until it is collected.
                onRelease = { edit ->
                    edit.wipe()
                    if (input.editText === edit) input.editText = null
                },
            )
        },
        enabled = enabled,
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        interactionSource = interaction,
        isError = isError,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }, enabled = enabled) {
                KpmIcon(
                    id = if (revealed) KpmIcons.VisibilityOff else KpmIcons.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.action_hide_password else R.string.action_show_password,
                    ),
                )
            }
        },
        container = {
            OutlinedTextFieldDefaults.Container(
                enabled = enabled,
                isError = isError,
                interactionSource = interaction,
                colors = OutlinedTextFieldDefaults.colors(),
            )
        },
    )
    }
}

/**
 * EditText that never takes part in autofill. Not being important for
 * autofill is not enough: since Android 14 the framework still starts a fill
 * request for password fields. A view without autofill type is skipped first.
 */
private class NoAutofillEditText(context: Context) : EditText(context) {
    override fun getAutofillType(): Int = View.AUTOFILL_TYPE_NONE

    override fun getAutofillHints(): Array<String>? = null
}
