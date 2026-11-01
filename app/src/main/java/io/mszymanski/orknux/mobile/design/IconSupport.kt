package io.mszymanski.orknux.mobile.design

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

class IconPath(val d: String, val stroke: Boolean, val width: Float, val fill: Boolean)

/** Builds an icon from an SVG's own paths; drawn in one colour, which `Icon`'s tint replaces. */
internal fun orkIcon(name: String, size: Float, vararg paths: IconPath): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, size, size).apply {
        paths.forEach { p ->
            addPath(
                pathData = addPathNodes(p.d),
                fill = if (p.fill) SolidColor(Color.Black) else null,
                stroke = if (p.stroke) SolidColor(Color.Black) else null,
                strokeLineWidth = p.width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
