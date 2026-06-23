package com.syarah.vinscanner.presentation.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureDecodeSampleSizeTest {
    @Test
    fun `keeps full resolution at or below 720p`() {
        assertEquals(1, captureDecodeSampleSize(1280, 720))
        assertEquals(1, captureDecodeSampleSize(720, 1280))
    }

    @Test
    fun `downsamples oversized captures`() {
        assertEquals(4, captureDecodeSampleSize(4000, 3000))
        assertEquals(8, captureDecodeSampleSize(8000, 6000))
    }

    @Test
    fun `handles invalid dimensions`() {
        assertEquals(1, captureDecodeSampleSize(-1, -1))
    }
}
