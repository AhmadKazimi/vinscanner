package com.syarah.vinscanner.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.syarah.vinscanner.R
import kotlinx.coroutines.CancellationException

/**
 * Plays a short success chime plus a slight vibration when a VIN is auto-detected.
 * Create once per scanner screen and [release] on dispose.
 */
internal class ScanFeedback(context: Context) {
    private val appContext = context.applicationContext

    private val soundPool: SoundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()

    @Volatile
    private var soundId: Int = 0

    @Volatile
    private var loaded: Boolean = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }


    init {
        soundPool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        soundId = soundPool.load(appContext, R.raw.scan_success, 1)
    }

    /** Play the success chime and a slight vibration. Safe to call from any thread. */
    fun success() {
        if (loaded && soundId != 0) {
            soundPool.play(soundId, 0.05f, 0.05f, 1, 0, 1f)
        }
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            vib.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Vibration is best-effort.
        }
    }

    fun release() {
        soundPool.release()
    }
}
