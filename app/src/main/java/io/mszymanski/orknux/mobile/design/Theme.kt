package io.mszymanski.orknux.mobile.design

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mszymanski.orknux.mobile.R

/**
 * The web interface's design tokens (`orknux-ui/src/styles/tokens.css`), dark
 * theme with the blue accent. Every colour on screen comes from here.
 */
object Ork {
    val bg = Color(0xFF09090B)
    val surface = Color(0xFF121214)
    val surfaceRaised = Color(0xFF18181B)
    val surfaceHover = Color(0xFF27272A)
    val border = Color(0xFF27272A)

    val textPrimary = Color(0xFFFAFAFA)
    val textBody = Color(0xFFE4E4E7)
    val textSecondary = Color(0xFFA1A1AA)
    val textMuted = Color(0xFF71717A)

    val accent = Color(0xFF4F7FC4)
    val accentSoft = Color(0x264F7FC4)
    val onAccent = Color(0xFFFFFFFF)

    val unreadBg = Color(0xFFE4E4E7)
    val unreadFg = Color(0xFF09090B)

    val thinking = Color(0xFFC4453D)
    val thinkingSoft = Color(0x29C4453D)
    /** Solid: the same faint red over the surface, so nothing shows through it. */
    val thinkingSurface = Color(0xFF1C1416)
    /** Behind a transcript: the sky is still there, but it stays out of the words. */
    val readingVeil = Color(0xD909090B)

    val cyan = Color(0xFF22D3EE)
    val cyanSoft = Color(0x2406B6D4)
    val violet = Color(0xFFC084FC)
    val violetSoft = Color(0x24A855F7)

    val danger = Color(0xFFF87171)
    val dangerSoft = Color(0x1AEF4444)
    val dangerBorder = Color(0x33EF4444)
    val amber = Color(0xFFF59E0B)
    val amberSoft = Color(0x1AF59E0B)

    val skyStarFar = Color(0xFFBED2FF)
    val skyStarMid = Color(0xFFAAC8FF)
    val skyStarNear = Color(0xFFFFFFFF)
    val skyWashStrong = Color(0x386F98D6)
    val skyWashSoft = Color(0x264F7FC4)

    val radius4 = 4.dp
    val radius6 = 6.dp
    val radius8 = 8.dp
    val radius12 = 12.dp
    val control = 44.dp
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val GeistSans = FontFamily(
    variable(R.font.geist, 400),
    variable(R.font.geist, 500),
    variable(R.font.geist, 600),
)

val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 500),
)

/** The small mono capitals the web client labels things with: USER CHATS, TOOL, THINKING. */
val MonoLabel = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    letterSpacing = 1.2.sp,
)

private val OrkTypography = Typography(
    titleLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = GeistSans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = GeistSans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = GeistSans, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = GeistSans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun OrknuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ork.accent,
            onPrimary = Ork.onAccent,
            primaryContainer = Ork.accentSoft,
            onPrimaryContainer = Ork.textPrimary,
            secondary = Ork.accent,
            background = Ork.bg,
            onBackground = Ork.textPrimary,
            surface = Ork.surface,
            onSurface = Ork.textPrimary,
            surfaceVariant = Ork.surfaceRaised,
            onSurfaceVariant = Ork.textSecondary,
            surfaceContainer = Ork.surfaceRaised,
            surfaceContainerHigh = Ork.surfaceRaised,
            surfaceContainerHighest = Ork.surfaceHover,
            surfaceContainerLow = Ork.surface,
            outline = Ork.border,
            outlineVariant = Ork.border,
            error = Ork.danger,
        ),
        typography = OrkTypography,
    ) {
        // There is no Surface under the sky, so nothing else would set this and text would be black.
        CompositionLocalProvider(LocalContentColor provides Ork.textPrimary, content = content)
    }
}
