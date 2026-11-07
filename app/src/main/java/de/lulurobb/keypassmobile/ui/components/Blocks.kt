package de.lulurobb.keypassmobile.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.lulurobb.keypassmobile.ui.icons.KpmIcon

/** Horizontal screen margin. */
val ScreenPadding = 16.dp

/**
 * Scale for press feedback on top of the ripple: 0.97 while pressed, 120 ms.
 * Compose scales animations with the system animator duration, so this snaps
 * when the user turns animations off.
 */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource): () -> Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    return { scale.value }
}

enum class BigButtonStyle { Filled, Tonal }

/**
 * Full-width 56 dp pill button for the one main action at the bottom of a
 * screen. [loading] shows a spinner and blocks clicks while keeping the color.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    style: BigButtonStyle = BigButtonStyle.Filled,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    val scheme = MaterialTheme.colorScheme
    val buttonModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .graphicsLayer { scale().let { scaleX = it; scaleY = it } }
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    val content: @Composable RowScope.() -> Unit = {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
            icon != null -> KpmIcon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        if (loading || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
    when (style) {
        BigButtonStyle.Filled -> Button(
            onClick = { if (!loading) onClick() },
            modifier = buttonModifier,
            enabled = enabled && !loading,
            shape = CircleShape,
            contentPadding = padding,
            interactionSource = source,
            colors = if (loading) {
                ButtonDefaults.buttonColors(
                    disabledContainerColor = scheme.primary.copy(alpha = 0.6f),
                    disabledContentColor = scheme.onPrimary,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            content = content,
        )
        BigButtonStyle.Tonal -> FilledTonalButton(
            onClick = { if (!loading) onClick() },
            modifier = buttonModifier,
            enabled = enabled && !loading,
            shape = CircleShape,
            contentPadding = padding,
            interactionSource = source,
            content = content,
        )
    }
}

/** Container colors of a [Notice]. */
enum class NoticeTone { Warning, Error, Neutral, Positive }

/**
 * Inline notice (the draft's card, banner, hint and no-match blocks): icon,
 * optional title, text, optional actions aligned to the end.
 */
@Composable
fun Notice(
    tone: NoticeTone,
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    compact: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) = Notice(tone, icon, AnnotatedString(text), modifier, title, compact, actions)

@Composable
fun Notice(
    tone: NoticeTone,
    @DrawableRes icon: Int,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    title: String? = null,
    compact: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        NoticeTone.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoticeTone.Error -> scheme.errorContainer to scheme.onErrorContainer
        NoticeTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
        NoticeTone.Positive -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = if (compact) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
    ) {
        Column(
            Modifier.padding(
                start = 16.dp,
                end = if (actions != null) 12.dp else 16.dp,
                top = if (compact) 12.dp else 14.dp,
                bottom = if (actions != null) 4.dp else if (compact) 12.dp else 14.dp,
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpmIcon(icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            }
        }
    }
}

/** Colored section title (settings sections, picker sections). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, topPadding: Dp = 22.dp) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = topPadding, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Settings row: icon, title, sublines, optional trailing content. Clickable
 * when [onClick] is set; a switch row passes [checked] and [onCheckedChange]
 * so the whole row toggles.
 */
@Composable
fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    sublines: List<SettingsSubline> = emptyList(),
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    /** What TalkBack announces for the tap ("Im Browser öffnen"); only for rows with [onClick]. */
    onClickLabel: String? = null,
) {
    val interaction = when {
        checked != null && onCheckedChange != null -> Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
        onClick != null -> Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interaction)
            .heightIn(min = 56.dp)
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KpmIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.align(Alignment.Top).padding(top = 2.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            sublines.forEach { line ->
                Text(
                    line.text,
                    style = if (line.monospace) MonoBodyMedium else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** One line under a settings title; [monospace] for identifiers (paths, GUIDs). */
data class SettingsSubline(val text: String, val monospace: Boolean = false)

/** Body medium in the platform monospace face, for identifiers only. */
val MonoBodyMedium: androidx.compose.ui.text.TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.95f,
    )

/**
 * Segmented single choice (Material 3 segmented buttons), indented to the
 * text column of [SettingsRow].
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding + 40.dp, end = ScreenPadding)
            // Same height for all segments when a label wraps at large font scales.
            .height(IntrinsicSize.Min),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                modifier = Modifier.fillMaxHeight(),
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                // Two lines rather than an ellipsis at large font scales.
                label = { Text(label(option), maxLines = 2, textAlign = TextAlign.Center) },
            )
        }
    }
}

/** Full-screen busy state (decrypting the database). */
@Composable
fun BusyOverlay(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(interactionSource = null, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            LinearProgressIndicator(modifier = Modifier.width(200.dp))
        }
    }
}

/** Small pill beside a value ("passt"). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
