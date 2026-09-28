package sh.gerra.again.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Camera first, so always dark: near-black around the picture, warm off-white text, and one soft
 * gold accent, the colour of an old print, for the one thing to press next.
 */
private val AgainColors = darkColorScheme(
    primary = Color(0xFFEBC98E),
    onPrimary = Color(0xFF2A1E08),
    primaryContainer = Color(0xFF3A3022),
    onPrimaryContainer = Color(0xFFF6E3C1),
    secondaryContainer = Color(0xFF2A2826),
    onSecondaryContainer = Color(0xFFEDE8E1),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF2EEE8),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFF2EEE8),
    surfaceVariant = Color(0xFF1F1E1C),
    onSurfaceVariant = Color(0xFFB8B2A9),
    surfaceContainerHigh = Color(0xFF1F1E1C),
    outline = Color(0xFF5A5650),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF5C1107),
    errorContainer = Color(0xFF4A1C15),
    onErrorContainer = Color(0xFFFFDAD3),
)

/** The default Material type, with a serif for the app's name: the one flourish. */
private val AgainTypography = Typography().let { base ->
    base.copy(
        displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 56.sp, lineHeight = 60.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** What is drawn over the camera picture: a translucent black that keeps white text legible on any scene. */
internal val Scrim = Color(0x99000000)

@Composable
internal fun AgainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AgainColors, typography = AgainTypography, content = content)
}
