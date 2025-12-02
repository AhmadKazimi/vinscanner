package com.syarah.vinscanner

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.syarah.vinscanner.di.appModule
import com.syarah.vinscanner.di.cameraModule
import com.syarah.vinscanner.di.mlModule
import com.syarah.vinscanner.di.repositoryModule
import com.syarah.vinscanner.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.error.KoinApplicationAlreadyStartedException

/**
 * Internal initializer for VIN scanner library.
 * This ContentProvider automatically initializes Koin DI when the app starts.
 * No manual initialization required by consuming apps.
 */
internal class VinScannerInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false

        return try {
            Log.d(TAG, "Initializing VIN Scanner library...")

            startKoin {
                androidContext(context.applicationContext)
                modules(
                    appModule,
                    cameraModule,
                    mlModule,
                    repositoryModule,
                    viewModelModule
                )
            }

            Log.d(TAG, "VIN Scanner library initialized successfully")
            true
        } catch (e: KoinApplicationAlreadyStartedException) {
            // Koin already started (e.g., in tests or multi-process scenarios)
            Log.d(TAG, "Koin already initialized, skipping")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VIN Scanner library", e)
            false
        }
    }

    // No-op implementations (required by ContentProvider)
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "VinScannerInit"
    }
}
