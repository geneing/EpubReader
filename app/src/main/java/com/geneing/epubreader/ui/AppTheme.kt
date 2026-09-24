package com.geneing.epubreader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.geneing.epubreader.data.ThemeMode

private val Sage = Color(0xFF3E6655)
private val Parchment = Color(0xFFF7F6F1)
private val Ink = Color(0xFF262A26)

@Composable
fun EpubReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFB1D2BB),
            secondary = Color(0xFFB9CBBE),
            background = Color(0xFF181C19),
            surface = Color(0xFF222824),
            surfaceVariant = Color(0xFF303832),
        )
    } else {
        lightColorScheme(
            primary = Sage,
            secondary = Color(0xFF667B6D),
            background = Parchment,
            surface = Color.White,
            surfaceVariant = Color(0xFFECEEE8),
            onBackground = Ink,
            onSurface = Ink,
        )
    }

    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
