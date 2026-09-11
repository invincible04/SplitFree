package com.splitfree.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR code generation using ZXing: encoding only, no camera or scanning.
 *
 * Encoding a 512×512 module matrix takes tens of milliseconds; callers should run [encode] off the
 * main thread (e.g. `withContext(Dispatchers.Default)`), not inside composition.
 */
object QrGenerator {
    fun encode(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}
