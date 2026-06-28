package com.kazimi.syaravin.presentation.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.kazimi.syaravin.data.datasource.camera.CameraDataSource
import com.kazimi.syaravin.data.datasource.ml.TextExtractor
import com.kazimi.syaravin.data.datasource.ml.VinDetector
import com.kazimi.syaravin.data.datasource.validator.VinValidator
import com.kazimi.syaravin.util.FocusState
import com.kazimi.syaravin.util.ImagePreprocessor
import com.kazimi.syaravin.util.LogTags
import com.kazimi.syaravin.util.RoiConfig
import com.kazimi.syaravin.util.SLog
import com.kazimi.syaravin.util.ScannerPerfConfig
import com.kazimi.syaravin.util.Sharpness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private const val TAG = LogTags.LIBRARY

// Auto-accept geometry gates for the detected VIN box (normalized 0..1 within the analyzed frame).
// The analyzed frame IS the tight ROI band, so a well-placed VIN fills its width (left~0,
// right~1) — we therefore only gate vertically (not clipped top/bottom, roughly vertically
// centered) and require the box to fill a decent fraction of the width (camera close enough).
private const val VIN_BOX_VERTICAL_MARGIN = 0.01f // box top/bottom must sit this far inside
private const val VIN_BOX_MAX_CENTER_OFFSET_Y = 0.45f // box vertical center within this of mid
private const val VIN_BOX_MIN_WIDTH = 0.20f // box at least this wide => camera close enough

private val scanFrameCounter = AtomicLong(0)

private data class FrameCandidate(
    val value: String,
    val confidence: Float,
    val isValid: Boolean,
    val checksumValid: Boolean,
)

private fun isVinBoxWellPositioned(box: com.kazimi.syaravin.domain.model.BoundingBox): Boolean {
    // Not clipped vertically (the VIN band spans the ROI width by design, so don't gate L/R).
    if (box.top < VIN_BOX_VERTICAL_MARGIN || box.bottom > 1f - VIN_BOX_VERTICAL_MARGIN) {
        return false
    }
    // Roughly vertically centered.
    val centerY = (box.top + box.bottom) / 2f
    if (abs(centerY - 0.5f) > VIN_BOX_MAX_CENTER_OFFSET_Y) {
        return false
    }
    // Close enough (box fills a decent fraction of the frame width).
    return (box.right - box.left) >= VIN_BOX_MIN_WIDTH
}

/**
 * Picks the highest-need guidance hint for the current frame from the best detected box (ROI-space,
 * normalized 0..1), focus stability, and whether the sharpness gate is holding a soft read. Returns
 * [ScanGuidance.NONE] when a well-placed, focused, sharp VIN is being scanned (nothing to prompt).
 */
private fun computeScanGuidance(
    bestBox: com.kazimi.syaravin.domain.model.BoundingBox?,
    focusStable: Boolean,
    holdingForSharper: Boolean,
): ScanGuidance {
    if (bestBox == null) return ScanGuidance.AIM
    if ((bestBox.right - bestBox.left) < VIN_BOX_MIN_WIDTH) return ScanGuidance.MOVE_CLOSER

    val clipped = bestBox.top < VIN_BOX_VERTICAL_MARGIN || bestBox.bottom > 1f - VIN_BOX_VERTICAL_MARGIN
    val centerY = (bestBox.top + bestBox.bottom) / 2f
    if (clipped || abs(centerY - 0.5f) > VIN_BOX_MAX_CENTER_OFFSET_Y) return ScanGuidance.CENTER_VIN

    // Well placed — only blur/focus could still be holding things up.
    if (!focusStable) return ScanGuidance.TAP_TO_FOCUS
    if (holdingForSharper) return ScanGuidance.HOLD_STEADY
    return ScanGuidance.NONE
}

