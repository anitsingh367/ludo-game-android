package com.example.ludoduel.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.Baloo
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoTheme
import kotlinx.coroutines.delay

/** Everything that changes about one player's die while it animates. Written by [GameAnimator]. */
@Stable
class DieVisual {
    /** The value on top when it lands, or null for the blank "tap" face. */
    var face by mutableStateOf<Int?>(null)
    /** Cube rotation in degrees about X, Y, Z (see [DieCube]). */
    var rotX by mutableFloatStateOf(0f)
    var rotY by mutableFloatStateOf(0f)
    var rotZ by mutableFloatStateOf(0f)
    /** Height above the table in pixels; the shadow stays on the table and shrinks. */
    var liftPx by mutableFloatStateOf(0f)
    var scale3d by mutableFloatStateOf(1f)
    var shakeX by mutableFloatStateOf(0f)
    /** Golden glow burst for a six, 0..1. */
    val glow = Animatable(0f)
    /** Red flash for a cancelled third six, 0..1. */
    val redFlash = Animatable(0f)
    /** Slide-in when the die moves to this player's box, 0..1. */
    val enter = Animatable(1f)
    /** "+1 turn!" label floating up from the die; progress 0..1 (1 = hidden). */
    val floater = Animatable(1f)
    var floaterText by mutableStateOf("")

    /** Shows [value] (or the blank face) at rest. */
    fun rest(value: Int?) {
        face = value
        val (x, y, z) = if (value == null) Triple(0f, 0f, 0f) else DieCube.restAngles(value)
        rotX = x
        rotY = y
        rotZ = z
        liftPx = 0f
        scale3d = 1f
        shakeX = 0f
    }
}

/**
 * A real 3D die drawn in a Canvas: an ivory cube with rounded faces, black pips and the 1-pip in the
 * player's color, lit from the top left, with a soft shadow on the table. Tapping rolls when
 * [enabled]; while waiting for a tap it wiggles every 2 seconds to invite one.
 */
@Composable
fun Die(
    visual: DieVisual,
    color: PlayerColor,
    enabled: Boolean,
    description: String,
    onRoll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLudoPalette.current.of(color)
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        while (enabled) {
            delay(2_000)
            for (target in listOf(12f, -10f, 7f, -4f, 0f)) wiggle.animateTo(target, tween(70))
        }
        wiggle.snapTo(0f)
    }
    val measurer = rememberTextMeasurer()
    Box(
        modifier
            .graphicsLayer {
                translationX = visual.shakeX + (1f - visual.enter.value) * 40.dp.toPx()
                alpha = visual.enter.value
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRoll,
            )
            .semantics { contentDescription = description },
    ) {
        // Shadow on the table: smaller and lighter while the die is in the air.
        Canvas(Modifier.fillMaxSize()) {
            val up = (visual.liftPx / 24.dp.toPx()).coerceIn(0f, 1f)
            val w = size.width * 0.78f * (1f - 0.45f * up)
            val h = size.height * 0.2f * (1f - 0.45f * up)
            drawOval(Color.Black.copy(alpha = 0.3f * (1f - 0.55f * up)), Offset((size.width - w) / 2, size.height * 0.84f - h / 2), Size(w, h))
            drawGlow(visual.glow.value)
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -visual.liftPx
                    scaleX = visual.scale3d
                    scaleY = visual.scale3d
                    rotationZ = wiggle.value
                },
        ) {
            drawDieCube(visual.face, visual.rotX, visual.rotY, visual.rotZ, colors.main, visual.redFlash.value)
            if (visual.face == null && enabled) {
                val layout = measurer.measure(
                    "TAP",
                    TextStyle(color = Color(0xFF8D8375), fontSize = (size.width * 0.2f).toSp(), fontWeight = FontWeight.ExtraBold, fontFamily = Baloo),
                )
                drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f - size.height * 0.02f))
            }
        }
    }
}

private fun DrawScope.drawGlow(glow: Float) {
    if (glow <= 0f) return
    val radius = size.minDimension * (0.55f + 0.5f * glow)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE082).copy(alpha = glow), Color(0xFFFFC107).copy(alpha = glow * 0.6f), Color.Transparent), center, radius),
        radius,
        center,
    )
}

private val IVORY = Color(0xFFFBF6EA)
private val IVORY_SHADE = Color(0xFF9C9281)
private val PIP = Color(0xFF1E1B18)

