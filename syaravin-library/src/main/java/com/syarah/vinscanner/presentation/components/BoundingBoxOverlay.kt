package com.syarah.vinscanner.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.syarah.vinscanner.domain.model.BoundingBox

/**
 * Composable that draws bounding boxes over detected VIN regions
 */
@Composable
internal fun BoundingBoxOverlay(
    modifier: Modifier = Modifier,
    boundingBoxes: List<BoundingBox>,
    boxColor: Color = Color.Unspecified,
    strokeWidth: Float = 3f
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.dp.toPx() }
    val color = if (boxColor == Color.Unspecified) MaterialTheme.colorScheme.primary else boxColor

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        boundingBoxes.forEach { box ->
            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(
                    x = box.left * size.width,
                    y = box.top * size.height
                ),
                size = Size(
                    width = (box.right - box.left) * size.width,
                    height = (box.bottom - box.top) * size.height
                ),
                style = Stroke(width = strokeWidthPx)
            )

            // Draw confidence score if high enough
            if (box.confidence > 0.25f) {
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        this.color = color.toArgb()
                        textSize = 40f
                        isAntiAlias = true
                    }

                    val confidenceText = "${(box.confidence * 100).toInt()}%"
                    canvas.nativeCanvas.drawText(
                        confidenceText,
                        box.left * size.width + 10,
                        box.top * size.height - 10,
                        paint
                    )
                }
            }
        }
    }
}