package com.syarah.vinscanner.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
    cornerLength: Dp = 24.dp,
    cornerRadius: Dp = 14.dp,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { borderWidth.toPx() }
    val cornerLenPx = with(density) { cornerLength.toPx() }
    val radiusPx = with(density) { cornerRadius.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val leftPx = roiBox.left * size.width
        val topPx = roiBox.top * size.height
        val rightPx = roiBox.right * size.width
        val bottomPx = roiBox.bottom * size.height

        // Scrim covering everything except a rounded-rect cutout over the ROI.
        val cutout = Path().apply {
            addRoundRect(
                RoundRect(
                    left = leftPx,
                    top = topPx,
                    right = rightPx,
                    bottom = bottomPx,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                )
            )
        }
        clipPath(cutout, clipOp = ClipOp.Difference) {
            drawRect(color = scrimColor, size = size)
        }

        // Rounded border
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(x = leftPx, y = topPx),
            size = Size(width = rightPx - leftPx, height = bottomPx - topPx),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = strokeWidthPx),
        )

        // Corner accents — start past the rounded corner, with round caps.
        // Top-left
        drawLine(
            color = borderColor,
            start = Offset(leftPx + radiusPx, topPx),
            end = Offset(leftPx + radiusPx + cornerLenPx, topPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderColor,
            start = Offset(leftPx, topPx + radiusPx),
            end = Offset(leftPx, topPx + radiusPx + cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        // Top-right
        drawLine(
            color = borderColor,
            start = Offset(rightPx - radiusPx, topPx),
            end = Offset(rightPx - radiusPx - cornerLenPx, topPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderColor,
            start = Offset(rightPx, topPx + radiusPx),
            end = Offset(rightPx, topPx + radiusPx + cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        // Bottom-left
        drawLine(
            color = borderColor,
            start = Offset(leftPx + radiusPx, bottomPx),
            end = Offset(leftPx + radiusPx + cornerLenPx, bottomPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderColor,
            start = Offset(leftPx, bottomPx - radiusPx),
            end = Offset(leftPx, bottomPx - radiusPx - cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        // Bottom-right
        drawLine(
            color = borderColor,
            start = Offset(rightPx - radiusPx, bottomPx),
            end = Offset(rightPx - radiusPx - cornerLenPx, bottomPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderColor,
            start = Offset(rightPx, bottomPx - radiusPx),
            end = Offset(rightPx, bottomPx - radiusPx - cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
    }
}
