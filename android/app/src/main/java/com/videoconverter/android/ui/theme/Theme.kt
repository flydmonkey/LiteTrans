package com.videoconverter.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat

private fun seedLightScheme() = lightColorScheme(
    primary = Color(SeedColors.LightPrimary),
    onPrimary = Color(SeedColors.LightOnPrimary),
    primaryContainer = Color(SeedColors.LightPrimaryContainer),
    onPrimaryContainer = Color(SeedColors.LightOnPrimaryContainer),
    secondary = Color(SeedColors.LightSecondary),
    onSecondary = Color(SeedColors.LightOnSecondary),
    secondaryContainer = Color(SeedColors.LightSecondaryContainer),
    onSecondaryContainer = Color(SeedColors.LightOnSecondaryContainer),
    tertiary = Color(SeedColors.LightTertiary),
    onTertiary = Color(SeedColors.LightOnTertiary),
    tertiaryContainer = Color(SeedColors.LightTertiaryContainer),
    onTertiaryContainer = Color(SeedColors.LightOnTertiaryContainer),
    error = Color(SeedColors.LightError),
    onError = Color(SeedColors.LightOnError),
    errorContainer = Color(SeedColors.LightErrorContainer),
    onErrorContainer = Color(SeedColors.LightOnErrorContainer),
    background = Color(SeedColors.LightBackground),
    onBackground = Color(SeedColors.LightOnBackground),
    surface = Color(SeedColors.LightSurface),
    onSurface = Color(SeedColors.LightOnSurface),
    surfaceVariant = Color(SeedColors.LightSurfaceVariant),
    onSurfaceVariant = Color(SeedColors.LightOnSurfaceVariant),
    outline = Color(SeedColors.LightOutline),
    outlineVariant = Color(SeedColors.LightOutlineVariant),
)

private fun seedDarkScheme() = darkColorScheme(
    primary = Color(SeedColors.DarkPrimary),
    onPrimary = Color(SeedColors.DarkOnPrimary),
    primaryContainer = Color(SeedColors.DarkPrimaryContainer),
    onPrimaryContainer = Color(SeedColors.DarkOnPrimaryContainer),
    secondary = Color(SeedColors.DarkSecondary),
    onSecondary = Color(SeedColors.DarkOnSecondary),
    secondaryContainer = Color(SeedColors.DarkSecondaryContainer),
    onSecondaryContainer = Color(SeedColors.DarkOnSecondaryContainer),
    tertiary = Color(SeedColors.DarkTertiary),
    onTertiary = Color(SeedColors.DarkOnTertiary),
    tertiaryContainer = Color(SeedColors.DarkTertiaryContainer),
    onTertiaryContainer = Color(SeedColors.DarkOnTertiaryContainer),
    error = Color(SeedColors.DarkError),
    onError = Color(SeedColors.DarkOnError),
    errorContainer = Color(SeedColors.DarkErrorContainer),
    onErrorContainer = Color(SeedColors.DarkOnErrorContainer),
    background = Color(SeedColors.DarkBackground),
    onBackground = Color(SeedColors.DarkOnBackground),
    surface = Color(SeedColors.DarkSurface),
    onSurface = Color(SeedColors.DarkOnSurface),
    surfaceVariant = Color(SeedColors.DarkSurfaceVariant),
    onSurfaceVariant = Color(SeedColors.DarkOnSurfaceVariant),
    outline = Color(SeedColors.DarkOutline),
    outlineVariant = Color(SeedColors.DarkOutlineVariant),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(ShapeTokens.ExtraSmall),
    small = RoundedCornerShape(ShapeTokens.Small),
    medium = RoundedCornerShape(ShapeTokens.Medium),
    large = RoundedCornerShape(ShapeTokens.Large),
    extraLarge = RoundedCornerShape(ShapeTokens.ExtraLarge),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when (
        colorSchemeChoice(darkTheme, dynamicColor, Build.VERSION.SDK_INT)
    ) {
        ColorSchemeChoice.DynamicDark -> dynamicDarkColorScheme(context)
        ColorSchemeChoice.DynamicLight -> dynamicLightColorScheme(context)
        ColorSchemeChoice.SeedDark -> seedDarkScheme()
        ColorSchemeChoice.SeedLight -> seedLightScheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content,
    )
}

@Composable
fun LightTranscodeTheme(content: @Composable () -> Unit) {
    AppTheme(content = content)
}
