package com.gymcoach.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors: ColorScheme = lightColorScheme(
    primary = GreenPrimary,
    primaryContainer = GreenContainer,
    secondary = SlateSecondary,
    secondaryContainer = AmberContainer,
    tertiary = TealTertiary,
    tertiaryContainer = TealContainer,
    error = RedError,
    errorContainer = RedContainer,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = GreenPrimaryDark,
    primaryContainer = GreenContainerDark,
    secondary = SlateSecondaryDark,
    secondaryContainer = AmberContainerDark,
    tertiary = TealTertiaryDark,
    tertiaryContainer = TealContainerDark,
    error = RedErrorDark,
    errorContainer = RedContainerDark,
)

/**
 * §8.2: Material 3 with dynamic colour on API 31+ and the fixed fallback palette below; dark theme
 * follows the system.
 */
@Composable
fun GymCoachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
