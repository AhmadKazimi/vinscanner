package com.syarah.vinscanner.presentation.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureDecodeSampleSizeTest {
    @Test
    fun `keeps full resolution at or below 1080p`() {
        assertEquals(1, captureDecodeSampleSize(1920, 1080))
        assertEquals(1, captureDecodeSampleSize(1080, 1920))
    }

    @Test
    fun `downsamples oversized captures`() {
        assertEquals(2, captureDecodeSampleSize(4000, 3000))
        assertEquals(4, captureDecodeSampleSize(8000, 6000))
    }

    @Test
    fun `handles invalid dimensions`() {
        assertEquals(1, captureDecodeSampleSize(-1, -1))
    }
}