internal suspend fun processImage(
    frameReceivedNs: Long,
    imageProxy: ImageProxy,
    cameraDataSource: CameraDataSource,
    vinDetector: VinDetector,
    textExtractor: TextExtractor,
    vinValidator: VinValidator,
    roiFrameCounter: AtomicLong,
    acceptGate: VinAcceptState,
    onVinDetected: (String, Float, Bitmap?) -> Unit,
    onBoxesDetected: (List<com.kazimi.syaravin.domain.model.BoundingBox>) -> Unit,
    onRoiBorderStateChange: (RoiBorderState) -> Unit,
    onRoiBitmapCaptured: (Bitmap) -> Unit,
    onCandidateScanned: (String?, Float, Boolean, Boolean) -> Unit,
    onScanGuidance: (ScanGuidance) -> Unit,
) {
    var stageImageToBitmapNs = 0L
    var stageRoiCropNs = 0L
    var stageDetectionNs = 0L
    var stageTextNs = 0L
    var stagePostNs = 0L
    try {
        // Convert only the rotation-aware ROI from YUV to RGB.
        val imageToBitmapStartNs = System.nanoTime()
        val roi = RoiConfig.roi
        val bitmap = cameraDataSource.imageToBitmap(imageProxy, roi)
        stageImageToBitmapNs = System.nanoTime() - imageToBitmapStartNs

        try {
            // The camera data source already returned the ROI; retain full-frame mapping metadata.
            val roiCropStartNs = System.nanoTime()
            val processedBitmap = bitmap
            // Store a copy for manual-entry fallback; throttled to reduce allocation pressure.
            if (roiFrameCounter.incrementAndGet() % 5L == 1L) {
                try {
                    val roiCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val safeRoiCopy = ImagePreprocessor.downscaleForDisplay(roiCopy)
                    if (safeRoiCopy !== roiCopy && !roiCopy.isRecycled) roiCopy.recycle()
                    withContext(Dispatchers.Main) { onRoiBitmapCaptured(safeRoiCopy) }
                } catch (e: Exception) {
                    SLog.e(TAG, "Failed to create ROI bitmap copy", e)
                }
            }
            stageRoiCropNs = System.nanoTime() - roiCropStartNs

            var bestVin: String? = null
            var bestConfidence = 0f
            var croppedVinBitmap: Bitmap? = null
            // First plausible read this frame, surfaced as the live "possible VIN".
            var frameCandidate: FrameCandidate? = null

            try {
                val frame = scanFrameCounter.incrementAndGet()

                // Gather (box, text) candidates. USE_GOOGLE_OCR_ONLY=true reads the whole frame with
                // Google ML Kit (text already in hand, no custom model); otherwise the custom TFLite
                // detector finds boxes and ML Kit OCRs each one. Boxes are ROI-crop-normalized either
                // way, so the downstream gating is identical. Pre-fetched text is null in custom mode.
                val detectionStartNs = System.nanoTime()
                val candidates: List<Pair<com.kazimi.syaravin.domain.model.BoundingBox, String?>> =
                    if (ScannerPerfConfig.USE_GOOGLE_OCR_ONLY) {
                        textExtractor
                            .extractAllTextWithBounds(processedBitmap)
                            .map { it.boundingBox to it.text }
                    } else {
                        vinDetector
                            .detect(processedBitmap)
                            .boundingBoxes
                            .sortedByDescending { it.confidence }
                            .map { box -> box to null }
                    }
                stageDetectionNs = System.nanoTime() - detectionStartNs

                // Map ROI-crop boxes to full-frame normalized coords for the overlay.
                val roiWidthNorm = roi.right - roi.left
                val roiHeightNorm = roi.bottom - roi.top
                val mappedBoxes =
                    candidates.map { (box, _) ->
                        com.kazimi.syaravin.domain.model.BoundingBox(
                            left = roi.left + box.left * roiWidthNorm,
                            top = roi.top + box.top * roiHeightNorm,
                            right = roi.left + box.right * roiWidthNorm,
                            bottom = roi.top + box.bottom * roiHeightNorm,
                            confidence = box.confidence,
                        )
                    }
                onBoxesDetected(mappedBoxes)

                // Update ROI border state based on detection
                if (mappedBoxes.isEmpty()) {
                    onRoiBorderStateChange(RoiBorderState.NO_DETECTION)
                } else {
                    onRoiBorderStateChange(RoiBorderState.NEUTRAL)
                }

                val textStartNs = System.nanoTime()
                val ocrMode = if (ScannerPerfConfig.USE_GOOGLE_OCR_ONLY) "google" else "custom"
                if (candidates.isNotEmpty()) {
                    SLog.w(
                        TAG,
                        "VIN_SCAN frame=$frame mode=$ocrMode candidates=${candidates.size} analysisFrame=${imageProxy.width}x${imageProxy.height} cropBitmap=${processedBitmap.width}x${processedBitmap.height}",
                    )
                }
                val frameNowMs = System.currentTimeMillis()
                // Commit a held soft candidate once its hold window elapses — even with no fresh
                // valid read this frame (later frames may be misreads that never re-enter accept).
                // Checked before the stale-drop so timeout-accept wins over reset-gap.
                if (acceptGate.isAcceptTimedOut(frameNowMs)) {
                    bestVin = acceptGate.stashedVin
                    bestConfidence = acceptGate.stashedConfidence
                    croppedVinBitmap = acceptGate.takeStashedBitmap()
                    acceptGate.reset()
                    SLog.w(TAG, "VIN_SHARPNESS frame=$frame timeout -> ACCEPT_STASHED result=${bestVin ?: "none"}")
                } else if (acceptGate.isStale(frameNowMs)) {
                    SLog.w(TAG, "VIN_SHARPNESS frame=$frame stale pending dropped")
                    acceptGate.reset()
                }
                for ((boxIdx, candidatePair) in candidates.withIndex()) {
                    if (bestVin != null) break // already committed (timeout) — skip further OCR
                    val box = candidatePair.first
                    val boxTag = "${boxIdx + 1}/${candidates.size}"
                    val boxCoords = "L=${"%.3f".format(
                        box.left,
                    )} T=${"%.3f".format(box.top)} R=${"%.3f".format(box.right)} B=${"%.3f".format(box.bottom)}"
                    // Google mode already has the line text; custom mode OCRs the detected box.
                    val textInBox = candidatePair.second ?: textExtractor.extractText(processedBitmap, box)
                    if (textInBox.isNullOrBlank()) {
                        SLog.w(TAG, "VIN_CANDIDATE frame=$frame box=$boxTag conf=${"%.3f".format(box.confidence)} $boxCoords ocr=null")
                        continue
                    }
                    val candidate = vinValidator.cleanVin(textInBox)
                    val validation = vinValidator.validate(candidate)
                    val outcome = if (validation.isValid) "ACCEPTED" else "REJECTED"
                    val reason = validation.errorMessage ?: if (validation.checksumValid) "checksum_ok" else "soft_accept"
                    SLog.w(
                        TAG,
                        "VIN_CANDIDATE frame=$frame box=$boxTag conf=${"%.3f".format(
                            box.confidence,
                        )} $boxCoords ocr=\"${textInBox.take(40)}\" clean=\"$candidate\" $outcome reason=\"$reason\"",
                    )
                    if (frameCandidate == null && candidate.length >= 11) {
                        frameCandidate =
                            FrameCandidate(
                                value = validation.correctedVin ?: candidate,
                                confidence = box.confidence,
                                isValid = validation.isValid,
                                checksumValid = validation.checksumValid,
                            )
                    }
                    // Auto-accept ONLY checksum-valid reads. Soft-accept (format-valid but
                    // checksum-failed) is shown as a live candidate but never auto-confirmed —
                    // otherwise a misread VIN gets delivered with false confidence.
                    if (validation.isValid && validation.checksumValid) {
                        if (!isVinBoxWellPositioned(box)) {
                            val cx = (box.left + box.right) / 2f
                            val cy = (box.top + box.bottom) / 2f
                            SLog.w(
                                TAG,
                                "VIN_REJECT_POSITION frame=$frame box=$boxTag $boxCoords " +
                                    "center=(${"%.2f".format(cx)},${"%.2f".format(cy)}) w=${"%.2f".format(box.right - box.left)}",
                            )
                            continue
                        }
                        // Wait for focus to settle before accepting.
                        if (!FocusState.isStable) {
                            SLog.w(TAG, "VIN_REJECT_FOCUS frame=$frame box=$boxTag (focus not stable)")
                            continue
                        }

                        // #2 sharpness gate: prefer a sharp frame; hold soft ones briefly and accept
                        // the sharpest, with a time-bounded fallback so we never stall forever. The
                        // result image is the full scanned frame, contrast-boosted for clarity.
                        val vinValue = validation.correctedVin ?: candidate
                        val sharpness =
                            Sharpness.varianceOfLaplacian(
                                processedBitmap,
                                ScannerPerfConfig.sharpnessSampleMaxEdge,
                            )
                        val thr = ScannerPerfConfig.sharpnessThreshold

                        // Auto-scan source is the soft analysis stream → sharpen the result.
                        fun enhanced(): Bitmap? =
                            try {
                                ImagePreprocessor.enhanceForDisplay(processedBitmap, sharpen = true)
                            } catch (e: Exception) {
                                SLog.e(TAG, "Failed to prepare VIN frame bitmap", e)
                                null
                            }
                        when (acceptGate.onValidRead(sharpness, frameNowMs)) {
                            VinAcceptDecider.Action.ACCEPT_CURRENT -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(sharpness)} thr=$thr -> ACCEPT_CURRENT",
                                )
                                acceptGate.reset()
                                bestVin = vinValue
                                bestConfidence = box.confidence
                                croppedVinBitmap = enhanced()
                                break
                            }

                            VinAcceptDecider.Action.ACCEPT_STASHED -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(sharpness)} thr=$thr -> ACCEPT_STASHED",
                                )
                                bestVin = acceptGate.stashedVin ?: vinValue
                                bestConfidence = acceptGate.stashedConfidence
                                croppedVinBitmap = acceptGate.takeStashedBitmap() ?: enhanced()
                                acceptGate.reset()
                                break
                            }

                            VinAcceptDecider.Action.STASH_CURRENT -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(
                                        sharpness,
                                    )} thr=$thr -> STASH (waiting for sharper)",
                                )
                                acceptGate.stash(vinValue, box.confidence, enhanced())
                                // No accept this frame; keep scanning subsequent frames.
                                break
                            }

                            VinAcceptDecider.Action.DISCARD_CURRENT -> {
                                SLog.w(
                                    TAG,
                                    "VIN_SHARPNESS frame=$frame box=$boxTag value=${"%.1f".format(
                                        sharpness,
                                    )} thr=$thr -> DISCARD (waiting for sharper)",
                                )
                                break
                            }
                        }
                    }
                }
                onCandidateScanned(
                    frameCandidate?.value,
                    frameCandidate?.confidence ?: 0f,
                    frameCandidate?.isValid ?: false,
                    frameCandidate?.checksumValid ?: false,
                )
                // Surface context-sensitive guidance (aim / closer / center / focus / hold steady).
                onScanGuidance(
                    computeScanGuidance(
                        bestBox = candidates.firstOrNull()?.first,
                        focusStable = FocusState.isStable,
                        holdingForSharper = acceptGate.hasPending,
                    ),
                )
                if (candidates.isNotEmpty()) {
                    SLog.w(TAG, "VIN_RESULT frame=$frame result=${bestVin ?: "none"}")
                }

                stageTextNs = System.nanoTime() - textStartNs

                // If none found from boxes, fall back to ROI text lines and require validation

                /* Fallback disabled by user request - rely on AI detection only
                if (bestVin == null) {
                    SLog.d(TAG, "Falling back to ROI text lines for VIN candidate...")
                    for (text in allText) {
                        val cleanedText = vinValidator.cleanVin(text)
                        val validation = vinValidator.validate(cleanedText)
                        if (validation.isValid) {
                            bestVin = cleanedText
                            bestConfidence = 1.0f
                            // VIN found from text, AI model did not detect box location
                            SLog.d(TAG, "VIN found from text without AI detection box")
                            break
                        }
                    }
                }
                 */
            } finally {
                if (processedBitmap !== bitmap) {
                    try {
                        processedBitmap.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }

            // If a VIN was found, report it
            val postStartNs = System.nanoTime()
            if (bestVin != null) {
                SLog.d(TAG, "VIN detected with confidence=$bestConfidence")
                onRoiBorderStateChange(RoiBorderState.VALID_VIN_DETECTED)
                onVinDetected(bestVin, bestConfidence, croppedVinBitmap)
            }
            stagePostNs = System.nanoTime() - postStartNs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SLog.e(TAG, "Error processing image", e)
        } finally {
            try {
                bitmap.recycle()
            } catch (_: Throwable) {
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        SLog.e(TAG, "Error converting image", e)
    } finally {
        val totalNs = System.nanoTime() - frameReceivedNs
        val stageSummary =
            "camera_to_bitmap_ms=${"%.2f".format(stageImageToBitmapNs / 1_000_000.0)} " +
                "roi_crop_ms=${"%.2f".format(stageRoiCropNs / 1_000_000.0)} " +
                "detect_ms=${"%.2f".format(stageDetectionNs / 1_000_000.0)} " +
                "ocr_ms=${"%.2f".format(stageTextNs / 1_000_000.0)} " +
                "post_ms=${"%.2f".format(stagePostNs / 1_000_000.0)}"
        ScannerPerfConfig.frameTiming.onFrameFinished(totalNs, stageSummary)
        imageProxy.close()
    }
}
