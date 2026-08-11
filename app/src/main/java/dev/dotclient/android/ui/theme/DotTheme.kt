package dev.dotclient.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class DotThemeMode(val label: String) {
    AMOLED("AMOLED"),
    GRAPHITE("Graphite"),
    MATRIX("Matrix");

    companion object {
        fun fromStorage(value: String?): DotThemeMode =
            entries.firstOrNull { it.name == value } ?: AMOLED
    }
}

private val AmoledColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF101010),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF303030),
    error = Color(0xFFFF3B30),
)

private val GraphiteColors = darkColorScheme(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF151515),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF181818),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF444444),
    error = Color(0xFFFF5A52),
)

private val MatrixColors = darkColorScheme(
    primary = Color(0xFF78FF78),
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFD8FFD8),
    surface = Color(0xFF020A02),
    onSurface = Color(0xFFD8FFD8),
    surfaceVariant = Color(0xFF071507),
    onSurfaceVariant = Color(0xFF86B886),
    outline = Color(0xFF245A24),
    error = Color(0xFFFF5252),
)

private val CourierLike = FontFamily.Monospace
private val DotTypography = Typography(
    displayLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 56.sp, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    bodyLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = CourierLike, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.8.sp),
)

@Composable
fun DotTheme(themeMode: DotThemeMode = DotThemeMode.AMOLED, content: @Composable () -> Unit) {
    val colors = when (themeMode) {
        DotThemeMode.AMOLED -> AmoledColors
        DotThemeMode.GRAPHITE -> GraphiteColors
        DotThemeMode.MATRIX -> MatrixColors
    }
    MaterialTheme(colorScheme = colors, typography = DotTypography, content = content)
}
