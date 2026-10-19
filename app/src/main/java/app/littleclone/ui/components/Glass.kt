package app.littleclone.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.composed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.littleclone.ui.theme.LocalTokens
import app.littleclone.ui.theme.Type

/** Soft grey light blobs behind everything; this is what the glass panels appear to refract. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Box(
        modifier
            .fillMaxSize()
            .background(t.background)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(t.orbA, Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.06f),
                        radius = size.width * 0.95f,
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(t.orbB, Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.55f),
                        radius = size.width * 1.1f,
                    )
                )
            }
    )
}

/** A translucent panel with a light top edge, as on iOS. Android 11 has no cheap backdrop blur. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    val t = LocalTokens.current
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(t.glassTop, t.glassBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(t.strokeTop, t.strokeBottom)), shape)
    ) {
        content()
    }
}

/** Click handling with the iOS "squish" and no ripple. */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, label = "press")
    this
        .scale(scale)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

enum class ButtonKind { Solid, Glass }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Solid,
    enabled: Boolean = true,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(50)
    val color = if (kind == ButtonKind.Solid) t.onSolid else t.text
    val base = modifier.pressable(enabled = enabled, onClick = onClick).clip(shape)
    val styled = if (kind == ButtonKind.Solid) {
        base.background(if (enabled) t.solid else t.textTertiary)
    } else {
        base
            .background(Brush.verticalGradient(listOf(t.glassTop, t.glassBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(t.strokeTop, t.strokeBottom)), shape)
    }
    Box(styled.padding(horizontal = 20.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = Type.headline.copy(color = color, textAlign = TextAlign.Center))
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().height(6.dp).clip(shape).background(t.glassTop)) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(t.solid, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
                }
        )
    }
}

enum class Glyph { Plus, Close, Play, Check, Search, Trash, Clone }

/** Line icons drawn by hand so the app needs no icon library. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.1f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.Plus -> {
                drawLine(color, Offset(w * 0.5f, w * 0.18f), Offset(w * 0.5f, w * 0.82f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, w * 0.5f), Offset(w * 0.82f, w * 0.5f), sw, StrokeCap.Round)
            }
            Glyph.Close -> {
                drawLine(color, Offset(w * 0.22f, w * 0.22f), Offset(w * 0.78f, w * 0.78f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.78f, w * 0.22f), Offset(w * 0.22f, w * 0.78f), sw, StrokeCap.Round)
            }
            Glyph.Play -> {
                val p = Path().apply {
                    moveTo(w * 0.3f, w * 0.2f)
                    lineTo(w * 0.8f, w * 0.5f)
                    lineTo(w * 0.3f, w * 0.8f)
                    close()
                }
                drawPath(p, color)
            }
            Glyph.Check -> {
                val p = Path().apply {
                    moveTo(w * 0.2f, w * 0.55f)
                    lineTo(w * 0.42f, w * 0.75f)
                    lineTo(w * 0.8f, w * 0.28f)
                }
                drawPath(p, color, style = stroke)
            }
            Glyph.Search -> {
                drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.43f, w * 0.43f), style = stroke)
                drawLine(color, Offset(w * 0.63f, w * 0.63f), Offset(w * 0.84f, w * 0.84f), sw, StrokeCap.Round)
            }
            Glyph.Trash -> {
                drawLine(color, Offset(w * 0.2f, w * 0.27f), Offset(w * 0.8f, w * 0.27f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.4f, w * 0.15f), Offset(w * 0.6f, w * 0.15f), sw, StrokeCap.Round)
                val p = Path().apply {
                    moveTo(w * 0.27f, w * 0.27f)
                    lineTo(w * 0.32f, w * 0.85f)
                    lineTo(w * 0.68f, w * 0.85f)
                    lineTo(w * 0.73f, w * 0.27f)
                }
                drawPath(p, color, style = stroke)
            }
            Glyph.Clone -> {
                drawRoundRect(
                    color, topLeft = Offset(w * 0.12f, w * 0.12f), size = Size(w * 0.5f, w * 0.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = stroke,
                )
                drawRoundRect(
                    color, topLeft = Offset(w * 0.38f, w * 0.38f), size = Size(w * 0.5f, w * 0.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
                )
            }
        }
    }
}

/** Round glass button holding one glyph. */
@Composable
fun GlyphButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Box(
        modifier
            .pressable(onClick = onClick)
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(t.glassTop, t.glassBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(t.strokeTop, t.strokeBottom)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, t.text, size = 18.dp)
    }
}

@Composable
fun Label(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    BasicText(
        text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
