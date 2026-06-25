package com.kazimi.syaravin.util

import android.content.Context
import android.widget.Toast

/**
 * Extension function to show toast messages
 */
internal fun Context.showToast(
    message: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    Toast.makeText(this, message, duration).show()
}
