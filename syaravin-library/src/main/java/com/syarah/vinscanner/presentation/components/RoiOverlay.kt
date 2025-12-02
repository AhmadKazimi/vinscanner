package com.syarah.vinscanner.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.syarah.vinscanner.domain.model.BoundingBox
import com.syarah.vinscanner.util.RoiConfig

/**
 * Draws a shaded mask outside a rectangular ROI with a guiding border and corners.
 * The [roiBox] uses normalised coordinates (0f..1f).
 */
@Composable
internal fun RoiOverlay(
    modifier: Modifier = Modifier,
    roiBox: BoundingBox = RoiConfig.roi,
    scrimColor: Color = Color.Black.copy(alpha = 0.55f),
    borderColor: Color = Color.White,
    borderWidth: Dp = 2.dp,
    cornerLength: Dp = 24.dp
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { borderWidth.toPx() }
    val cornerLenPx = with(density) { cornerLength.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val leftPx = roiBox.left * size.width
        val topPx = roiBox.top * size.height
        val rightPx = roiBox.right * size.width
        val bottomPx = roiBox.bottom * size.height

        // Top scrim
        drawRect(
            color = scrimColor,
            topLeft = Offset(x = 0f, y = 0f),
            size = Size(width = size.width, height = topPx)
        )

        // Bottom scrim
        drawRect(
            color = scrimColor,
            topLeft = Offset(x = 0f, y = bottomPx),
            size = Size(width = size.width, height = size.height - bottomPx)
        )

        // Left scrim
        drawRect(
            color = scrimColor,
            topLeft = Offset(x = 0f, y = topPx),
            size = Size(width = leftPx, height = bottomPx - topPx)
        )

        // Right scrim
        drawRect(
            color = scrimColor,
            topLeft = Offset(x = rightPx, y = topPx),
            size = Size(width = size.width - rightPx, height = bottomPx - topPx)
        )

        // Border
        drawRect(
            color = borderColor,
            topLeft = Offset(x = leftPx, y = topPx),
            size = Size(width = rightPx - leftPx, height = bottomPx - topPx),
            style = Stroke(width = strokeWidthPx)
        )

        // Corner accents
        // Top-left
        drawLine(
            color = borderColor,
            start = Offset(leftPx, topPx),
            end = Offset(leftPx + cornerLenPx, topPx),
            strokeWidth = strokeWidthPx
        )
        drawLine(
            color = borderColor,
            start = Offset(leftPx, topPx),
            end = Offset(leftPx, topPx + cornerLenPx),
            strokeWidth = strokeWidthPx
        )

        // Top-right
        drawLine(
            color = borderColor,
            start = Offset(rightPx, topPx),
            end = Offset(rightPx - cornerLenPx, topPx),
            strokeWidth = strokeWidthPx
        )
        drawLine(
            color = borderColor,
            start = Offset(rightPx, topPx),
            end = Offset(rightPx, topPx + cornerLenPx),
            strokeWidth = strokeWidthPx
        )

        // Bottom-left
        drawLine(
            color = borderColor,
            start = Offset(leftPx, bottomPx),
            end = Offset(leftPx + cornerLenPx, bottomPx),
            strokeWidth = strokeWidthPx
        )
        drawLine(
            color = borderColor,
            start = Offset(leftPx, bottomPx),
            end = Offset(leftPx, bottomPx - cornerLenPx),
            strokeWidth = strokeWidthPx
        )

        // Bottom-right
        drawLine(
            color = borderColor,
            start = Offset(rightPx, bottomPx),
            end = Offset(rightPx - cornerLenPx, bottomPx),
            strokeWidth = strokeWidthPx
        )
        drawLine(
            color = borderColor,
            start = Offset(rightPx, bottomPx),
            end = Offset(rightPx, bottomPx - cornerLenPx),
            strokeWidth = strokeWidthPx
        )
    }
}


