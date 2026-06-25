package com.kazimi.syaravin.presentation.scanner

/**
 * Pure, bitmap-free decision state machine for auto-accepting a detected VIN, used to trade
 * motion/defocus blur for a bounded amount of latency.
 *
 * The rule per valid+positioned+focus-stable read:
 *  - sharpness ≥ [sharpThreshold]  → accept this frame immediately.
 *  - otherwise (soft frame)        → remember the sharpest soft frame seen, keep scanning, and once
 *                                    [acceptTimeoutMs] has elapsed since the first soft read, accept
 *                                    the best soft frame rather than stalling forever.
 *
 * Bitmap lifecycle (recycling the stashed result image, transferring ownership on accept) lives in
 * the caller; this class only decides *what* to do so it stays unit-testable without Android.
 *
 * Not thread-safe: the scanner serialises frame processing under a mutex, so a single instance is
 * only ever touched by one frame at a time.
 */
internal class VinAcceptDecider(
    private val enabled: Boolean,
    private val sharpThreshold: Double,
    private val acceptTimeoutMs: Long,
    private val resetGapMs: Long,
) {
    enum class Action {
        /** Sharp enough, or timed out with the current frame being the sharpest → commit current. */
        ACCEPT_CURRENT,

        /** Timed out and a previously stashed frame is sharper → commit the stashed frame. */
        ACCEPT_STASHED,

        /** Soft, but the sharpest soft frame so far → stash it and keep scanning. */
        STASH_CURRENT,

        /** Soft and not better than what is already stashed → drop it and keep scanning. */
        DISCARD_CURRENT,
    }

    private var bestSharpness = Double.NEGATIVE_INFINITY
    private var pending = false
    private var firstSoftReadMs = 0L
    private var lastReadMs = 0L

    /** True while a soft candidate is being held, waiting for a sharper frame or the timeout. */
    val hasPending: Boolean get() = pending

    fun onValidRead(
        sharpness: Double,
        nowMs: Long,
    ): Action {
        if (!enabled) return Action.ACCEPT_CURRENT

        lastReadMs = nowMs

        if (sharpness >= sharpThreshold) {
            // Sharp frame wins outright; any held soft candidate is abandoned by the caller's reset.
            return Action.ACCEPT_CURRENT
        }

        if (!pending) {
            pending = true
            firstSoftReadMs = nowMs
        }
        val timedOut = nowMs - firstSoftReadMs >= acceptTimeoutMs
        val currentIsBest = sharpness > bestSharpness

        if (timedOut) {
            return if (currentIsBest) Action.ACCEPT_CURRENT else Action.ACCEPT_STASHED
        }

        return if (currentIsBest) {
            bestSharpness = sharpness
            Action.STASH_CURRENT
        } else {
            Action.DISCARD_CURRENT
        }
    }

    /**
     * True once a held candidate's hold window ([acceptTimeoutMs]) has elapsed — the caller should
     * commit the stashed best frame. Evaluated every frame (not only on a fresh valid read), so a
     * lone soft-but-valid read still commits even if later frames are misreads that never re-enter
     * the accept path.
     */
    fun isAcceptTimedOut(nowMs: Long): Boolean = hasPending && (nowMs - firstSoftReadMs) >= acceptTimeoutMs

    /**
     * True when a held candidate has gone stale — no valid read for [resetGapMs] — so the caller
     * should drop it (recycling the stashed bitmap) rather than committing a frame the user has
     * already moved away from. [resetGapMs] must exceed [acceptTimeoutMs] so timeout-accept wins.
     */
    fun isStale(nowMs: Long): Boolean = hasPending && (nowMs - lastReadMs) >= resetGapMs

    fun reset() {
        bestSharpness = Double.NEGATIVE_INFINITY
        pending = false
        firstSoftReadMs = 0L
        lastReadMs = 0L
    }
}
