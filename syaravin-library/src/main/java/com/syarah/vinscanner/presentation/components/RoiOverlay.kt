package com.syarah.vinscanner.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

    // Animate the border toward the target color. Read the animated value ONLY inside the draw
    // lambda below so the animation invalidates the draw phase, not composition — keeps the whole
    // scanner screen from recomposing every frame during the 250ms color transition.
    val animatedBorder by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(durationMillis = 250),
        label = "roi_border_color",
    )

    // Reuse one Path across draws instead of allocating per frame.
    val cutout = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val leftPx = roiBox.left * size.width
        val topPx = roiBox.top * size.height
        val rightPx = roiBox.right * size.width
        val bottomPx = roiBox.bottom * size.height
        val borderDrawColor = animatedBorder

        // Scrim covering everything except a rounded-rect cutout over the ROI.
        cutout.rewind()
        cutout.addRoundRect(
            RoundRect(
                left = leftPx,
                top = topPx,
                right = rightPx,
                bottom = bottomPx,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
            ),
        )
        clipPath(cutout, clipOp = ClipOp.Difference) {
            drawRect(color = scrimColor, size = size)
        }

        // Rounded border
        drawRoundRect(
            color = borderDrawColor,
            topLeft = Offset(x = leftPx, y = topPx),
            size = Size(width = rightPx - leftPx, height = bottomPx - topPx),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = strokeWidthPx),
        )

        // Corner accents — start past the rounded corner, with round caps.
        // Top-left
        drawLine(
            color = borderDrawColor,
            start = Offset(leftPx + radiusPx, topPx),
            end = Offset(leftPx + radiusPx + cornerLenPx, topPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderDrawColor,
            start = Offset(leftPx, topPx + radiusPx),
            end = Offset(leftPx, topPx + radiusPx + cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        // Top-right
        drawLine(
            color = borderDrawColor,
            start = Offset(rightPx - radiusPx, topPx),
            end = Offset(rightPx - radiusPx - cornerLenPx, topPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderDrawColor,
            start = Offset(rightPx, topPx + radiusPx),
            end = Offset(rightPx, topPx + radiusPx + cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        // Bottom-left
        drawLine(
            color = borderDrawColor,
            start = Offset(leftPx + radiusPx, bottomPx),
            end = Offset(leftPx + radiusPx + cornerLenPx, bottomPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderDrawColor,
            start = Offset(leftPx, bottomPx - radiusPx),
            end = Offset(leftPx, bottomPx - radiusPx - cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        // Bottom-right
        drawLine(
            color = borderDrawColor,
            start = Offset(rightPx - radiusPx, bottomPx),
            end = Offset(rightPx - radiusPx - cornerLenPx, bottomPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = borderDrawColor,
            start = Offset(rightPx, bottomPx - radiusPx),
            end = Offset(rightPx, bottomPx - radiusPx - cornerLenPx),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
    }
}
