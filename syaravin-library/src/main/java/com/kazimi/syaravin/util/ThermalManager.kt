package com.kazimi.syaravin.util

import android.content.Context
import android.os.PowerManager

private const val TAG = LogTags.LIBRARY

/** Lifecycle-controlled bridge to Android's system thermal status. */
internal class ThermalManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    val currentStatus: Int
        get() = powerManager.currentThermalStatus

    fun start(onStatusChanged: (Int) -> Unit) {
        if (listener != null) return
        val newListener =
            PowerManager.OnThermalStatusChangedListener { status ->
                SLog.w(TAG, "System thermal status changed to $status")
                onStatusChanged(status)
            }
        listener = newListener
        powerManager.addThermalStatusListener(appContext.mainExecutor, newListener)
        onStatusChanged(currentStatus)
    }

    fun stop() {
        listener?.let(powerManager::removeThermalStatusListener)
        listener = null
    }

    companion object {
        fun inferenceIntervalMs(
            baseIntervalMs: Long,
            status: Int,
        ): Long =
            when {
                status >= PowerManager.THERMAL_STATUS_EMERGENCY -> baseIntervalMs * 10
                status >= PowerManager.THERMAL_STATUS_CRITICAL -> baseIntervalMs * 6
                status >= PowerManager.THERMAL_STATUS_SEVERE -> baseIntervalMs * 4
                status >= PowerManager.THERMAL_STATUS_MODERATE -> baseIntervalMs * 2
                status >= PowerManager.THERMAL_STATUS_LIGHT -> baseIntervalMs * 3 / 2
                else -> baseIntervalMs
            }
    }
}
