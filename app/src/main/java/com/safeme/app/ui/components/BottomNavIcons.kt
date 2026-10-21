package com.safeme.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val NavHomeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NavHome",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 11f)
            lineTo(12f, 3f)
            lineTo(21f, 11f)
            moveTo(5f, 9f)
            lineTo(5f, 20f)
            lineTo(19f, 20f)
            lineTo(19f, 9f)
        }
    }.build()
}

internal val NavBlockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NavBlock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(20f, 5f)
            lineTo(20f, 10f)
            curveTo(20f, 15f, 17f, 19f, 12f, 22f)
            curveTo(7f, 19f, 4f, 15f, 4f, 10f)
            lineTo(4f, 5f)
            close()
            moveTo(9f, 12f)
            lineTo(11f, 14f)
            lineTo(15f, 10f)
        }
    }.build()
}

internal val NavFocusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NavFocus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // COD-80: focus/target glyph instead of the calendar — two
            // concentric rings (r = 8 and r = 3 around 12,12), same 2 dp
            // round-cap stroke as its nav neighbours. Each ring is drawn as
            // two half arcs so the geometry is exact (no hairline gap).
            moveTo(12f, 4f)
            arcToRelative(8f, 8f, 0f, false, true, 0f, 16f)
            arcToRelative(8f, 8f, 0f, false, true, 0f, -16f)
            close()
            moveTo(12f, 9f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, 6f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, -6f)
            close()
        }
    }.build()
}

internal val NavProfileIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NavProfile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 12f)
            arcToRelative(4f, 4f, 0f, true, false, 0f, -8f)
            arcToRelative(4f, 4f, 0f, false, false, 0f, 8f)
            close()
            moveTo(4f, 21f)
            lineTo(4f, 20f)
            arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
            lineTo(18f, 21f)
        }
    }.build()
}
