package com.kazimi.syaravin.util

import android.util.Log

/**
 * Simple thermal management utility to prevent overheating
 */
object ThermalManager {
    private const val TAG = "ThermalManager"
    
    // Performance tracking
    private var processingCount = 0L
    private var totalProcessingTime = 0L
    private var startTime = System.currentTimeMillis()
    
    // Thermal throttling thresholds
    private const val MAX_AVG_PROCESSING_TIME = 200L // ms
    private const val MAX_PROCESSING_RATE = 3.0 // frames per second
    
    fun shouldThrottle(): Boolean {
        val currentTime = System.currentTimeMillis()
        val elapsedSeconds = (currentTime - startTime) / 1000.0
        
        // Reset counters every minute
        if (elapsedSeconds > 60) {
            reset()
            return false
        }
        
        // Check processing rate
        val currentRate = processingCount / maxOf(elapsedSeconds, 1.0)
        if (currentRate > MAX_PROCESSING_RATE) {
            Log.w(TAG, "Throttling due to high processing rate: $currentRate fps")
            return true
        }
        
        // Check average processing time
        val avgProcessingTime = if (processingCount > 0) {
            totalProcessingTime / processingCount
        } else 0L
        
        if (avgProcessingTime > MAX_AVG_PROCESSING_TIME) {
            Log.w(TAG, "Throttling due to high processing time: ${avgProcessingTime}ms")
            return true
        }
        
        return false
    }
    
    fun recordProcessing(processingTimeMs: Long) {
        processingCount++
        totalProcessingTime += processingTimeMs
    }
    
    private fun reset() {
        processingCount = 0L
        totalProcessingTime = 0L
        startTime = System.currentTimeMillis()
        Log.d(TAG, "Performance counters reset")
    }
    
    fun getStats(): String {
        val avgTime = if (processingCount > 0) totalProcessingTime / processingCount else 0L
        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = processingCount / maxOf(elapsedSeconds, 1.0)
        return "Rate: %.1f fps, Avg: %dms".format(rate, avgTime)
    }
}
