package dev.aetherx86.ferrite.ui.board

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The board is a designed page, not a flowing document, so everything on it scales from one unit.
 *
 * Landscape is designed at 1280 by 720 and portrait at 390 by 780. Type ignores the system font scale here, because
 * the sizes already fill the screen, and larger ones would run off it.
 */
class BoardDims(val widthDp: Float, val heightDp: Float, private val density: Density) {
    val landscape = widthDp > heightDp
    val unit: Float = if (landscape) {
        minOf(widthDp / 1280f, heightDp / 720f)
    } else {
        minOf(widthDp / 390f, heightDp / 780f)
    }

    /** Page size in design units. */
    val pageWidth = widthDp / unit
    val pageHeight = heightDp / unit

    val margin = if (landscape) 52f else 20f

    fun d(x: Float): Dp = (x * unit).dp

    /** A font size that keeps its proportion on any screen and at any system font scale. */
    fun t(x: Float): TextUnit = with(density) { (x * unit).dp.toSp() }

    fun px(x: Float): Float = x * unit * density.density
}

/** Colors of one version of the board. The flood is the same page with the line's color as the background. */
class BoardScheme(
    val background: androidx.compose.ui.graphics.Color?,
    val text: androidx.compose.ui.graphics.Color,
    val soft: androidx.compose.ui.graphics.Color,
    val dim: androidx.compose.ui.graphics.Color,
    val faint: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color,
    val rule: androidx.compose.ui.graphics.Color,
    val track: androidx.compose.ui.graphics.Color,
    val trackAhead: androidx.compose.ui.graphics.Color,
    val chevron: androidx.compose.ui.graphics.Color,
    val chevronAhead: androidx.compose.ui.graphics.Color,
    val stationFill: androidx.compose.ui.graphics.Color,
    val trainBody: androidx.compose.ui.graphics.Color,
    val trainOther: androidx.compose.ui.graphics.Color,
    val trainNumber: androidx.compose.ui.graphics.Color,
    val trainOtherNumber: androidx.compose.ui.graphics.Color,
    val trainWindow: androidx.compose.ui.graphics.Color,
    val stripe: androidx.compose.ui.graphics.Color,
    val flood: Boolean,
)
