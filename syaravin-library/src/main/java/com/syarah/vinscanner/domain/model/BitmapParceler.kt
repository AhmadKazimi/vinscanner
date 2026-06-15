package com.syarah.vinscanner.domain.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Parcel
import kotlinx.parcelize.Parceler
import java.io.ByteArrayOutputStream

internal object BitmapParceler : Parceler<Bitmap?> {
    override fun create(parcel: Parcel): Bitmap? {
        val size = parcel.readInt()
        if (size <= 0) return null
        val bytes = ByteArray(size)
        parcel.readByteArray(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun Bitmap?.write(parcel: Parcel, flags: Int) {
        if (this == null) {
            parcel.writeInt(0)
            return
        }
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 90, out)
        val bytes = out.toByteArray()
        parcel.writeInt(bytes.size)
        parcel.writeByteArray(bytes)
    }
}
