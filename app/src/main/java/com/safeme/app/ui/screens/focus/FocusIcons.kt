package com.safeme.app.ui.screens.focus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val FocusCalendarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchCalendar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 5f)
            horizontalLineTo(17f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
            verticalLineTo(18f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
            horizontalLineTo(7f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
            verticalLineTo(8f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
            close()
            moveTo(4f, 10f)
            horizontalLineTo(20f)
            moveTo(9f, 3f)
            verticalLineTo(7f)
            moveTo(15f, 3f)
            verticalLineTo(7f)
        }
    }.build()
}

internal val FocusClockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchClock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, false, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, false, true, -18f, 0f)
            moveTo(12f, 7f)
            verticalLineTo(12f)
            lineToRelative(3f, 2f)
        }
    }.build()
}

internal val FocusSearchIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchSearch",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11f, 11f)
            arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
            arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
            moveTo(21f, 21f)
            lineToRelative(-4.3f, -4.3f)
        }
    }.build()
}

internal val FocusCheckIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchCheck",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 3f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 12f)
            lineToRelative(5f, 5f)
            lineToRelative(10f, -11f)
        }
    }.build()
}

internal val FocusNameIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchName",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 6f)
            horizontalLineTo(20f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
            moveTo(4f, 18f)
            horizontalLineTo(12f)
        }
    }.build()
}

internal val FocusInfoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchInfo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, false, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, false, true, -18f, 0f)
            moveTo(12f, 8f)
            horizontalLineTo(12.01f)
            moveTo(11f, 12f)
            horizontalLineTo(12f)
            verticalLineToRelative(4f)
            horizontalLineTo(13f)
        }
    }.build()
}

/** Small × for dismissing the a11y warning banner. */
internal val FocusCloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SchClose",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        val stroke = SolidColor(Color.Black)
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }.build()
}
