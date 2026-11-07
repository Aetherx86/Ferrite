package dev.aetherx86.ferrite.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.aetherx86.ferrite.resources.Res
import dev.aetherx86.ferrite.resources.ferrite_mono_bold
import dev.aetherx86.ferrite.resources.ferrite_mono_medium
import dev.aetherx86.ferrite.resources.ferrite_sans_black
import dev.aetherx86.ferrite.resources.ferrite_sans_bold
import dev.aetherx86.ferrite.resources.ferrite_sans_medium
import dev.aetherx86.ferrite.resources.ferrite_sans_regular
import org.jetbrains.compose.resources.Font

object Palette {
    val Ink = Color(0xFF06080E)
    val InkRaised = Color(0xFF0B0F17)
    val Panel = Color(0xFF10151F)
    val PanelHigh = Color(0xFF171E2A)
    val Hairline = Color(0xFF1E2633)
    val Text = Color(0xFFF3F5F9)
    val TextSoft = Color(0xFFB8C0CE)
    val TextDim = Color(0xFF828B9B)
    val TextFaint = Color(0xFF545D6D)
    val Accent = Color(0xFF16A8E0)
    val Roomy = Color(0xFF3AD58B)
    val Moderate = Color(0xFFF6C344)
    val Crowded = Color(0xFFF4544B)
    val RoomyDeep = Color(0xFF0F2A22)
    val ModerateDeep = Color(0xFF2B2415)
    val CrowdedDeep = Color(0xFF2E1718)
}

@Immutable
class AppFonts(val sans: FontFamily, val mono: FontFamily)

val LocalFonts = staticCompositionLocalOf<AppFonts> { error("No fonts") }

@Composable
fun rememberAppFonts(): AppFonts {
    val regular = Font(Res.font.ferrite_sans_regular, FontWeight.Normal)
    val medium = Font(Res.font.ferrite_sans_medium, FontWeight.Medium)
    val bold = Font(Res.font.ferrite_sans_bold, FontWeight.Bold)
    val black = Font(Res.font.ferrite_sans_black, FontWeight.Black)
    val monoMedium = Font(Res.font.ferrite_mono_medium, FontWeight.Medium)
    val monoBold = Font(Res.font.ferrite_mono_bold, FontWeight.Bold)
    return remember(regular, medium, bold, black, monoMedium, monoBold) {
        AppFonts(
            sans = FontFamily(regular, medium, bold, black),
            mono = FontFamily(monoMedium, monoBold),
        )
    }
}

/** Dark text on bright colors and light text on deep ones, whichever is easier to read. */
fun contrastOn(background: Color): Color = if (background.relativeLuminance() > 0.20f) Palette.Ink else Palette.Text

fun Color.relativeLuminance(): Float {
    fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else kotlin.math.exp(2.4f * kotlin.math.ln((c + 0.055f) / 1.055f))
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/** Lifts a line color that would disappear against the dark background, leaving its hue alone. */
fun visibleOnDark(line: Color): Color {
    val luminance = line.relativeLuminance()
    if (luminance >= 0.11f) return line
    val mix = ((0.11f - luminance) / 0.11f * 0.5f).coerceIn(0f, 0.5f)
    return Color(
        red = line.red + (1f - line.red) * mix,
        green = line.green + (1f - line.green) * mix,
        blue = line.blue + (1f - line.blue) * mix,
    )
}
