package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Matrix

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated !== this) recycle()
    return rotated
}
