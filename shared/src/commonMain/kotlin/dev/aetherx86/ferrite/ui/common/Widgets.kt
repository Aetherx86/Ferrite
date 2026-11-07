package dev.aetherx86.ferrite.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.data.Line

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Panel)
            .border(1.dp, Palette.Hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Label(text, size = 13.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

/** A line's round mark. Short names shrink to fit, so a two-character mark stays inside its circle. */
@Composable
fun LineMark(line: Line, size: Dp = 24.dp) {
    val color = Color(line.colorArgb)
    Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        FitText(
            line.short,
            maxSize = (size.value * 0.58f).sp,
            color = contrastOn(color),
            weight = FontWeight.Bold,
            alignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = size * 0.08f),
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Palette.Accent) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) accent else Palette.Panel)
            .border(1.dp, if (selected) accent else Palette.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, size = 13.sp, weight = FontWeight.Bold, color = if (selected) contrastOn(accent) else Palette.TextSoft)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Icon? = null, color: Color = Palette.Accent) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            AppIcon(icon, contrastOn(color), size = 18.dp)
            Spacer(Modifier.width(7.dp))
        }
        Label(text, size = 14.sp, weight = FontWeight.Bold, color = contrastOn(color))
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Icon? = null) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(Palette.PanelHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            AppIcon(icon, Palette.TextSoft, size = 18.dp)
            Spacer(Modifier.width(7.dp))
        }
        Label(text, size = 14.sp, weight = FontWeight.Bold, color = Palette.Text)
    }
}

@Composable
fun IconButton(icon: Icon, label: String, onClick: () -> Unit, tint: Color = Palette.Text, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint, size = 22.dp)
    }
}

@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: Icon = Icon.SEARCH,
    onSubmit: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val family = LocalFonts.current.sans
    Row(
        modifier.fillMaxWidth().clip(shape).background(Palette.Panel).border(1.dp, Palette.Hairline, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(leading, Palette.TextDim, size = 20.dp)
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Label(placeholder, size = 15.sp, color = Palette.TextFaint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = textStyle(family, 15.sp, FontWeight.Medium, Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Icon.CLOSE, Palette.TextDim, size = 16.dp)
            }
        }
    }
}

/** Says plainly why a feature has nothing live to show, and what to do about it. */
@Composable
fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Panel(modifier) {
        Label(title, size = 15.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Label(body, size = 13.sp, color = Palette.TextSoft, maxLines = 6)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(action, onAction)
                if (secondary != null && onSecondary != null) GhostButton(secondary, onSecondary)
            }
        }
    }
}

@Composable
fun SourceTag(text: String, live: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(if (live) Palette.Accent.copy(alpha = 0.22f) else Palette.PanelHigh, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Label(text, size = 10.sp, weight = FontWeight.Bold, color = if (live) Palette.Accent else Palette.TextDim)
    }
}

/** Swallows taps so they do not reach whatever is behind a sheet. */
@Composable
fun Modifier.consumeTaps(): Modifier = this.clickable(remember { MutableInteractionSource() }, indication = null) {}
