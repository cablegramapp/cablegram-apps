package app.cablegram.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory

fun decodePosterBitmap(path: String?, maxEdge: Int = 360): Bitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        val sample = maxOf(1, maxOf(w, h) / maxEdge)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()
}

fun stillsJpegBase64(path: String, maxEdge: Int = 320, quality: Int = 50): String? {
    val bitmap = decodePosterBitmap(path, maxEdge) ?: return null
    val out = java.io.ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}
