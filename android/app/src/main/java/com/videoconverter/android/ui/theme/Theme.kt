package com.videoconverter.android.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun LightTranscodeTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val background = Color(LightTokens.Canvas)
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = background.toArgb()
            window.navigationBarColor = background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(LightTokens.Accent),
            onPrimary = Color(LightTokens.OnDark),
            background = background,
            surface = Color(LightTokens.Card),
            onBackground = Color(LightTokens.Ink),
            onSurface = Color(LightTokens.Ink),
            secondary = Color(LightTokens.Ink),
            onSecondary = Color(LightTokens.OnDark),
            error = Color(LightTokens.Accent),
        ),
        content = content,
    )
}
