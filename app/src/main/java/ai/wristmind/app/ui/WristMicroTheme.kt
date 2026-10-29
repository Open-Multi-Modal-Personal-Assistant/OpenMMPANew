package ai.wristmind.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Calibrated for a Full Android 15 rectangular watch (>2" diagonal) reporting `Smallest Width = 426dp`.
 *
 * Because 426dp squeezed onto a ~2-inch physical panel has ~2.3x higher effective density than a phone,
 * standard 14sp text and 48dp buttons become microscopic. WristMicroTheme enforces:
 *  - True AMOLED pitch-black background (`#000000`) for battery savings & outdoor glare contrast
 *  - 20sp–26sp typography and 76dp–88dp touch target heights
 */
object WristDimens {
    val PrimaryTouchHeight = 80.dp
    val SecondaryTouchHeight = 68.dp
    val CardCornerRadius = 22.dp
    val ScreenPadding = 12.dp
}

object WristColors {
    val AmoledBlack = Color(0xFF000000)
    val CardSurface = Color(0xFF10161B)
    val CardSurfaceElevated = Color(0xFF18222A)
    val EmeraldEdge = Color(0xFF00E676)
    val CyanCloud = Color(0xFF18FFFF)
    val AmberWarn = Color(0xFFFFCA28)
    val CoralDanger = Color(0xFFFF5252)
    val TextPrimary = Color(0xFFF4F8FB)
    val TextMuted = Color(0xFFA2B4C0)
}

private val WristColorScheme = darkColorScheme(
    primary = WristColors.EmeraldEdge,
    onPrimary = Color(0xFF002411),
    secondary = WristColors.CyanCloud,
    onSecondary = Color(0xFF002529),
    tertiary = WristColors.AmberWarn,
    background = WristColors.AmoledBlack,
    onBackground = WristColors.TextPrimary,
    surface = WristColors.CardSurface,
    onSurface = WristColors.TextPrimary,
    surfaceVariant = WristColors.CardSurfaceElevated,
    onSurfaceVariant = WristColors.TextMuted,
    error = WristColors.CoralDanger
)

private val WristTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 31.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 23.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 23.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp
    )
)

@Composable
fun WristMicroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WristColorScheme,
        typography = WristTypography,
        content = content
    )
}
