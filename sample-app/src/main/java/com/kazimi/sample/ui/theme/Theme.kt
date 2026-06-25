package com.kazimi.sample.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SampleAmoledColorScheme =
    darkColorScheme(
        primary = BlueBabyBlue,
        onPrimary = OnBlueDark,
        primaryContainer = BlueContainerDark,
        onPrimaryContainer = BlueBabyBlue,
        secondary = OnDarkVariant,
        onSecondary = AmoledBlack,
        tertiary = GreenLiteSage,
        onTertiary = AmoledBlack,
        tertiaryContainer = GreenContainerDark,
        onTertiaryContainer = GreenLiteSage,
        background = AmoledBlack,
        onBackground = OnDark,
        surface = AmoledBlack,
        onSurface = OnDark,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = OnDarkVariant,
        surfaceContainerLowest = AmoledBlack,
        surfaceContainerLow = DarkSurfaceContainer,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceVariant,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        error = RedError,
        onError = AmoledBlack,
    )

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SampleAmoledColorScheme,
        content = content,
    )
}