/**
 * The cube rotated by ([ax], [ay], [az]) degrees: only faces pointing at the viewer are drawn, back
 * to front, each shaded by its angle to the light. [face] null draws the front face blank (the "tap"
 * face). Also used by the home and waiting-room dice and the previews.
 */
fun DrawScope.drawDieCube(face: Int?, ax: Float, ay: Float, az: Float, onePipColor: Color, redFlash: Float = 0f, area: Rect = Rect(Offset.Zero, size)) {
    val scale = area.minDimension * 0.26f
    val mid = area.center
    fun toScreen(p: Vec3): Offset = DieCube.project(p).let { (x, y) -> Offset(mid.x + x * scale, mid.y + y * scale) }
    fun polygon(points: List<Vec3>): Path = Path().apply {
        points.forEachIndexed { i, p -> toScreen(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
        close()
    }

    val visible = DieCube.faces
        .map { f -> f to DieCube.transform(f.normal, ax, ay, az) }
        .filter { (_, n) -> DieCube.isVisible(n, n) }
        .sortedBy { (_, n) -> n.z }
    for ((f, n) in visible) {
        val light = (0.5f + 0.5f * maxOf(0f, n dot DieCube.light)).coerceIn(0f, 1f)
        var base = lerp(IVORY_SHADE, IVORY, light)
        if (redFlash > 0f) base = lerp(base, Color(0xFFFF1744), 0.7f * redFlash)
        fun local(a: Float, b: Float) = DieCube.transform(f.normal + f.u * a + f.v * b, ax, ay, az)
        // Edge band, slightly darker, then the rounded face on top: reads as a rounded cube.
        drawPath(polygon(listOf(local(-1f, -1f), local(1f, -1f), local(1f, 1f), local(-1f, 1f))), lerp(base, IVORY_SHADE, 0.35f))
        val rounded = buildList {
            val h = 0.9f
            val r = 0.32f
            for ((cx, cy, start) in listOf(Triple(h - r, h - r, 0.0), Triple(-(h - r), h - r, 90.0), Triple(-(h - r), -(h - r), 180.0), Triple(h - r, -(h - r), 270.0))) {
                for (k in 0..4) {
                    val a = Math.toRadians(start + k * 22.5)
                    add(local(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()))
                }
            }
        }
        drawPath(polygon(rounded), base)
        if (face == null && f.value == 1) continue // blank "tap" face
        for ((pu, pv) in DieCube.pips(f.value)) {
            val pipR = if (f.value == 1) 0.26f else 0.18f
            val circle = List(14) { k ->
                val a = k * 2 * Math.PI / 14
                local(pu + pipR * cos(a).toFloat(), pv + pipR * sin(a).toFloat())
            }
            val pipColor = if (f.value == 1) onePipColor else PIP
            drawPath(polygon(circle), lerp(pipColor.copy(alpha = 1f), Color.Black, 0.25f * (1f - light)))
        }
    }
}

@Preview(widthDp = 420, heightDp = 80)
@Composable
private fun DieFacesPreview() {
    LudoTheme {
        val palette = LocalLudoPalette.current
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf<Int?>(null, 1, 2, 3, 4, 5, 6).forEach { face ->
                val (x, y, z) = if (face == null) Triple(0f, 0f, 0f) else DieCube.restAngles(face)
                Canvas(Modifier.size(52.dp)) { drawDieCube(face, x, y, z, palette.red.main) }
            }
        }
    }
}

@Preview(widthDp = 420, heightDp = 80)
@Composable
private fun DieAnglesPreview() {
    LudoTheme {
        val palette = LocalLudoPalette.current
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(20f, 30f, 10f), Triple(55f, -40f, 25f), Triple(130f, 70f, -30f), Triple(200f, 150f, 60f), Triple(-60f, 250f, 120f), Triple(300f, -20f, 200f)).forEach { (x, y, z) ->
                Canvas(Modifier.size(52.dp)) { drawDieCube(4, x, y, z, palette.yellow.main) }
            }
        }
    }
}

@Preview(widthDp = 200, heightDp = 80)
@Composable
private fun DieEffectsPreview() {
    LudoTheme {
        val palette = LocalLudoPalette.current
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.size(56.dp)) { drawGlow(0.8f); drawDieCube(6, 0f, 180f, 0f, palette.yellow.main) }
            Canvas(Modifier.size(56.dp)) { drawDieCube(6, 0f, 180f, 0f, palette.red.main, redFlash = 1f) }
        }
    }
}
