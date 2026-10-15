package com.nexbank.designlibrary

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens. Every screen in the app reads from here - never hard-code colors/spacing in features. */
object NexColors {
    val Primary = Color(0xFF00695C)
    val OnPrimary = Color.White
    val Success = Color(0xFF2E7D32)
    val Warning = Color(0xFFEF6C00)
    val Danger = Color(0xFFC62828)
    val Neutral = Color(0xFF546E7A)
}

object NexSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

private val LightScheme = lightColorScheme(primary = NexColors.Primary, onPrimary = NexColors.OnPrimary, error = NexColors.Danger)
private val DarkScheme = darkColorScheme(primary = Color(0xFF4DB6AC), onPrimary = Color.Black, error = Color(0xFFEF9A9A))

private val NexTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val NexShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun NexTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = NexTypography,
        shapes = NexShapes,
        content = content,
    )
}
