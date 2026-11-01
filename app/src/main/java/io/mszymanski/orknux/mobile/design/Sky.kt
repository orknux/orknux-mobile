package io.mszymanski.orknux.mobile.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min
import kotlin.random.Random

private class Star(val x: Float, val y: Float, val alpha: Float)

private class Layer(val tile: Float, val radius: Float, val color: Color, val stars: List<Star>)

private fun layer(seed: Int, tile: Float, count: Int, radius: Float, color: Color, alphas: ClosedFloatingPointRange<Float>): Layer {
    val random = Random(seed)
    return Layer(
        tile,
        radius,
        color,
        List(count) {
            Star(
                random.nextFloat() * tile,
                random.nextFloat() * tile,
                alphas.start + random.nextFloat() * (alphas.endInclusive - alphas.start),
            )
        },
    )
}

/**
 * orknux.ai's sky, which the sign-in pages also have: three tiles of stars and a
 * blue wash. [drift] is for the screens with nothing to read; behind a list it
 * holds still, and [strength] keeps it behind the words.
 */
@Composable
fun Sky(modifier: Modifier = Modifier, drift: Boolean = false, strength: Float = 1f, washAt: Float = 0.45f) {
    val layers = remember {
        listOf(
            layer(11, 900f, 90, 1.1f, Ork.skyStarFar, 0.35f..0.7f),
            layer(23, 1300f, 45, 1.7f, Ork.skyStarMid, 0.5f..0.85f),
            layer(37, 1800f, 16, 2.3f, Ork.skyStarNear, 0.6f..0.95f),
        )
    }
    if (drift) {
        val moving by rememberInfiniteTransition(label = "sky").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(70_000, easing = LinearEasing), RepeatMode.Restart),
            label = "drift",
        )
        Canvas(modifier) { drawSky(layers, moving, strength, washAt) }
    } else {
        Canvas(modifier) { drawSky(layers, 0f, strength, washAt) }
    }
}

private fun DrawScope.drawSky(layers: List<Layer>, moved: Float, strength: Float, washAt: Float) {
    val centre = Offset(size.width / 2, size.height * washAt)
    val reach = min(size.width, size.height) * 0.95f
    drawRect(
        Brush.radialGradient(
            0f to Ork.skyWashStrong.copy(alpha = Ork.skyWashStrong.alpha * strength),
            0.55f to Ork.skyWashSoft.copy(alpha = Ork.skyWashSoft.alpha * strength * 0.6f),
            1f to Color.Transparent,
            center = centre,
            radius = reach,
        ),
    )
    layers.forEach { layer ->
        val rise = moved * layer.tile
        var tileX = 0f
        while (tileX < size.width) {
            var tileY = -layer.tile
            while (tileY < size.height) {
                layer.stars.forEach { star ->
                    val y = tileY + (star.y - rise + layer.tile) % layer.tile
                    if (y in 0f..size.height && tileX + star.x <= size.width) {
                        drawCircle(
                            layer.color.copy(alpha = star.alpha * strength),
                            radius = layer.radius,
                            center = Offset(tileX + star.x, y),
                        )
                    }
                }
                tileY += layer.tile
            }
            tileX += layer.tile
        }
    }
}
