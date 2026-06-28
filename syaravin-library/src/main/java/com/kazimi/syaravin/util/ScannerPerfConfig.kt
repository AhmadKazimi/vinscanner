package com.kazimi.syaravin.util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val TAG = LogTags.LIBRARY

internal object ScannerPerfConfig {
    /**
     * Compile-time switch for the scanning strategy:
     *  - true  → use Google ML Kit's full-frame Latin text recognition directly (no custom model).
     *  - false → use the custom TFLite VIN detector + per-box ML Kit OCR.
     */
    const val USE_GOOGLE_OCR_ONLY = true

    private const val DEFAULT_INFERENCE_INTERVAL_MS = 500L
    // 3:4 to match the device's native analysis family (observed 720×960); a 9:16 request gets
    // coerced down. Explicit cap — not HIGHEST — to avoid a device picking a huge sensor size.
    private const val DEFAULT_IMAGE_ANALYSIS_WIDTH = 1440
    private const val DEFAULT_IMAGE_ANALYSIS_HEIGHT = 1920
    private const val DEFAULT_INTERPRETER_THREADS = 4
    private const val DEFAULT_LOG_EVERY_N_FRAMES = 30

    val perfLogsEnabled: Boolean =
        java.lang.Boolean.parseBoolean(System.getProperty("syaravin.perf.logs", "true"))

    val inferenceIntervalMs: Long =
        max(
            0L,
            System
                .getProperty("syaravin.perf.inference.interval.ms")
                ?.toLongOrNull()
                ?: DEFAULT_INFERENCE_INTERVAL_MS,
        )

    val imageAnalysisWidth: Int =
        System
            .getProperty("syaravin.perf.camera.analysis.width")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_IMAGE_ANALYSIS_WIDTH

    val imageAnalysisHeight: Int =
        System
            .getProperty("syaravin.perf.camera.analysis.height")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_IMAGE_ANALYSIS_HEIGHT

    /**
     * When true, request the device's highest available ImageAnalysis resolution instead of the
     * [imageAnalysisWidth]×[imageAnalysisHeight] target. Maximum crop detail, but the largest
     * thermal/CPU cost (bigger YUV→RGB conversions). Off by default.
     */
    val imageAnalysisPreferHighestRes: Boolean =
        java.lang.Boolean.parseBoolean(
            System.getProperty("syaravin.perf.camera.analysis.highest", "false"),
        )

    val interpreterThreads: Int =
        System
            .getProperty("syaravin.perf.tflite.threads")
            ?.toIntOrNull()
            ?.takeIf { it in listOf(1, 2, 4, 6) }
            ?: DEFAULT_INTERPRETER_THREADS

    // --- Sharpness accept gate (#2) -------------------------------------------------------------
    // Trades motion/defocus blur for a bounded amount of accept latency. All knobs overridable via
    // System.getProperty so the threshold can be tuned per-device against logged values.

    // Tuned against on-device values at 1920×1440: close/sharp reads ~6000–13000, valid far/small
    // reads ~1200–1600, motion-blurred lower. 800 accepts legitimate reads immediately; only
    // clearly blurred frames fall below and are held for a sharper one (time-bounded fallback).
    private const val DEFAULT_SHARPNESS_THRESHOLD = 800.0
    private const val DEFAULT_SHARPNESS_ACCEPT_TIMEOUT_MS = 1200L
    private const val DEFAULT_SHARPNESS_RESET_GAP_MS = 1500L
    private const val DEFAULT_SHARPNESS_SAMPLE_MAX_EDGE = 320

    /** When false the gate is bypassed entirely (accept the first valid read, as before). */
    val sharpnessGateEnabled: Boolean =
        java.lang.Boolean.parseBoolean(System.getProperty("syaravin.perf.sharpness.enabled", "true"))

