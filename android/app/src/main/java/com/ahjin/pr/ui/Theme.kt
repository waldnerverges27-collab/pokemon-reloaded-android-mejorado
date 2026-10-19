package com.ahjin.pr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahjin.pr.R

/**
 * Google Sans Flex is the typeface Material 3 Expressive is designed around, and it is most of what
 * separates an expressive app from a plain Material one. It is a variable font, so the weight is
 * picked through a variation setting rather than separate files.
 */
private val SansFlex: FontFamily = FontFamily(
    Font(R.font.google_sans_flex, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.google_sans_flex, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(560))),
    Font(R.font.google_sans_flex, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(640))),
    Font(R.font.google_sans_flex, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(760))),
)

/** The expressive corner scale: rounder than baseline Material, which is what gives the app its shape. */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

/**
 * "Obsidiana y oro": near-black surfaces with a faint cool tint, warm off-white text and a single
 * champagne-gold accent. One accent colour is the whole trick: everything that matters (the play
 * button, the selected tab, section titles) is gold, and everything else stays out of its way.
 *
 * The wallpaper-driven dynamic colour that used to be here is gone on purpose: it made the app look
 * different on every phone, and a premium look is one that does not depend on the wallpaper.
 */
private val ObsidianScheme = darkColorScheme(
    primary = Color(0xFFE3C07B),
    onPrimary = Color(0xFF2E2100),
    primaryContainer = Color(0xFF4A3910),
    onPrimaryContainer = Color(0xFFFBDF9F),
    inversePrimary = Color(0xFF7D5C0E),
    secondary = Color(0xFFB8C0D6),
    onSecondary = Color(0xFF222A3B),
    secondaryContainer = Color(0xFF2D3550),
    onSecondaryContainer = Color(0xFFDAE1F7),
    tertiary = Color(0xFF86D3C0),
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF1E4F44),
    onTertiaryContainer = Color(0xFFA2F0DB),
    background = Color(0xFF08090C),
    onBackground = Color(0xFFEAE6DE),
    surface = Color(0xFF0B0C10),
    onSurface = Color(0xFFEAE6DE),
    surfaceVariant = Color(0xFF23252C),
    onSurfaceVariant = Color(0xFFB9B4AA),
    surfaceTint = Color(0xFFE3C07B),
    inverseSurface = Color(0xFFEAE6DE),
    inverseOnSurface = Color(0xFF2B2A27),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF7C776D),
    outlineVariant = Color(0xFF34353B),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF08090C),
    surfaceBright = Color(0xFF2A2D36),
    surfaceContainerLowest = Color(0xFF050608),
    surfaceContainerLow = Color(0xFF101217),
    surfaceContainer = Color(0xFF15171D),
    surfaceContainerHigh = Color(0xFF1B1E25),
    surfaceContainerHighest = Color(0xFF23262F),
)

/** The same idea on ivory: ink text, bronze instead of champagne so the accent keeps its contrast. */
private val IvoryScheme = lightColorScheme(
    primary = Color(0xFF7D5C0E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9C),
    onPrimaryContainer = Color(0xFF261A00),
    inversePrimary = Color(0xFFE3C07B),
    secondary = Color(0xFF596077),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E3F0),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF266B5C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA9F1DD),
    onTertiaryContainer = Color(0xFF00201A),
    background = Color(0xFFFBF8F1),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFE8E2D4),
    onSurfaceVariant = Color(0xFF4B473D),
    surfaceTint = Color(0xFF7D5C0E),
    inverseSurface = Color(0xFF31302B),
    inverseOnSurface = Color(0xFFF4F0E8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7C776A),
    outlineVariant = Color(0xFFCFC9B9),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFDCD9D0),
    surfaceBright = Color(0xFFFBF8F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2EA),
    surfaceContainer = Color(0xFFF0EDE4),
    surfaceContainerHigh = Color(0xFFEAE7DD),
    surfaceContainerHighest = Color(0xFFE4E1D7),
)

/** Appearance setting: follow the system, always dark, or always light. Dark is the default. */
object ThemeMode {
    const val SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2
}

@Composable
fun isDarkTheme(mode: Int): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    else -> true
}

/** The gold of the main actions: a gentle top-to-bottom sheen, never a flat fill. */
@Composable
fun goldBrush(): Brush {
    val gold = MaterialTheme.colorScheme.primary
    return Brush.verticalGradient(listOf(lerp(gold, Color.White, 0.22f), gold))
}

/** Tightens the big sizes and opens the small caps a touch: that is most of what "refined" is in type. */
private fun TextStyle.tracked(spacing: Double): TextStyle = copy(fontFamily = SansFlex, letterSpacing = spacing.sp)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReloadedTheme(mode: Int = ThemeMode.DARK, content: @Composable () -> Unit) {
    val scheme = if (isDarkTheme(mode)) ObsidianScheme else IvoryScheme

    val typography = remember {
        val base = Typography()
        Typography(
            displayLarge = base.displayLarge.tracked(-0.8),
            displayMedium = base.displayMedium.tracked(-0.6),
            displaySmall = base.displaySmall.tracked(-0.4),
            headlineLarge = base.headlineLarge.tracked(-0.4),
            headlineMedium = base.headlineMedium.tracked(-0.3),
            headlineSmall = base.headlineSmall.tracked(-0.2),
            titleLarge = base.titleLarge.tracked(-0.1),
            titleMedium = base.titleMedium.tracked(0.1),
            titleSmall = base.titleSmall.tracked(0.1),
            bodyLarge = base.bodyLarge.tracked(0.2),
            bodyMedium = base.bodyMedium.tracked(0.2),
            bodySmall = base.bodySmall.tracked(0.3),
            labelLarge = base.labelLarge.tracked(0.3),
            labelMedium = base.labelMedium.tracked(0.5),
            labelSmall = base.labelSmall.tracked(0.6),
        )
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
