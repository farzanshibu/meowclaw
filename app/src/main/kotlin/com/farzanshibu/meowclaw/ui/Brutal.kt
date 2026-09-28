package com.farzanshibu.meowclaw.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Neubrutalism: flat loud colour, thick ink outlines, hard offset shadows,
 * chunky type. Every surface in the app is built from these pieces.
 */
@Immutable
data class BrutalPalette(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val shadow: Color,
    val muted: Color,
    val yellow: Color = Color(0xFFFFD23F),
    val pink: Color = Color(0xFFFF7AB6),
    val blue: Color = Color(0xFF5B8CFF),
    val green: Color = Color(0xFF3DDC97),
    val purple: Color = Color(0xFFA78BFA),
    val orange: Color = Color(0xFFFF9F43),
    val red: Color = Color(0xFFFF5A5F),
) {
    /** Text drawn on an accent fill is always dark for contrast. */
    val onAccent: Color get() = Color(0xFF111111)
}

val LightBrutal = BrutalPalette(
    paper = Color(0xFFFFF4DE), surface = Color(0xFFFFFFFF), ink = Color(0xFF111111),
    shadow = Color(0xFF111111), muted = Color(0xFF5B5B5B),
)
val DarkBrutal = BrutalPalette(
    paper = Color(0xFF141414), surface = Color(0xFF222222), ink = Color(0xFFF4F1EA),
    shadow = Color(0xFFF4F1EA), muted = Color(0xFFB5B0A6),
)

val LocalBrutal = staticCompositionLocalOf { LightBrutal }

object Brutal {
    val colors: BrutalPalette @Composable get() = LocalBrutal.current
    val Border = 2.5.dp
    val Shadow = 4.dp
    val Radius = 12.dp
    val Mono = FontFamily.Monospace
}

@Composable
fun MeowClawTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkBrutal else LightBrutal
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.yellow, onPrimary = p.onAccent, secondary = p.pink, onSecondary = p.onAccent,
            background = p.paper, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
            onSurfaceVariant = p.muted, outline = p.ink, outlineVariant = p.ink, error = p.red,
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.yellow, secondary = p.pink, onSecondary = p.onAccent,
            background = p.paper, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
            onSurfaceVariant = p.muted, outline = p.ink, outlineVariant = p.ink, error = p.red,
        )
    }
    val base = Typography()
    val type = Typography(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Black),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        bodyLarge = base.bodyLarge.copy(fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
    )
    CompositionLocalProvider(LocalBrutal provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** Ink outline plus a hard, unblurred shadow offset down-right. */
fun Modifier.brutal(
    fill: Color,
    ink: Color,
    shadow: Color,
    radius: Dp = Brutal.Radius,
    shadowOffset: Dp = Brutal.Shadow,
    border: Dp = Brutal.Border,
): Modifier = this
    .drawBehind {
        val o = shadowOffset.toPx()
        if (o > 0f) {
            drawRoundRect(
                color = shadow,
                topLeft = Offset(o, o),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(radius.toPx()),
            )
        }
    }
    .clip(RoundedCornerShape(radius))
    .background(fill)
    .border(border, ink, RoundedCornerShape(radius))

@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.colors.surface,
    radius: Dp = Brutal.Radius,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Brutal.colors
    Column(
        modifier.padding(end = Brutal.Shadow, bottom = Brutal.Shadow)
            .brutal(fill, c.ink, c.shadow, radius)
            .padding(padding),
        content = content,
    )
}

/** A button that sinks into its shadow when pressed. */
@Composable
fun BrutalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Brutal.colors.yellow,
    enabled: Boolean = true,
    radius: Dp = Brutal.Radius,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = Brutal.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shift by animateDpAsState(if (pressed && enabled) Brutal.Shadow else 0.dp, label = "press")
    val bg = if (enabled) fill else fill.copy(alpha = 0.45f)
    Box(modifier.padding(end = Brutal.Shadow, bottom = Brutal.Shadow)) {
        Row(
            Modifier.offset(x = shift, y = shift)
                .brutal(bg, c.ink, c.shadow, radius, shadowOffset = Brutal.Shadow - shift)
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            val textColor = if (fill.luminance() > 0.4f) c.onAccent else Color.White
            CompositionLocalProvider(LocalContentColor provides textColor) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = textColor)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun BrutalIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Brutal.colors.surface,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    BrutalButton(
        onClick, modifier, fill = fill, enabled = enabled, radius = 10.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(size * 0.5f))
        }
    }
}

/** Small uppercase label chip, e.g. "ON-DEVICE", "VISION". */
@Composable
fun Tag(text: String, fill: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Brutal.colors
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(fill)
            .border(1.5.dp, c.ink, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(12.dp), tint = c.onAccent)
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text.uppercase(), color = c.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Black,
            fontFamily = Brutal.Mono, letterSpacing = 0.5.sp,
        )
    }
}

/** Chunky on/off switch. */
@Composable
fun BrutalSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Brutal.colors
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, label = "knob")
    Box(
        modifier.size(width = 50.dp, height = 30.dp)
            .brutal(if (checked) c.green else c.surface, c.ink, c.shadow, 15.dp, shadowOffset = 0.dp)
            .clickable(role = Role.Switch) { onChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = knob, y = 2.dp).size(22.dp)
                .clip(RoundedCornerShape(11.dp)).background(c.ink),
        )
    }
}

@Composable
fun BrutalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Brutal.colors
    Column(modifier) {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = Brutal.Mono, color = c.ink)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().brutal(c.surface, c.ink, c.shadow, 10.dp, shadowOffset = 0.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = c.muted, fontSize = 14.sp)
                BasicTextField(
                    value, onValueChange,
                    singleLine = singleLine,
                    textStyle = TextStyle(color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(c.ink),
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = KeyboardActions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
    }
}

/** Section heading with a coloured block marker. */
@Composable
fun SectionTitle(title: String, accent: Color, subtitle: String? = null) {
    val c = Brutal.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).brutal(accent, c.ink, c.shadow, 3.dp, shadowOffset = 0.dp, border = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
    }
    subtitle?.let {
        Spacer(Modifier.height(2.dp))
        Text(it, fontSize = 12.5.sp, color = c.muted)
    }
}
