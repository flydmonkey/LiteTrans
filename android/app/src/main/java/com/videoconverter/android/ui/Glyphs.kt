package com.videoconverter.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.videoconverter.android.ui.theme.ShapeTokens

enum class AppGlyph { Video, Audio, Image, File, Document, Check }

@Composable
fun GlyphBadge(
    kind: AppGlyph,
    emphasized: Boolean,
    size: Dp = 36.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(ShapeTokens.Glyph))
            .background(if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AppGlyphIcon(
            kind = kind,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun AppGlyphIcon(
    kind: AppGlyph,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        when (kind) {
            AppGlyph.Video -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.18f),
                    size = Size(w * 0.84f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = stroke,
                )
                val play = Path().apply {
                    val cx = w * 0.42f
                    val cy = h * 0.5f
                    val s = h * 0.16f
                    moveTo(cx - s * 0.2f, cy - s)
                    lineTo(cx - s * 0.2f, cy + s)
                    lineTo(cx + s * 1.1f, cy)
                    close()
                }
                drawPath(play, color, style = Fill)
            }
            AppGlyph.Audio -> {
                drawOval(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.58f),
                    size = Size(w * 0.38f, h * 0.26f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.18f),
                    end = Offset(w * 0.5f, h * 0.72f),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.5f, h * 0.18f),
                    end = Offset(w * 0.82f, h * 0.3f),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round,
                )
            }
            AppGlyph.Image -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.1f, h * 0.18f),
                    size = Size(w * 0.8f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.1f),
                    style = stroke,
                )
                drawCircle(color = color, radius = w * 0.08f, center = Offset(w * 0.32f, h * 0.38f))
                val mountain = Path().apply {
                    moveTo(w * 0.18f, h * 0.72f)
                    lineTo(w * 0.42f, h * 0.46f)
                    lineTo(w * 0.58f, h * 0.6f)
                    lineTo(w * 0.7f, h * 0.5f)
                    lineTo(w * 0.82f, h * 0.72f)
                    close()
                }
                drawPath(mountain, color, style = Fill)
            }
            AppGlyph.File -> {
                val fold = w * 0.28f
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.1f)
                    lineTo(w * 0.72f - fold, h * 0.1f)
                    lineTo(w * 0.78f, h * 0.1f + fold)
                    lineTo(w * 0.78f, h * 0.9f)
                    lineTo(w * 0.22f, h * 0.9f)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(w * 0.72f - fold, h * 0.1f),
                    end = Offset(w * 0.72f - fold, h * 0.1f + fold),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.72f - fold, h * 0.1f + fold),
                    end = Offset(w * 0.78f, h * 0.1f + fold),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round,
                )
            }
            AppGlyph.Document -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.18f, h * 0.22f),
                    size = Size(w * 0.64f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.28f, h * 0.16f),
                    end = Offset(w * 0.82f, h * 0.16f),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.82f, h * 0.16f),
                    end = Offset(w * 0.82f, h * 0.7f),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.32f, h * 0.42f),
                    end = Offset(w * 0.68f, h * 0.42f),
                    strokeWidth = w * 0.08f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.32f, h * 0.56f),
                    end = Offset(w * 0.68f, h * 0.56f),
                    strokeWidth = w * 0.08f,
                    cap = StrokeCap.Round,
                )
            }
            AppGlyph.Check -> {
                val check = Path().apply {
                    moveTo(w * 0.18f, h * 0.52f)
                    lineTo(w * 0.4f, h * 0.74f)
                    lineTo(w * 0.84f, h * 0.26f)
                }
                drawPath(check, color, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round))
            }
        }
    }
}

private var convertTabIcon: ImageVector? = null

val ConvertTabIcon: ImageVector
    get() {
        convertTabIcon?.let { return it }
        convertTabIcon = ImageVector.Builder(
            name = "Convert",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 8f)
                lineToRelative(-4f, -4f)
                verticalLineToRelative(3f)
                horizontalLineTo(5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(11f)
                verticalLineToRelative(3f)
                close()
                moveTo(4f, 16f)
                lineToRelative(4f, 4f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(11f)
                verticalLineToRelative(-2f)
                horizontalLineTo(8f)
                verticalLineToRelative(-3f)
                close()
            }
        }.build()
        return convertTabIcon!!
    }
