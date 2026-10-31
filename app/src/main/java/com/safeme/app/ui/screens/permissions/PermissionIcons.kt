package com.safeme.app.ui.screens.permissions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun iconBuilder(name: String): ImageVector.Builder =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

private val stroke = SolidColor(Color.Black)

private fun ImageVector.Builder.strokePath(pathBuilder: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = stroke,
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder,
    )
}

val ChevronIcon: ImageVector by lazy {
    iconBuilder("Chevron").apply {
        strokePath {
            moveTo(15f, 5f)
            lineToRelative(-7f, 7f)
            lineToRelative(7f, 7f)
        }
    }.build()
}

val InfoCircleIcon: ImageVector by lazy {
    iconBuilder("InfoCircle").apply {
        strokePath {
            moveTo(12f, 3f)
            arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
            arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
        }
        strokePath {
            moveTo(12f, 11f)
            verticalLineTo(16f)
        }
        strokePath {
            moveTo(12f, 8f)
            lineTo(12.01f, 8f)
        }
    }.build()
}

val CheckIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Check",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 13f)
            lineTo(9f, 17f)
            lineTo(19f, 7f)
        }
    }.build()
}

val BellIcon: ImageVector by lazy {
    iconBuilder("Bell").apply {
        strokePath {
            moveTo(18f, 8f)
            arcToRelative(6f, 6f, 0f, false, false, -12f, 0f)
            curveToRelative(0f, 7f, -3f, 9f, -3f, 9f)
            horizontalLineToRelative(18f)
            reflectiveCurveToRelative(-3f, -2f, -3f, -9f)
        }
        strokePath {
            moveTo(10.3f, 21f)
            arcToRelative(2f, 2f, 0f, false, false, 3.4f, 0f)
        }
    }.build()
}

val BatteryIcon: ImageVector by lazy {
    iconBuilder("Battery").apply {
        strokePath {
            moveTo(4f, 7f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, true, true, 2f, 2f)
            verticalLineToRelative(6f)
            arcToRelative(2f, 2f, 0f, true, true, -2f, 2f)
            horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, true, true, -2f, -2f)
            verticalLineTo(9f)
            arcToRelative(2f, 2f, 0f, true, true, 2f, -2f)
            close()
        }
        strokePath {
            moveTo(22f, 11f)
            verticalLineToRelative(2f)
        }
    }.build()
}

val A11yPersonIcon: ImageVector by lazy {
    iconBuilder("A11yPerson").apply {
        strokePath {
            moveTo(16f, 4f)
            moveToRelative(-1f, 0f)
            arcToRelative(1f, 1f, 0f, true, false, 2f, 0f)
            arcToRelative(1f, 1f, 0f, true, false, -2f, 0f)
        }
        strokePath {
            moveToRelative(18f, 19f)
            lineToRelative(1f, -7f)
            lineToRelative(-6f, 1f)
        }
        strokePath {
            moveTo(5f, 8f)
            lineToRelative(3f, -3f)
            lineToRelative(5.5f, 3f)
            lineToRelative(-2.36f, 3.5f)
        }
        strokePath {
            moveTo(4.24f, 14.5f)
            arcToRelative(5f, 5f, 0f, false, false, 6.88f, 6f)
        }
        strokePath {
            moveTo(13.76f, 17.5f)
            arcToRelative(5f, 5f, 0f, false, false, -6.88f, -6f)
        }
    }.build()
}
