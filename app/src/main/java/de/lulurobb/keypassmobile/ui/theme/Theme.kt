package de.lulurobb.keypassmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Color schemes taken from the approved UI draft (CSS tokens of ui-entwurf.html).
 * Roles that the draft does not name are filled in from the same tonal palettes.
 * No dynamic color: the app keeps its own teal identity on every wallpaper.
 */

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1D6A62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2E6),
    onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF8AD5C9),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFF785A0B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAF8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF5FAF8),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceTint = Color(0xFF1D6A62),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF5FAF8),
    surfaceDim = Color(0xFFD5DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFED),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AD5C9),
    onPrimary = Color(0xFF003732),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFA6F2E6),
    inversePrimary = Color(0xFF1D6A62),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFE9C26C),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceTint = Color(0xFF8AD5C9),
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4947),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343B39),
    surfaceDim = Color(0xFF0E1513),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1C),
    surfaceContainer = Color(0xFF1B2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
)

/**
 * App theme: Material 3 with the draft's colors, light or dark following the
 * system (UI-04). Typography and shapes are the Material defaults.
 */
@Composable
fun KeyPassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
