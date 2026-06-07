package com.syarah.vinscanner.util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val TAG = LogTags.LIBRARY

internal object ScannerPerfConfig {
    private const val DEFAULT_INFERENCE_INTERVAL_MS = 500L
    private const val DEFAULT_IMAGE_ANALYSIS_WIDTH = 540
    private const val DEFAULT_IMAGE_ANALYSIS_HEIGHT = 960
    private const val DEFAULT_INTERPRETER_THREADS = 4
    private const val DEFAULT_LOG_EVERY_N_FRAMES = 30

    val perfLogsEnabled: Boolean =
        java.lang.Boolean.parseBoolean(System.getProperty("syaravin.perf.logs", "true"))

    val inferenceIntervalMs: Long =
        max(
            0L,
            System.getProperty("syaravin.perf.inference.interval.ms")
                ?.toLongOrNull()
                ?: DEFAULT_INFERENCE_INTERVAL_MS
        )

    val imageAnalysisWidth: Int =
        System.getProperty("syaravin.perf.camera.analysis.width")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_IMAGE_ANALYSIS_WIDTH

    val imageAnalysisHeight: Int =
        System.getProperty("syaravin.perf.camera.analysis.height")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_IMAGE_ANALYSIS_HEIGHT

    val interpreterThreads: Int =
        System.getProperty("syaravin.perf.tflite.threads")
            ?.toIntOrNull()
            ?.takeIf { it in listOf(1, 2, 4, 6) }
            ?: DEFAULT_INTERPRETER_THREADS

    val delegateMode: String =
        System.getProperty("syaravin.perf.tflite.delegate", "gpu")
            ?.trim()
            ?.lowercase()
            ?: "gpu"

    val useXnnpack: Boolean =
        java.lang.Boolean.parseBoolean(
            System.getProperty(
                "syaravin.perf.tflite.xnnpack",
                (delegateMode == "xnnpack" || delegateMode == "cpu").toString()
            )
        )

    val frameTiming = FrameTimingLogger(DEFAULT_LOG_EVERY_N_FRAMES, perfLogsEnabled)
    val overlayTiming = ThrottledDurationLogger("overlay_render", DEFAULT_LOG_EVERY_N_FRAMES, perfLogsEnabled)
}

internal class FrameTimingLogger(
    private val logEveryNFrames: Int,
    private val enabled: Boolean
) {
    private val frameCounter = AtomicLong(0)
    private val intervalStartNs = AtomicLong(0L)
    private val intervalDurationNs = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)

    fun onFrameDropped() {
        if (enabled) droppedFrames.incrementAndGet()
    }

    fun onFrameFinished(totalDurationNs: Long, stageSummary: String) {
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
            "PERF_FRAME: count=$logEveryNFrames fps=${"%.2f".format(fps)} avg_e2e_ms=${"%.2f".format(avgE2eMs)} dropped=$dropped $stageSummary"
        )
    }
}

internal class ThrottledDurationLogger(
    private val name: String,
    private val everyN: Int,
    private val enabled: Boolean
) {
    private val count = AtomicInteger(0)

    fun log(durationNs: Long) {
        if (!enabled) return
        if (count.incrementAndGet() % everyN == 0) {
            SLog.w(TAG, "PERF_STAGE: $name=${"%.2f".format(durationNs / 1_000_000.0)}ms")
        }
    }
}
