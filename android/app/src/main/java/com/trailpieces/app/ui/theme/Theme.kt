package com.trailpieces.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForestGreen = Color(0xFF2D6A4F)
private val MossGreen = Color(0xFF40916C)
private val LeafLight = Color(0xFFD8F3DC)
private val BarkBrown = Color(0xFF1B4332)
private val Cream = Color(0xFFF8F9FA)

private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    secondary = MossGreen,
    onSecondary = Color.White,
    background = Cream,
    onBackground = BarkBrown,
    surface = Color.White,
    onSurface = BarkBrown,
    surfaceVariant = LeafLight,
    onSurfaceVariant = Color(0xFF52796F),
)

private val DarkColorScheme = darkColorScheme(
    primary = MossGreen,
    onPrimary = Color.Black,
    secondary = LeafLight,
    onSecondary = BarkBrown,
    background = Color(0xFF081C15),
    onBackground = LeafLight,
    surface = Color(0xFF1B4332),
    onSurface = LeafLight,
    surfaceVariant = Color(0xFF2D6A4F),
    onSurfaceVariant = Color(0xFFB7E4C7),
)

@Composable
fun TrailPiecesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
