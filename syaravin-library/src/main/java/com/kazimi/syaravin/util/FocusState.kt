package com.kazimi.syaravin.util

import android.hardware.camera2.CaptureResult

/**
 * Tracks the camera autofocus state reported per analysis frame (via a Camera2 capture callback),
 * so auto-detect can wait for focus to settle before accepting a VIN.
 */
internal object FocusState {
    /**
     * Gate strictness, overridable via the `syaravin.focus.gate.mode` system property:
     *  - `passive` (default): accept both `PASSIVE_FOCUSED` and `FOCUSED_LOCKED`. Required for
     *    continuous-AF devices, which mostly report `PASSIVE_FOCUSED` and rarely lock.
     *  - `locked`: accept only `FOCUSED_LOCKED`. Stricter, but risks stalling auto-accept on
     *    continuous-AF devices that never report a locked state. For on-device A/B testing.
     *
     * In either mode, transient/unfocused states (scanning, `PASSIVE_UNFOCUSED`,
     * `NOT_FOCUSED_LOCKED`, inactive) are rejected, and an unknown/`null` state (fixed-focus
     * cameras that never report AF state) is treated as stable so those devices never block.
     */
    private val lockedOnly: Boolean =
        System.getProperty("syaravin.focus.gate.mode")?.trim()?.lowercase() == "locked"

    @Volatile
    private var afState: Int? = null

    fun update(state: Int?) {
        afState = state
    }

    /**
     * True when focus is settled enough to accept a VIN. See [lockedOnly] for the accepted set.
     */
    val isStable: Boolean
        get() =
            when (afState) {
                // Fixed-focus / non-reporting cameras: never block.
                null -> true

                // Fully locked focus — always acceptable.
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> true

                // Continuous-AF "settled" state — acceptable unless running in locked-only mode.
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> !lockedOnly

                // Explicitly reject in-progress / failed-focus states (was an implicit `else`).
                CaptureResult.CONTROL_AF_STATE_INACTIVE,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
                -> false

                else -> false
            }
}
