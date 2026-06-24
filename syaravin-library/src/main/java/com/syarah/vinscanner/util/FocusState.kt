package com.syarah.vinscanner.util

import android.hardware.camera2.CaptureResult

/**
 * Tracks the camera autofocus state reported per analysis frame (via a Camera2 capture callback),
 * so auto-detect can wait for focus to settle before accepting a VIN.
 */
internal object FocusState {
    @Volatile
    private var afState: Int? = null

    fun update(state: Int?) {
        afState = state
    }

    /**
     * True when focus is settled. Unknown state (e.g. fixed-focus cameras that never report AF
     * state) returns true so we never block those devices indefinitely.
     */
    val isStable: Boolean
        get() = when (afState) {
            null -> true
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            -> true
            else -> false
        }
}
