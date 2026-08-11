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
fun DotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmoledColors,
        typography = DotTypography,
        content = content,
    )
}
