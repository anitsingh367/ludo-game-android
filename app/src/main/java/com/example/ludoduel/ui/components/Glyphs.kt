package com.example.ludoduel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Small icons drawn in code (the app bundles no icon font). */
enum class Glyph { HELP, SOUND_ON, SOUND_OFF, SETTINGS }

/** A round, translucent icon button for the slim top bar. */
@Composable
fun GlyphButton(glyph: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { drawGlyph(glyph, Color.White) }
    }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val stroke = Stroke(w * 0.11f, cap = StrokeCap.Round)
    when (glyph) {
        Glyph.HELP -> {
            // A question mark: an arc, a short stem and a dot.
            drawArc(color, 180f, 230f, false, Offset(w * 0.25f, w * 0.08f), Size(w * 0.5f, w * 0.46f), style = stroke)
            drawLine(color, Offset(w * 0.5f, w * 0.56f), Offset(w * 0.5f, w * 0.68f), stroke.width, StrokeCap.Round)
            drawCircle(color, w * 0.07f, Offset(w * 0.5f, w * 0.88f))
        }
        Glyph.SOUND_ON, Glyph.SOUND_OFF -> {
            val speaker = Path().apply {
                moveTo(w * 0.08f, w * 0.36f); lineTo(w * 0.28f, w * 0.36f); lineTo(w * 0.5f, w * 0.14f)
                lineTo(w * 0.5f, w * 0.86f); lineTo(w * 0.28f, w * 0.64f); lineTo(w * 0.08f, w * 0.64f); close()
            }
            drawPath(speaker, color)
            if (glyph == Glyph.SOUND_ON) {
                drawArc(color, -45f, 90f, false, Offset(w * 0.42f, w * 0.3f), Size(w * 0.36f, w * 0.4f), style = stroke)
                drawArc(color, -50f, 100f, false, Offset(w * 0.4f, w * 0.12f), Size(w * 0.56f, w * 0.76f), style = stroke)
            } else {
                drawLine(color, Offset(w * 0.62f, w * 0.34f), Offset(w * 0.92f, w * 0.66f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.92f, w * 0.34f), Offset(w * 0.62f, w * 0.66f), stroke.width, StrokeCap.Round)
            }
        }
        Glyph.SETTINGS -> {
            // A gear: eight teeth around a ring.
            val c = Offset(w / 2, w / 2)
            for (i in 0 until 8) {
                rotate(i * 45f, c) {
                    drawRect(color, Offset(w * 0.42f, w * 0.02f), Size(w * 0.16f, w * 0.2f))
                }
            }
            drawCircle(color, w * 0.33f, c)
            drawCircle(Color.Black.copy(alpha = 0.35f), w * 0.13f, c)
        }
    }
}
