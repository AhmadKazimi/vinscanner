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

/**
 * Result of a manual capture. [candidates] holds the possible 17-char VINs: a single entry when the
 * read is unambiguous (or only a best-effort guess), or several when ambiguous-character
 * clarification yields multiple checksum-valid VINs for the user to choose from. [areChecksumValid]
 * is true when the candidates are checksum-valid (vs. a best-effort conjured guess).
 */
internal class ManualCaptureResult(
    val candidates: List<String>,
    val confidence: Float,
    val areChecksumValid: Boolean,
    val image: Bitmap?,
)

internal suspend fun analyzeManualCapture(
    imageCapture: ImageCapture,
    captureExecutor: ExecutorService,
    fallbackRoiBitmap: Bitmap?,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
): ManualCaptureResult {
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

    // Scan ONLY the ROI band (matches the on-screen overlay) — keeps OCR focused on the VIN line
    // and avoids picking up surrounding sticker text. Same bitmap is used for the result image.
    val ocrBitmap: Bitmap
    if (capturedBitmap != null) {
        val portrait = rotateLandscapeCaptureToPortrait(capturedBitmap)
        val overlayFrame = centerCropToAspectRatio(portrait, RoiConfig.analyzedImageAspectRatio)
        ocrBitmap = cropToRoiBand(overlayFrame) // fresh bitmap, independent of the inputs below
        listOf(portrait, overlayFrame, capturedBitmap).distinct().forEach {
            if (!it.isRecycled) it.recycle()
        }
        fallbackRoiBitmap?.recycle()
    } else {
        ocrBitmap = fallbackRoiBitmap ?: return ManualCaptureResult(emptyList(), 0f, false, null)
    }
    val displayBitmap = ocrBitmap

    // Upscale + sharpen the band before OCR (manual only) so thin/touching glyphs separate.
    val ocrInput = ImagePreprocessor.enhanceForOcr(ocrBitmap)
    SLog.w(TAG, "MANUAL_CAPTURE ocr=${ocrBitmap.width}x${ocrBitmap.height} enhanced=${ocrInput.width}x${ocrInput.height}")

    // Manual capture is a high-res still → contrast only, no sharpen (avoids halos on sharp text).
    fun resultImage(): Bitmap? = ImagePreprocessor.enhanceForDisplay(displayBitmap, sharpen = false)

    try {
        // Collect every OCR read (per-box for the custom model, plus the full-frame lines).
        val rawReads = mutableListOf<Pair<String, Float>>()

        if (!ScannerPerfConfig.USE_GOOGLE_OCR_ONLY) {
            val boxes =
                vinDetector
                    .detect(ocrInput)
                    .boundingBoxes
                    .sortedByDescending { it.confidence }
                    .take(3)
            SLog.w(TAG, "MANUAL_CAPTURE detected_boxes=${boxes.size} bitmap=${ocrInput.width}x${ocrInput.height}")
            coroutineScope {
                boxes.map { box -> async { box to textExtractor.extractText(ocrInput, box) } }.awaitAll()
            }.forEach { (box, text) -> if (!text.isNullOrBlank()) rawReads += text to box.confidence }
        }

        textExtractor.extractAllText(ocrInput).forEach { line ->
            if (line.isNotBlank()) rawReads += line to 1f
        }
        SLog.w(TAG, "MANUAL_CAPTURE reads=${rawReads.size}")

        // Pick the base 17-char read: an exact cleanVin if any, else the conjured closest guess.
        val exact =
            rawReads.firstNotNullOfOrNull { (raw, conf) ->
                vinValidator.cleanVin(raw).takeIf { it.length == VinNumber.VIN_LENGTH }?.let { it to conf }
            }
        val (baseVin, confidence) =
            exact ?: run {
                val best =
                    rawReads
                        .map { (raw, conf) -> looseVin(raw) to conf }
                        .filter { it.first.isNotEmpty() }
                        .maxByOrNull { vinCloseness(it.first) }
                val conjured = best?.first?.let { if (it.length > VinNumber.VIN_LENGTH) it.take(VinNumber.VIN_LENGTH) else it } ?: ""
                conjured to (best?.second ?: 0f)
            }
        SLog.w(TAG, "MANUAL_CAPTURE base=\"$baseVin\" len=${baseVin.length}")

        // "LJ" frequently merges into a single "U" under OCR, leaving a 16-char read. Recover the
        // length by expanding a U back into "LJ"; prefer the expansion whose checksum is valid.
        val resolvedBase = expandMergedLj(baseVin, vinValidator)
        if (resolvedBase != baseVin) SLog.w(TAG, "MANUAL_CAPTURE expanded U->LJ \"$baseVin\" -> \"$resolvedBase\"")

        // Manual-only: explore ambiguous-character swaps and surface every checksum-valid VIN so the
        // user can pick the right one. Falls back to the single base read when none are valid.
        val checksumValid =
            if (resolvedBase.length == VinNumber.VIN_LENGTH) {
                ambiguousCandidates(resolvedBase)
                    .filter { vinValidator.validate(it).checksumValid }
                    .distinct()
                    // Show the candidates closest to the read first (fewest swapped chars).
                    .sortedBy { cand -> cand.indices.count { cand[it] != resolvedBase[it] } }
                    .take(MAX_VIN_CANDIDATES)
            } else {
                emptyList()
            }
        SLog.w(TAG, "MANUAL_CAPTURE checksum_valid=${checksumValid.size} -> $checksumValid")

        // Ambiguous clarification is used ONLY to populate the picker when more than one
        // checksum-valid VIN exists. Otherwise return the resolved base read.
        return if (checksumValid.size >= 2) {
            ManualCaptureResult(checksumValid, confidence, areChecksumValid = true, image = resultImage())
        } else {
            val baseChecksumValid =
                resolvedBase.length == VinNumber.VIN_LENGTH && vinValidator.validate(resolvedBase).checksumValid
            ManualCaptureResult(
                candidates = if (resolvedBase.isNotBlank()) listOf(resolvedBase) else emptyList(),
                confidence = confidence,
                areChecksumValid = baseChecksumValid,
                image = resultImage(),
            )
        }
    } finally {
        if (!ocrInput.isRecycled) ocrInput.recycle()
        if (displayBitmap !== ocrBitmap && !displayBitmap.isRecycled) displayBitmap.recycle()
        if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
    }
}

