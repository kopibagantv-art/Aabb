package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = SapphirePrimary,
    onPrimary = PureWhite,
    primaryContainer = IceBlueContainer,
    onPrimaryContainer = SapphireDeep,
    secondary = CobaltAccent,
    onSecondary = PureWhite,
    secondaryContainer = CrispSurfaceVariant,
    onSecondaryContainer = SapphireDeep,
    tertiary = SkyHighlight,
    onTertiary = PureWhite,
    tertiaryContainer = PaleIceSurface,
    onTertiaryContainer = SapphireDeep,
    background = SoftCanvas,
    onBackground = SlateInk,
    surface = PureWhite,
    onSurface = SlateInk,
    surfaceVariant = CrispSurfaceVariant,
    onSurfaceVariant = MutedSlate,
    outline = SubtleOutline,
    outlineVariant = HairlineDivider,
    error = CrimsonError,
    onError = PureWhite,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonError
)

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = IceBlueContainer,
    secondary = SkyHighlight,
    onSecondary = NightOnPrimary,
    secondaryContainer = NightSurfaceVariant,
    onSecondaryContainer = NightText,
    tertiary = SkyHighlight,
    onTertiary = NightOnPrimary,
    background = NightCanvas,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMutedText,
    outline = NightMutedText,
    outlineVariant = NightSurfaceVariant,
    error = CrimsonError,
    onError = PureWhite
)

val PdfSuperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PdfSuperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PdfSuperTypography,
        shapes = PdfSuperShapes,
        content = content
    )
}
