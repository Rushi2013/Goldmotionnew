package ai.jarvis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AMOLED design tokens from §12 — dark is the only theme, the accent stays cyan. */
@Immutable
data class JarvisColors(
    val amoled: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF0B0F14),
    val surfaceElevated: Color = Color(0xFF121A22),
    val cyan: Color = Color(0xFF00E5FF),
    val blue: Color = Color(0xFF3D7BFF),
    val success: Color = Color(0xFF22E39A),
    val warn: Color = Color(0xFFFFB020),
    val danger: Color = Color(0xFFFF4D6D),
    val text: Color = Color(0xFFE8F6FF),
    val muted: Color = Color(0xFF7A8CA0),
    val border: Color = Color(0x1F00E5FF),
    val bubbleOut: Color = Color(0xFF0E2A33),
    val bubbleIn: Color = Color(0xFF141B24),
    val onPrimary: Color = Color(0xFF00161C),
)

val LocalJarvisColors = staticCompositionLocalOf { JarvisColors() }

private val Scheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00161C),
    primaryContainer = Color(0xFF003642),
    onPrimaryContainer = Color(0xFF8DF4FF),
    secondary = Color(0xFF3D7BFF),
    onSecondary = Color(0xFF001233),
    tertiary = Color(0xFF22E39A),
    onTertiary = Color(0xFF00281A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8F6FF),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE8F6FF),
    surfaceVariant = Color(0xFF121A22),
    onSurfaceVariant = Color(0xFFA8BCCB),
    outline = Color(0xFF2A3A48),
    error = Color(0xFFFF4D6D),
    onError = Color(0xFF33000B),
)

private val JarvisTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

object Dimens {
    val cardRadius = 20.dp
    val bubbleRadius = 16.dp
    val gutter = 16.dp
    val gap = 8.dp
    val tap = 48.dp
}

@Composable
fun JarvisTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = JarvisColors()
    androidx.compose.runtime.CompositionLocalProvider(LocalJarvisColors provides colors) {
        MaterialTheme(
            colorScheme = Scheme,
            typography = JarvisTypography,
            content = content,
        )
    }
}
