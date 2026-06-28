package com.kazimi.sample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kazimi.syaravin.domain.model.VinNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SampleAppScreen(
    history: List<ScanHistoryEntry>,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullScreenImage by remember { mutableStateOf<ImageBitmap?>(null) }

    fullScreenImage?.let { image ->
        FullScreenImageDialog(image = image, onDismiss = { fullScreenImage = null })
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }

            if (history.isEmpty()) {
                item { EmptyState() }
            } else {
                itemsIndexed(history) { index, entry ->
                    HistoryItem(
                        entry = entry,
                        index = index,
                        onImageClick = { fullScreenImage = it },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Button(
                onClick = onScanClick,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Text(
                    text = "Start scan",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun HistoryItem(
    entry: ScanHistoryEntry,
    index: Int,
    onImageClick: (ImageBitmap) -> Unit,
) {
    if (entry.vin != null) {
        VinHistoryCard(vin = entry.vin, index = index, onImageClick = onImageClick)
    } else {
        MessageCard(message = entry.message ?: "Unknown result")
    }
}

@Composable
private fun VinHistoryCard(
    vin: VinNumber,
    index: Int,
    onImageClick: (ImageBitmap) -> Unit,
) {
    val context = LocalContext.current
    val resultBitmap by produceState<Bitmap?>(
        initialValue = vin.croppedImage,
        key1 = vin.croppedImage,
        key2 = vin.croppedImageUri,
    ) {
        value =
            vin.croppedImage
                ?: vin.croppedImageUri?.let { uri ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver
                            .openInputStream(uri)
                            ?.use(BitmapFactory::decodeStream)
                    }
                }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            resultBitmap?.let { bitmap ->
                val imageBitmap = bitmap.asImageBitmap()
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "VIN image",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(imageBitmap) },
                    contentScale = ContentScale.FillWidth,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = vin.value.ifBlank { "No VIN read" },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (vin.confidence > 0f) {
                        InfoChip(value = "${(vin.confidence * 100).toInt()}%")
                    }
                    StateChip(vin = vin)
                }
            }
        }
    }
}

@Composable
private fun StateChip(vin: VinNumber) {
    val scheme = MaterialTheme.colorScheme
    val (label, bg, fg) =
        if (vin.isValid) {
            Triple("VALID", scheme.tertiaryContainer, scheme.tertiary)
        } else {
            Triple("REVIEW", scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
        }
    Surface(shape = CircleShape, color = bg) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun InfoChip(
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MessageCard(message: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No scans yet",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Scan results will appear here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FullScreenImageDialog(
    image: ImageBitmap,
    onDismiss: () -> Unit,
) {
    val maxScale = 10f
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(
        raw: Offset,
        currentScale: Float,
    ): Offset {
        if (currentScale <= 1f) return Offset.Zero
        val maxX = (boxSize.width * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(1f, maxScale)
            scale = nextScale
            offset = clamp(offset + panChange, nextScale)
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .onSizeChanged { boxSize = it }
                    .transformable(transformState)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            },
                            onTap = { if (scale <= 1f) onDismiss() },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = "Full screen VIN image",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
