package com.kazimi.syaravin.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.kazimi.syaravin.data.datasource.ml.TextExtractor
import com.kazimi.syaravin.data.datasource.ml.VinDetector
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.domain.model.VinNumber
import com.kazimi.syaravin.util.ImagePreprocessor
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.RoiConfig
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.ScannerPerfConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val TAG = LogTags.LIBRARY

internal suspend fun analyzeManualCapture(
    imageCapture: ImageCapture,
    captureExecutor: ExecutorService,
    fallbackRoiBitmap: Bitmap?,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
): VinNumber {
    SLog.w(TAG, "MANUAL_CAPTURE analysis started")
    val capturedBitmap =
        try {
            captureStillBitmap(imageCapture, captureExecutor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SLog.w(TAG, "Still capture failed; analyzing latest ROI frame", e)
            null
        }

    // OCR runs on the FULL portrait frame (no ROI-band or aspect crop) so the VIN's edge characters
    // aren't clipped on any side. The displayed result image is still the ROI band.
    val ocrBitmap: Bitmap
    val displayBitmap: Bitmap
    if (capturedBitmap != null) {
        val portrait = rotateLandscapeCaptureToPortrait(capturedBitmap)
        // ROI band for display is cut from the analyzed-aspect crop (matches the on-screen overlay).
        val overlayFrame = centerCropToAspectRatio(portrait, RoiConfig.analyzedImageAspectRatio)
        displayBitmap = cropToRoiBand(overlayFrame)
        if (overlayFrame !== portrait && !overlayFrame.isRecycled) overlayFrame.recycle()
        if (capturedBitmap !== portrait && !capturedBitmap.isRecycled) capturedBitmap.recycle()
        fallbackRoiBitmap?.recycle()
        ocrBitmap = portrait
    } else {
        ocrBitmap = fallbackRoiBitmap ?: return VinNumber(value = "", confidence = 0f, isValid = false)
        displayBitmap = ocrBitmap
    }
    SLog.w(TAG, "MANUAL_CAPTURE ocr=${ocrBitmap.width}x${ocrBitmap.height} display=${displayBitmap.width}x${displayBitmap.height}")

    // Manual capture is a high-res still → contrast only, no sharpen (avoids halos on sharp text).
    fun resultImage(): Bitmap? = ImagePreprocessor.enhanceForDisplay(displayBitmap, sharpen = false)

    try {
        // Custom-model path: detect VIN boxes and OCR each. Skipped entirely in Google-OCR mode,
        // which relies on full-frame text recognition below.
        if (!ScannerPerfConfig.USE_GOOGLE_OCR_ONLY) {
            val boxes =
                vinDetector
                    .detect(ocrBitmap)
                    .boundingBoxes
                    .sortedByDescending { it.confidence }
                    .take(3)
            SLog.w(TAG, "MANUAL_CAPTURE detected_boxes=${boxes.size} bitmap=${ocrBitmap.width}x${ocrBitmap.height}")
            val extractedCandidates =
                coroutineScope {
                    boxes
                        .map { box ->
                            async { box to textExtractor.extractText(ocrBitmap, box) }
                        }.awaitAll()
                }

            for ((box, rawText) in extractedCandidates) {
                if (rawText.isNullOrBlank()) continue
                // Manual capture: trust the user's framing. cleanVin already replaces I/O/Q and
                // extracts the 17-char sequence; accept it on length alone — no checksum gate.
                val candidate = vinValidator.cleanVin(rawText)
                if (candidate.length != VinNumber.VIN_LENGTH) continue

                return VinNumber(
                    value = candidate,
                    confidence = box.confidence,
                    isValid = true,
                    croppedImage = resultImage(),
                )
            }
        }

        val fullImageText = textExtractor.extractAllText(ocrBitmap)
        SLog.w(TAG, "MANUAL_CAPTURE fallback_ocr_lines=${fullImageText.size}")
        for (rawText in fullImageText) {
            if (rawText.isBlank()) continue
            val candidate = vinValidator.cleanVin(rawText)
            SLog.w(
                TAG,
                "MANUAL_CAPTURE fallback_ocr=\"${rawText.take(40)}\" clean=\"$candidate\" len=${candidate.length}",
            )
            if (candidate.length != VinNumber.VIN_LENGTH) continue

            return VinNumber(
                value = candidate,
                confidence = 1f,
                isValid = true,
                croppedImage = resultImage(),
            )
        }

        return VinNumber(
            value = "",
            confidence = 0f,
            isValid = false,
            croppedImage = resultImage(),
        )
    } finally {
        if (displayBitmap !== ocrBitmap && !displayBitmap.isRecycled) displayBitmap.recycle()
        if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
    }
}

/** Crops the ROI band out of a full [overlayFrame] for display. Does not recycle [overlayFrame]. */
private fun cropToRoiBand(overlayFrame: Bitmap): Bitmap {
    val roi = RoiConfig.roi
    val left = floor(roi.left * overlayFrame.width).toInt().coerceIn(0, overlayFrame.width - 1)
    val top = floor(roi.top * overlayFrame.height).toInt().coerceIn(0, overlayFrame.height - 1)
    val right = ceil(roi.right * overlayFrame.width).toInt().coerceIn(left + 1, overlayFrame.width)
    val bottom = ceil(roi.bottom * overlayFrame.height).toInt().coerceIn(top + 1, overlayFrame.height)
    return Bitmap.createBitmap(overlayFrame, left, top, right - left, bottom - top)
}

private fun rotateLandscapeCaptureToPortrait(bitmap: Bitmap): Bitmap {
    if (bitmap.height >= bitmap.width) return bitmap
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun centerCropToAspectRatio(
    bitmap: Bitmap,
    targetAspectRatio: Float,
): Bitmap {
    val currentAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    if (abs(currentAspectRatio - targetAspectRatio) < 0.01f) return bitmap

    return if (currentAspectRatio > targetAspectRatio) {
        val targetWidth = (bitmap.height * targetAspectRatio).toInt().coerceIn(1, bitmap.width)
        val left = ((bitmap.width - targetWidth) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(bitmap, left, 0, targetWidth, bitmap.height)
    } else {
        val targetHeight = (bitmap.width / targetAspectRatio).toInt().coerceIn(1, bitmap.height)
        val top = ((bitmap.height - targetHeight) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(bitmap, 0, top, bitmap.width, targetHeight)
    }
}

private suspend fun captureStillBitmap(
    imageCapture: ImageCapture,
    executor: ExecutorService,
): Bitmap =
    suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = imageProxyJpegToBitmap(image)
                        if (cont.isActive) cont.resumeWith(Result.success(bmp))
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(exception))
                }
            },
        )
    }

private fun imageProxyJpegToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val decodeOptions =
        BitmapFactory.Options().apply {
            inSampleSize = captureDecodeSampleSize(bounds.outWidth, bounds.outHeight)
        }
    val decodedSource =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw IllegalStateException("Failed to decode captured JPEG")
    val decoded = boundCapturedBitmap(decodedSource)
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
    return rotated
}

internal fun captureDecodeSampleSize(
    width: Int,
    height: Int,
): Int {
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    while (
        maxOf(width / (sampleSize * 2), height / (sampleSize * 2)) > 1920 ||
        minOf(width / (sampleSize * 2), height / (sampleSize * 2)) > 1080
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun boundCapturedBitmap(bitmap: Bitmap): Bitmap {
    val scale =
        minOf(
            1f,
            1920f / maxOf(bitmap.width, bitmap.height),
            1080f / minOf(bitmap.width, bitmap.height),
        )
    if (scale >= 1f) return bitmap
    return Bitmap
        .createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled ->
            if (scaled !== bitmap) bitmap.recycle()
        }
}
