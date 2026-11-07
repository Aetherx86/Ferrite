package dev.aetherx86.ferrite.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

fun textStyle(
    family: FontFamily,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Palette.Text,
    align: TextAlign = TextAlign.Start,
    tight: Boolean = false,
    tracking: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = size,
    fontWeight = weight,
    color = color,
    textAlign = align,
    letterSpacing = tracking,
    lineHeight = if (tight) 1.0.em else TextUnit.Unspecified,
    lineHeightStyle = if (tight) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both) else null,
)

/** Regular text that ends in an ellipsis rather than running out of its space. */
@Composable
fun Label(
    text: String,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Palette.Text,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    mono: Boolean = false,
    tight: Boolean = false,
) {
    val fonts = LocalFonts.current
    BasicText(
        text = text,
        modifier = modifier,
        style = textStyle(if (mono) fonts.mono else fonts.sans, size, weight, color, align, tight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
    )
}

/**
 * One line of text as large as its space allows, up to [maxSize].
 *
 * Station and destination names run from two to a dozen characters. Sizing them by measuring the real glyphs is
 * what keeps a long name from spilling out of its box.
 */
@Composable
fun FitText(
    text: String,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Black,
    color: Color = Palette.Text,
    minSize: TextUnit = 8.sp,
    alignment: Alignment = Alignment.CenterStart,
    mono: Boolean = false,
    tight: Boolean = true,
) {
    val fonts = LocalFonts.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val family = if (mono) fonts.mono else fonts.sans
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val fitted = remember(text, family, weight, maxSize, minSize, maxW, maxH, density.fontScale, density.density) {
            val probe = { size: Float ->
                val layout = measurer.measure(
                    text = text,
                    style = textStyle(family, size.sp, weight, color, tight = tight),
                    maxLines = 1,
                    softWrap = false,
                    constraints = Constraints(),
                )
                (maxW == Constraints.Infinity || layout.size.width <= maxW) &&
                    (maxH == Constraints.Infinity || layout.size.height <= maxH)
            }
            var low = minSize.value
            var high = maxSize.value
            if (probe(high)) {
                high
            } else {
                repeat(9) {
                    val middle = (low + high) / 2
                    if (probe(middle)) low = middle else high = middle
                }
                low
            }
        }
        Box {
            BasicText(
                text = text,
                style = textStyle(family, fitted.sp, weight, color, tight = tight),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
