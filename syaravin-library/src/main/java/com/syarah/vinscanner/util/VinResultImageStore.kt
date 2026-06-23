package com.syarah.vinscanner.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RESULT_DIRECTORY = "syaravin-results"
private const val MAX_RESULT_BYTES = 3 * 1024 * 1024
private const val MAX_RESULT_PIXELS = 2_500_000
private const val MAX_RESULT_DIMENSION = 2200
private const val RESULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L

internal object VinResultImageStore {
    suspend fun save(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, RESULT_DIRECTORY).apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - RESULT_MAX_AGE_MS
        directory.listFiles()
            ?.filter { file -> file.lastModified() < staleBefore }
            ?.forEach(File::delete)

        val file = File(directory, "vin-${UUID.randomUUID()}.jpg")
        file.outputStream().buffered().use { output ->
            output.write(encodeBounded(bitmap))
        }
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.syaravin.fileprovider",
            file,
        )
    }

    private fun encodeBounded(source: Bitmap): ByteArray {
        var working = ImagePreprocessor.downscaleForDisplay(
            source,
            maxDimension = MAX_RESULT_DIMENSION,
            maxPixels = MAX_RESULT_PIXELS,
        )
        var ownsWorking = working !== source
        try {
            repeat(5) {
                for (quality in intArrayOf(90, 80, 70, 60, 50)) {
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(working.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            "Failed to encode VIN result image"
                        }
                        output.toByteArray()
                    }
                    if (bytes.size <= MAX_RESULT_BYTES) return bytes
                }

                val scaled = Bitmap.createScaledBitmap(
                    working,
                    (working.width * 0.75f).toInt().coerceAtLeast(1),
                    (working.height * 0.75f).toInt().coerceAtLeast(1),
                    true,
                )
                if (ownsWorking) working.recycle()
                working = scaled
                ownsWorking = true
            }
            error("Unable to bound VIN result image below $MAX_RESULT_BYTES bytes")
        } finally {
            if (ownsWorking && !working.isRecycled) working.recycle()
        }
    }
}
