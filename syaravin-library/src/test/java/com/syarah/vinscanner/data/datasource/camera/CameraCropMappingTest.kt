package com.syarah.vinscanner.data.datasource.camera

import com.syarah.vinscanner.domain.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCropMappingTest {
    private val roi = BoundingBox(left = 0.1f, top = 0.2f, right = 0.7f, bottom = 0.6f)

    @Test
    fun `maps output ROI back into source coordinates for every rotation`() {
        assertCropNear(SourceCrop(100, 100, 700, 300), sourceCrop(1000, 500, 0, roi))
        assertCropNear(SourceCrop(200, 150, 600, 450), sourceCrop(1000, 500, 90, roi))
        assertCropNear(SourceCrop(300, 200, 900, 400), sourceCrop(1000, 500, 180, roi))
        assertCropNear(SourceCrop(400, 50, 800, 350), sourceCrop(1000, 500, 270, roi))
    }

    @Test
    fun `aligns fallback crop to YUV chroma boundaries`() {
        assertEquals(
            SourceCrop(100, 200, 702, 402),
            evenSourceCrop(SourceCrop(101, 201, 701, 401), 1000, 500),
        )
    }

    private fun assertCropNear(expected: SourceCrop, actual: SourceCrop) {
        assertTrue(kotlin.math.abs(expected.left - actual.left) <= 1)
        assertTrue(kotlin.math.abs(expected.top - actual.top) <= 1)
        assertTrue(kotlin.math.abs(expected.right - actual.right) <= 1)
        assertTrue(kotlin.math.abs(expected.bottom - actual.bottom) <= 1)
    }
}