/**
 * Best-effort VIN-charset reduction of an OCR read: uppercases, maps the VIN-illegal I/O/Q (and
 * common confusions) to their digit look-alikes, then keeps only valid VIN characters. Used to
 * "conjure" a closest-guess VIN when no exact 17-char read is found.
 */
// Max ambiguous candidates surfaced to the user, the max characters allowed to differ from the
// base read per candidate, and a hard safety cap on the number of combinations explored.
private const val MAX_VIN_CANDIDATES = 4
private const val MAX_AMBIGUOUS_CHANGES = 3
private const val MAX_AMBIGUOUS_COMBOS = 4096

// OCR-confusable VIN characters (both directions). Used only in manual capture to enumerate
// alternative VINs and keep the checksum-valid ones for the user to choose from.
// Symmetric OCR confusion classes among VIN-valid characters (I/O/Q are already normalized away
// before this stage). Keep this tight: every extra pair widens the search and risks surfacing
// spurious checksum-valid look-alikes. Letter↔letter pairs that explode combinatorially with low
// payoff (M/N/H/W) are deliberately excluded.
private val AMBIGUOUS_CHARS: Map<Char, List<Char>> =
    mapOf(
        // digit ↔ letter look-alikes
        '0' to listOf('D'),
        'D' to listOf('0'),
        '5' to listOf('S'),
        'S' to listOf('5'),
        '8' to listOf('B'),
        'B' to listOf('8'),
        '2' to listOf('Z'),
        'Z' to listOf('2', '7'),
        '6' to listOf('G'),
        'G' to listOf('6', 'C'),
        'C' to listOf('G'),
        '4' to listOf('A'),
        'A' to listOf('4'),
        // 1 / 7 / J / T cluster (vertical strokes)
        '1' to listOf('J', '7', 'T'),
        'J' to listOf('1'),
        '7' to listOf('1', 'T', 'Z'),
        'T' to listOf('1', '7'),
        // U / V / Y cluster
        'U' to listOf('V'),
        'V' to listOf('U', 'Y'),
        'Y' to listOf('V'),
    )

/**
 * Enumerates VIN strings reachable from [base] by swapping OCR-ambiguous characters (see
 * [AMBIGUOUS_CHARS]), changing AT MOST [MAX_AMBIGUOUS_CHANGES] characters per candidate. Includes
 * [base] itself (0 changes). Bounded by [MAX_AMBIGUOUS_COMBOS] as a safety cap.
 */
private fun ambiguousCandidates(base: String): List<String> {
    // Positions that have at least one ambiguous alternative, with their alternative characters.
    val positions = base.indices.mapNotNull { i -> AMBIGUOUS_CHARS[base[i]]?.let { i to it } }
    val results = LinkedHashSet<String>()
    results += base
    val working = base.toCharArray()

    // Pick up to MAX_AMBIGUOUS_CHANGES positions (in increasing order) and one alternative each.
    fun recurse(
        startPos: Int,
        changesLeft: Int,
    ) {
        if (changesLeft == 0 || results.size >= MAX_AMBIGUOUS_COMBOS) return
        for (p in startPos until positions.size) {
            val (idx, alts) = positions[p]
            val original = working[idx]
            for (alt in alts) {
                working[idx] = alt
                results += String(working)
                if (results.size >= MAX_AMBIGUOUS_COMBOS) {
                    working[idx] = original
                    return
                }
                recurse(p + 1, changesLeft - 1)
            }
            working[idx] = original
        }
    }
    recurse(0, MAX_AMBIGUOUS_CHANGES)
    return results.toList()
}

/**
 * Recovers a 16-char read that lost a character to the common "LJ"→"U" OCR merge: expands a U back
 * into "LJ" to reach 17 chars. Tries each U position and prefers the expansion that is
 * checksum-valid; otherwise expands the first U. Returns [vin] unchanged when it isn't a 16-char
 * read containing a U.
 */
private fun expandMergedLj(
    vin: String,
    validator: VinValidator,
): String {
    if (vin.length != VinNumber.VIN_LENGTH - 1 || 'U' !in vin) return vin
    val expansions =
        vin.indices
            .filter { vin[it] == 'U' }
            .map { i -> vin.substring(0, i) + "LJ" + vin.substring(i + 1) }
    return expansions.firstOrNull { validator.validate(it).checksumValid } ?: expansions.first()
}

private fun looseVin(raw: String): String =
    raw
        .uppercase()
        .replace('I', '1')
        .replace('O', '0')
        .replace('Q', '0')
        .replace('|', '1')
        .replace('!', '1')
        .filter { it in '0'..'9' || (it in 'A'..'Z' && it != 'I' && it != 'O' && it != 'Q') }

/** Higher is better: rewards being close to 17 chars, then more digits (VINs are digit-heavy). */
private fun vinCloseness(s: String): Int = 100 - abs(VinNumber.VIN_LENGTH - s.length) * 5 + s.count { it.isDigit() }

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
