package moe.gensoukyo.agentpulse.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import moe.gensoukyo.agentpulse.data.ColorSource
import moe.gensoukyo.agentpulse.data.ThemeMode
import moe.gensoukyo.agentpulse.data.UiPreferences

@Immutable
data class SemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val info: Color,
    val infoContainer: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        success = Color(0xFF168A5B),
        successContainer = Color(0xFFD9F6E8),
        warning = Color(0xFFB86600),
        warningContainer = Color(0xFFFFE9C9),
        info = Color(0xFF4F63E7),
        infoContainer = Color(0xFFE8EBFF),
    )
}

val MaterialTheme.semanticColors: SemanticColors
    @Composable get() = LocalSemanticColors.current

@Composable
fun AgentPulseTheme(preferences: UiPreferences, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val dark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamic = preferences.colorSource == ColorSource.DYNAMIC && Build.VERSION.SDK_INT >= 31
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        else -> seedColorScheme(
            seed = Color(
                when (preferences.colorSource) {
                    ColorSource.DYNAMIC -> ColorSource.INDIGO.seedArgb!!
                    ColorSource.CUSTOM -> preferences.customSeedArgb
                    else -> preferences.colorSource.seedArgb!!
                },
            ),
            dark = dark,
        )
    }
    val semantic = if (dark) {
        SemanticColors(
            success = Color(0xFF70D6A5),
            successContainer = Color(0xFF123C2C),
            warning = Color(0xFFFFBD66),
            warningContainer = Color(0xFF4B3107),
            info = Color(0xFFB9C3FF),
            infoContainer = Color(0xFF29356F),
        )
    } else {
        SemanticColors(
            success = Color(0xFF168A5B),
            successContainer = Color(0xFFD9F6E8),
            warning = Color(0xFFB86600),
            warningContainer = Color(0xFFFFE9C9),
            info = Color(0xFF4F63E7),
            infoContainer = Color(0xFFE8EBFF),
        )
    }
    if (!view.isInEditMode) {
        SideEffect {
            context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AgentPulseTypography,
            shapes = AgentPulseShapes,
            content = content,
        )
    }
}

private val AgentPulseTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

private val AgentPulseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun seedColorScheme(seed: Color, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = blend(seed, Color.White, 0.64f),
        onPrimary = blend(seed, Color.Black, 0.68f),
        primaryContainer = blend(seed, Color.Black, 0.55f),
        onPrimaryContainer = blend(seed, Color.White, 0.82f),
        secondary = blend(seed, Color.White, 0.52f),
        secondaryContainer = blend(seed, Color.Black, 0.66f),
        tertiary = blend(rotate(seed), Color.White, 0.48f),
        tertiaryContainer = blend(rotate(seed), Color.Black, 0.62f),
        background = Color(0xFF111318),
        surface = Color(0xFF111318),
        surfaceVariant = blend(seed, Color.Black, 0.82f),
        outline = blend(seed, Color.White, 0.40f),
    )
} else {
    lightColorScheme(
        primary = blend(seed, Color.Black, 0.08f),
        onPrimary = Color.White,
        primaryContainer = blend(seed, Color.White, 0.82f),
        onPrimaryContainer = blend(seed, Color.Black, 0.62f),
        secondary = blend(seed, Color.Black, 0.22f),
        secondaryContainer = blend(seed, Color.White, 0.88f),
        tertiary = blend(rotate(seed), Color.Black, 0.12f),
        tertiaryContainer = blend(rotate(seed), Color.White, 0.84f),
        background = Color(0xFFF9FAFF),
        surface = Color(0xFFF9FAFF),
        surfaceVariant = blend(seed, Color.White, 0.93f),
        outline = blend(seed, Color.White, 0.52f),
    )
}

private fun blend(first: Color, second: Color, ratio: Float): Color = Color(
    ColorUtils.blendARGB(first.toArgb(), second.toArgb(), ratio),
)

private fun rotate(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[0] = (hsl[0] + 42f) % 360f
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
