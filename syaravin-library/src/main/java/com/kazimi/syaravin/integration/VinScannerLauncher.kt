package com.kazimi.syaravin.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kazimi.syaravin.VinScanResult
import com.kazimi.syaravin.VinScanner
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val VIN_IMAGE_QUALITY = 100

class CoreVinScanLauncher(
    private val onLaunch: () -> Unit,
) {
    fun launch() {
        onLaunch()
    }
}

data class VinScannerCapturedResult(
    val vin: String,
    val imagePath: String,
)

data class VinScannerLogCallbacks(
    val info: (action: String, details: Map<String, Any?>) -> Unit = { _, _ -> },
    val error: (
        action: String,
        error: String,
        exception: Throwable?,
        details: Map<String, Any?>,
    ) -> Unit = { _, _, _, _ -> },
    val exception: (message: String, exception: Exception) -> Unit = { _, _ -> },
)

@Composable
fun rememberVinScannerResultLauncher(
    eventPrefix: String,
    imageSubDir: String? = null,
    logCallbacks: VinScannerLogCallbacks = VinScannerLogCallbacks(),
    onSuccess: (VinScannerCapturedResult) -> Unit,
    onCancelled: () -> Unit,
    onError: (String) -> Unit,
): CoreVinScanLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vinScannerLauncher = rememberLauncherForActivityResult(
        contract = VinScanner.Contract(),
    ) { result ->
        logCallbacks.info(
            "${eventPrefix}_result_received",
            mapOf("resultType" to result::class.simpleName),
        )

        when (result) {
            is VinScanResult.Success -> {
                val vin = result.vinNumber
                logCallbacks.info(
                    "${eventPrefix}_scan_success",
                    mapOf(
                        "vinLength" to vin.value.length,
                        "confidence" to vin.confidence,
                        "isValid" to vin.isValid,
                    ),
                )

                scope.launch {
                    val startedAt = System.currentTimeMillis()
                    val vinImage = decodeVinBitmapFromUri(context, vin.croppedImageUri) { e ->
                        logCallbacks.exception("Failed to decode VIN image from uri", e)
                        logCallbacks.error(
                            "${eventPrefix}_decode_uri_exception",
                            e.message.orEmpty(),
                            e,
                            emptyMap(),
                        )
                    }

                    logCallbacks.info(
                        "${eventPrefix}_image_received",
                        mapOf(
                            "hasImageUri" to (vin.croppedImageUri != null),
                            "hasImage" to (vinImage != null),
                            "width" to vinImage?.width,
                            "height" to vinImage?.height,
                        ),
                    )

                    val filePath = saveVinImageToFile(
                        context = context,
                        bitmap = vinImage,
                        subDir = imageSubDir,
                        logCallbacks = logCallbacks,
                    )
                    vinImage.safeRecycle(logCallbacks)

                    logCallbacks.info(
                        "${eventPrefix}_image_saved",
                        mapOf(
                            "saved" to filePath.isNotEmpty(),
                            "hasImage" to (vinImage != null),
                            "path" to filePath.takeIf { it.isNotEmpty() },
                            "durationMs" to (System.currentTimeMillis() - startedAt),
                        ),
                    )

                    onSuccess(VinScannerCapturedResult(vin.value, filePath))
                }
            }

            is VinScanResult.Cancelled -> {
                logCallbacks.info("${eventPrefix}_scan_cancelled", emptyMap())
                onCancelled()
            }

            is VinScanResult.Error -> {
                val errorMessage = result.message.ifBlank { "VIN scanning error" }
                logCallbacks.error("${eventPrefix}_scan_error", errorMessage, null, emptyMap())
                onError(errorMessage)
            }
        }
    }

    return remember(vinScannerLauncher) {
        CoreVinScanLauncher {
            vinScannerLauncher.launch(Unit)
        }
    }
}

@Composable
fun VinScannerAutoLaunchEffect(
    isActive: Boolean,
    eventPrefix: String,
    imageSubDir: String? = null,
    logCallbacks: VinScannerLogCallbacks = VinScannerLogCallbacks(),
    onSuccess: (VinScannerCapturedResult) -> Unit,
    onCancelled: () -> Unit,
    onError: (String) -> Unit,
) {
    val launcher = rememberVinScannerResultLauncher(
        eventPrefix = eventPrefix,
        imageSubDir = imageSubDir,
        logCallbacks = logCallbacks,
        onSuccess = onSuccess,
        onCancelled = onCancelled,
        onError = onError,
    )

    LaunchedEffect(isActive, launcher) {
        if (isActive) {
            launcher.launch()
        }
    }
}

suspend fun decodeVinBitmapFromUri(
    context: Context,
    uri: Uri?,
    onError: (e: Exception) -> Unit = {},
): Bitmap? = withContext(Dispatchers.IO) {
    uri ?: return@withContext null
    try {
        context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        null
    }
}

@OptIn(ExperimentalUuidApi::class)
suspend fun saveVinImageToFile(
    context: Context,
    bitmap: Bitmap?,
    subDir: String? = null,
    logCallbacks: VinScannerLogCallbacks = VinScannerLogCallbacks(),
): String = withContext(Dispatchers.Default) {
    if (bitmap == null || bitmap.isRecycled) return@withContext ""

    try {
        val safeFileName = "vin-scan-${Uuid.random()}.png"
        val dir = subDir?.let { File(context.filesDir, it).apply { mkdirs() } } ?: context.filesDir
        val outputFile = File(dir, safeFileName)
        val savedWidth = bitmap.width
        val savedHeight = bitmap.height

        val saved = FileOutputStream(outputFile).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, VIN_IMAGE_QUALITY, outputStream)
        }

        if (!saved) {
            logCallbacks.error(
                "save_bitmap_failed",
                "Bitmap.compress returned false",
                null,
                mapOf(
                    "fileName" to safeFileName,
                    "width" to savedWidth,
                    "height" to savedHeight,
                ),
            )
            return@withContext ""
        }

        logCallbacks.info(
            "save_bitmap_completed",
            mapOf(
                "outputPath" to outputFile.absolutePath,
                "outputBytes" to outputFile.length(),
                "width" to savedWidth,
                "height" to savedHeight,
                "quality" to VIN_IMAGE_QUALITY,
            ),
        )
        outputFile.absolutePath
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logCallbacks.exception("Failed to save VIN image", e)
        logCallbacks.error("save_bitmap_exception", e.message.orEmpty(), e, emptyMap())
        ""
    }
}

fun Bitmap?.safeRecycle(
    logCallbacks: VinScannerLogCallbacks = VinScannerLogCallbacks(),
) {
    try {
        this ?: return
        if (!isRecycled) {
            recycle()
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logCallbacks.exception("Failed to safe recycle bitmap, because, ${e.message}", e)
    }
}
