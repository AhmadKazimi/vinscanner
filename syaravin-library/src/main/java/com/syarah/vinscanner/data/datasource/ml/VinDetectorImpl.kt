package com.syarah.vinscanner.data.datasource.ml

import com.syarah.vinscanner.util.LogTags

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.syarah.vinscanner.util.SLog
import com.syarah.vinscanner.data.model.DetectionResult
import com.syarah.vinscanner.domain.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = LogTags.LIBRARY
private const val ENABLE_DETAILED_LOGS = false

/**
 * Implementation of VinDetector using TensorFlow Lite
 */
internal class VinDetectorImpl(
    private val interpreter: Interpreter
) : VinDetector {

    companion object {
        // Model constants - Ultralytics YOLO exports use square input (e.g., 640x640)
        private const val MODEL_INPUT_SIZE = 640
        private const val PIXEL_SIZE = 3 // RGB

        // Thresholds
        private const val DEFAULT_CONF_THRESHOLD = 0.25f
        private const val NMS_IOU_THRESHOLD = 0.45f
    }

    // Pre-allocated buffers for better performance
    private val imgData: ByteBuffer = ByteBuffer.allocateDirect(
        MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * PIXEL_SIZE * 4 // 4 bytes per float
    ).apply {
        order(ByteOrder.nativeOrder())
    }
    private val intValues = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
    private val warmupDone = AtomicBoolean(false)
    @Volatile private var cachedOutputDimA = -1
    @Volatile private var cachedOutputDimB = -1
    @Volatile private var cachedOutputDynamic: Array<Array<FloatArray>>? = null

    override suspend fun detect(bitmap: Bitmap, confidenceThreshold: Float): DetectionResult =
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()

            if (ENABLE_DETAILED_LOGS) {
                SLog.d(TAG, "=== AI DETECTION START ===")
                SLog.d(TAG, "Input bitmap: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")
                SLog.d(TAG, "Requested confidence threshold: $confidenceThreshold")
            }

            try {
                maybeWarmupInterpreter()
                // Compute letterbox parameters (to later unmap predictions)
                val scaleFactor = min(
                    MODEL_INPUT_SIZE.toFloat() / bitmap.width,
                    MODEL_INPUT_SIZE.toFloat() / bitmap.height
                )
                val scaledWidth = (bitmap.width * scaleFactor).toInt()
                val scaledHeight = (bitmap.height * scaleFactor).toInt()
                val padLeft = (MODEL_INPUT_SIZE - scaledWidth) / 2f
                val padTop = (MODEL_INPUT_SIZE - scaledHeight) / 2f

                if (ENABLE_DETAILED_LOGS) {
                    SLog.d(TAG, "Letterbox params: scaleFactor=$scaleFactor, scaled=${scaledWidth}x${scaledHeight}, padding=(${padLeft},${padTop})")
                }

                // Preprocess (letterbox to 640x640)
                val preprocessedBitmap = preprocessImage(bitmap)
                if (ENABLE_DETAILED_LOGS) {
                    SLog.d(TAG, "Preprocessed bitmap: ${preprocessedBitmap.width}x${preprocessedBitmap.height}")
                }
                convertBitmapToByteBuffer(preprocessedBitmap)
                preprocessedBitmap.recycle()

                // Reuse output buffer for the configured model output shape.
                val outShape = interpreter.getOutputTensor(0).shape()
                // Expected formats (examples): [1, 8400, 6] or [1, 6, 8400] or [1, 8400, 85] / [1, 85, 8400]
                require(outShape.size == 3) { "Unexpected output tensor rank: ${outShape.contentToString()}" }
                val dimA = outShape[1]
                val dimB = outShape[2]
                val outputDynamic = getOrCreateOutputBuffer(dimA, dimB)
                val outputMap = mapOf(0 to outputDynamic)

                // Run inference
                interpreter.runForMultipleInputsOutputs(arrayOf(imgData), outputMap)

                val propsCandidates = setOf(5, 6, 84, 85)
                val propertiesCount: Int
                val numCandidates: Int
                val propsFirst: Boolean
                if (dimA in propsCandidates) {
                    propertiesCount = dimA
                    numCandidates = dimB
                    propsFirst = true
                } else if (dimB in propsCandidates) {
                    propertiesCount = dimB
                    numCandidates = dimA
                    propsFirst = false
                } else {
                    // Fallback heuristic: properties dimension is the smaller one
                    if (dimA <= dimB) {
                        propertiesCount = dimA
                        numCandidates = dimB
                        propsFirst = true
                    } else {
                        propertiesCount = dimB
                        numCandidates = dimA
                        propsFirst = false
                    }
                }

                if (ENABLE_DETAILED_LOGS) {
                    SLog.d(TAG, "Output tensor shape=${outShape.contentToString()}, props=${propertiesCount}, num=${numCandidates}, propsFirst=${propsFirst}")
                }

                fun getProp(candidateIndex: Int, propIndex: Int): Float {
                    return if (propsFirst) outputDynamic[0][propIndex][candidateIndex] else outputDynamic[0][candidateIndex][propIndex]
                }

                val rawBoxes = mutableListOf<BoundingBox>()
                val confThresh = max(confidenceThreshold, DEFAULT_CONF_THRESHOLD)

                if (ENABLE_DETAILED_LOGS) {
                    SLog.d(TAG, "Scanning ${numCandidates} candidates...")
                }

                val topIndices: java.util.PriorityQueue<Pair<Int, Float>>? =
                    if (ENABLE_DETAILED_LOGS) java.util.PriorityQueue(6) { a: Pair<Int, Float>, b -> a.second.compareTo(b.second) }
                    else null

                for (i in 0 until numCandidates) {
                    val cx = getProp(i, 0) * MODEL_INPUT_SIZE
                    val cy = getProp(i, 1) * MODEL_INPUT_SIZE
                    val w = getProp(i, 2) * MODEL_INPUT_SIZE
                    val h = getProp(i, 3) * MODEL_INPUT_SIZE
                    val obj = if (propertiesCount > 4) getProp(i, 4) else 1f

                    var clsScore = 1f
                    if (propertiesCount > 5) {
                        var maxCls = 0f
                        var idx = 5
                        while (idx < propertiesCount) {
                            val s = getProp(i, idx)
                            if (s > maxCls) maxCls = s
                            idx++
                        }
                        clsScore = maxCls
                    }

                    val conf = obj * clsScore

                    if (ENABLE_DETAILED_LOGS && topIndices != null) {
                        if (topIndices.size < 5) {
                            topIndices.add(i to conf)
                        } else if (conf > (topIndices.peek()?.second ?: Float.NEGATIVE_INFINITY)) {
                            topIndices.poll()
                            topIndices.add(i to conf)
                        }
                    }

                    if (conf < confThresh) continue

                    // Convert to pixel box in model space (640x640)
                    val leftPxModel = cx - w / 2f
                    val topPxModel = cy - h / 2f
                    val rightPxModel = cx + w / 2f
                    val bottomPxModel = cy + h / 2f

                    // Unletterbox: map from 640x640 (with padding) to content area (scaledWidth x scaledHeight)
                    val leftContent = ((leftPxModel - padLeft) / scaledWidth).coerceIn(0f, 1f)
                    val topContent = ((topPxModel - padTop) / scaledHeight).coerceIn(0f, 1f)
                    val rightContent = ((rightPxModel - padLeft) / scaledWidth).coerceIn(0f, 1f)
                    val bottomContent = ((bottomPxModel - padTop) / scaledHeight).coerceIn(0f, 1f)

                    val passesValidation = rightContent > leftContent && bottomContent > topContent

                    if (passesValidation) {
                        rawBoxes.add(
                            BoundingBox(
                                left = leftContent,
                                top = topContent,
                                right = rightContent,
                                bottom = bottomContent,
                                confidence = conf
                            )
                        )
                    }
                }

                // Log top 5 candidates for debugging
                if (ENABLE_DETAILED_LOGS) {
                    SLog.d(TAG, "=== TOP 5 CANDIDATES DEBUG ===")
                    val sortedTop = topIndices!!.sortedByDescending { it.second }
                    for ((idx, conf) in sortedTop) {
                        val cx = getProp(idx, 0) * MODEL_INPUT_SIZE
                        val cy = getProp(idx, 1) * MODEL_INPUT_SIZE
                        val w = getProp(idx, 2) * MODEL_INPUT_SIZE
                        val h = getProp(idx, 3) * MODEL_INPUT_SIZE
                        val obj = if (propertiesCount > 4) getProp(idx, 4) else 1f

                        var clsScore = 1f
                        if (propertiesCount > 5) {
                            var maxCls = 0f
                            var i = 5
                            while (i < propertiesCount) {
                                val s = getProp(idx, i)
                                if (s > maxCls) maxCls = s
                                i++
                            }
                            clsScore = maxCls
                        }

                        val leftPxModel = cx - w / 2f
                        val topPxModel = cy - h / 2f
                        val rightPxModel = cx + w / 2f
                        val bottomPxModel = cy + h / 2f

                        val leftContent = ((leftPxModel - padLeft) / scaledWidth)
                        val topContent = ((topPxModel - padTop) / scaledHeight)
                        val rightContent = ((rightPxModel - padLeft) / scaledWidth)
                        val bottomContent = ((bottomPxModel - padTop) / scaledHeight)

                        val valid = rightContent > leftContent && bottomContent > topContent

                        SLog.d(TAG, "Candidate[$idx]: conf=$conf (obj=$obj, cls=$clsScore)")
                        SLog.d(TAG, "  Raw Model Box: cx=$cx, cy=$cy, w=$w, h=$h")
                        SLog.d(TAG, "  Px Model Box: L=$leftPxModel, T=$topPxModel, R=$rightPxModel, B=$bottomPxModel")
                        SLog.d(TAG, "  Content Box (pre-coerce): L=$leftContent, T=$topContent, R=$rightContent, B=$bottomContent")
                        SLog.d(TAG, "  Valid: $valid")
                    }
                }

                // Apply NMS to reduce duplicates
                val nmsBoxes = nonMaxSuppression(rawBoxes, NMS_IOU_THRESHOLD)

                val processingTime = System.currentTimeMillis() - startTime

                if (ENABLE_DETAILED_LOGS && nmsBoxes.isNotEmpty()) {
                    nmsBoxes.forEachIndexed { idx, box ->
                        SLog.d(TAG, "Box[$idx]: confidence=${box.confidence}, coords=(${box.left},${box.top},${box.right},${box.bottom})")
                    }
                }

                DetectionResult(
                    boundingBoxes = nmsBoxes,
                    processingTimeMs = processingTime
                )
            } catch (e: Exception) {
                SLog.e(TAG, "Error during VIN detection", e)
                DetectionResult(
                    boundingBoxes = emptyList(),
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

    private fun maybeWarmupInterpreter() {
        if (warmupDone.compareAndSet(false, true)) {
            repeat(3) {
                imgData.rewind()
                val outShape = interpreter.getOutputTensor(0).shape()
                val dimA = outShape[1]
                val dimB = outShape[2]
                val warmupOutput = getOrCreateOutputBuffer(dimA, dimB)
                interpreter.runForMultipleInputsOutputs(arrayOf(imgData), mapOf(0 to warmupOutput))
            }
        }
    }

    private fun getOrCreateOutputBuffer(dimA: Int, dimB: Int): Array<Array<FloatArray>> {
        val cached = cachedOutputDynamic
        if (cached != null && dimA == cachedOutputDimA && dimB == cachedOutputDimB) {
            return cached
        }
        val created = Array(1) { Array(dimA) { FloatArray(dimB) } }
        cachedOutputDimA = dimA
        cachedOutputDimB = dimB
        cachedOutputDynamic = created
        return created
    }

    override fun preprocessImage(bitmap: Bitmap): Bitmap {
        SLog.d(TAG, "Original bitmap dimensions: ${bitmap.width}x${bitmap.height}")
        // Create a square bitmap with MODEL_INPUT_SIZE dimensions
        val scaleFactor = min(
            MODEL_INPUT_SIZE.toFloat() / bitmap.width,
            MODEL_INPUT_SIZE.toFloat() / bitmap.height
        )

        val scaledWidth = (bitmap.width * scaleFactor).toInt()
        val scaledHeight = (bitmap.height * scaleFactor).toInt()

        // Create scaled bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // Create square bitmap with padding if needed
        val outputBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Calculate padding to center the image
        val left = (MODEL_INPUT_SIZE - scaledWidth) / 2f
        val top = (MODEL_INPUT_SIZE - scaledHeight) / 2f

        // Draw scaled bitmap centered on canvas
        canvas.drawBitmap(scaledBitmap, left, top, null)

        // Clean up
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return outputBitmap
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        imgData.rewind()

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Convert the image pixels to floating point values
        for (pixel in intValues) {
            val r = (pixel shr 16 and 0xFF) / 255.0f
            val g = (pixel shr 8 and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            imgData.putFloat(r)
            imgData.putFloat(g)
            imgData.putFloat(b)
        }
    }

    // Basic NMS for single-class detections
    private fun nonMaxSuppression(boxes: List<BoundingBox>, iouThreshold: Float): List<BoundingBox> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<BoundingBox>()
        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            result.add(current)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val other = it.next()
                val iou = computeIoU(current, other)
                if (iou > iouThreshold) it.remove()
            }
        }
        return result
    }

    private fun computeIoU(a: BoundingBox, b: BoundingBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f
        return interArea / union
    }
}