    /** Min variance-of-Laplacian to accept a frame outright. MUST be tuned per-device. */
    val sharpnessThreshold: Double =
        System
            .getProperty("syaravin.perf.sharpness.threshold")
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 }
            ?: DEFAULT_SHARPNESS_THRESHOLD

    /** After this long holding only soft reads, accept the sharpest soft frame seen. */
    val sharpnessAcceptTimeoutMs: Long =
        max(
            0L,
            System.getProperty("syaravin.perf.sharpness.timeout.ms")?.toLongOrNull()
                ?: DEFAULT_SHARPNESS_ACCEPT_TIMEOUT_MS,
        )

    /** Drop a held soft candidate after this long without any valid read (user moved away). */
    val sharpnessResetGapMs: Long =
        max(
            0L,
            System.getProperty("syaravin.perf.sharpness.resetgap.ms")?.toLongOrNull()
                ?: DEFAULT_SHARPNESS_RESET_GAP_MS,
        )

    /** Downsample long edge before measuring sharpness, to bound per-frame cost. */
    val sharpnessSampleMaxEdge: Int =
        System
            .getProperty("syaravin.perf.sharpness.sample.maxedge")
            ?.toIntOrNull()
            ?.takeIf { it >= 16 }
            ?: DEFAULT_SHARPNESS_SAMPLE_MAX_EDGE

    val delegateMode: String =
        System
            .getProperty("syaravin.perf.tflite.delegate", "gpu")
            ?.trim()
            ?.lowercase()
            ?: "gpu"

    val useXnnpack: Boolean =
        java.lang.Boolean.parseBoolean(
            System.getProperty(
                "syaravin.perf.tflite.xnnpack",
                (delegateMode == "xnnpack" || delegateMode == "cpu").toString(),
            ),
        )

    val frameTiming = FrameTimingLogger(DEFAULT_LOG_EVERY_N_FRAMES, perfLogsEnabled)
    val overlayTiming = ThrottledDurationLogger("overlay_render", DEFAULT_LOG_EVERY_N_FRAMES, perfLogsEnabled)
}

internal class FrameTimingLogger(
    private val logEveryNFrames: Int,
    private val enabled: Boolean,
) {
    private val frameCounter = AtomicLong(0)
    private val intervalStartNs = AtomicLong(0L)
    private val intervalDurationNs = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)

    fun onFrameDropped() {
        if (enabled) droppedFrames.incrementAndGet()
    }

    fun onFrameFinished(
        totalDurationNs: Long,
        stageSummary: String,
    ) {
        if (!enabled) return

        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (intervalStartNs.get() == 0L) {
            intervalStartNs.compareAndSet(0L, nowNs)
        }
        val frameIndex = frameCounter.incrementAndGet()
        intervalDurationNs.addAndGet(totalDurationNs)
        if (frameIndex % logEveryNFrames != 0L) return

        val elapsedNs = nowNs - intervalStartNs.getAndSet(nowNs)
        val avgE2eMs = intervalDurationNs.getAndSet(0L) / 1_000_000.0 / logEveryNFrames
        val fps = if (elapsedNs > 0L) (logEveryNFrames * 1_000_000_000.0) / elapsedNs else 0.0
        val dropped = droppedFrames.getAndSet(0L)

        SLog.w(
            TAG,
            "PERF_FRAME: count=$logEveryNFrames fps=${"%.2f".format(
                fps,
            )} avg_e2e_ms=${"%.2f".format(avgE2eMs)} dropped=$dropped $stageSummary",
        )
    }
}

internal class ThrottledDurationLogger(
    private val name: String,
    private val everyN: Int,
    private val enabled: Boolean,
) {
    private val count = AtomicInteger(0)

    fun log(durationNs: Long) {
        if (!enabled) return
        if (count.incrementAndGet() % everyN == 0) {
            SLog.w(TAG, "PERF_STAGE: $name=${"%.2f".format(durationNs / 1_000_000.0)}ms")
        }
    }
}
