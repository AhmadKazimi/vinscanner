package com.kazimi.syaravin.presentation.scanner

import android.graphics.Bitmap

/**
 * Holds the cross-frame state for the sharpness accept gate: the pure [VinAcceptDecider] plus the
 * display-ready bitmap of the sharpest soft frame seen so far. Owns the stashed bitmap's lifecycle.
 *
 * Not thread-safe; only touched by one frame at a time under the scanner's analysis mutex.
 */
internal class VinAcceptState(
    private val decider: VinAcceptDecider,
) {
    var stashedVin: String? = null
        private set
    var stashedConfidence: Float = 0f
        private set
    private var stashedBitmap: Bitmap? = null

    fun onValidRead(
        sharpness: Double,
        nowMs: Long,
    ): VinAcceptDecider.Action = decider.onValidRead(sharpness, nowMs)

    fun isStale(nowMs: Long): Boolean = decider.isStale(nowMs)

    fun isAcceptTimedOut(nowMs: Long): Boolean = decider.isAcceptTimedOut(nowMs)

    val hasPending: Boolean get() = stashedVin != null

    /** Replace the stashed soft candidate, recycling any prior stashed bitmap. */
    fun stash(
        vin: String,
        confidence: Float,
        bitmap: Bitmap?,
    ) {
        if (bitmap !== stashedBitmap) recycleStash()
        stashedVin = vin
        stashedConfidence = confidence
        stashedBitmap = bitmap
    }

    /** Hand off the stashed bitmap to the caller (ownership transfers; gate no longer recycles it). */
    fun takeStashedBitmap(): Bitmap? {
        val bitmap = stashedBitmap
        stashedBitmap = null
        return bitmap
    }

    /** Reset the decision state and recycle any stashed bitmap still owned here. */
    fun reset() {
        decider.reset()
        recycleStash()
        stashedVin = null
        stashedConfidence = 0f
    }

    private fun recycleStash() {
        stashedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        stashedBitmap = null
    }
}
