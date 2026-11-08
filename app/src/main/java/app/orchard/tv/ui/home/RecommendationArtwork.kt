package app.orchard.tv.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.orchard.tv.wallpaper.Images
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

internal data class RecommendationArtwork(val picture: ImageBitmap, val glass: ImageBitmap)

/** Portrait posters and album covers remain whole, on the right of the title area. */
internal fun recommendationArtBounds(width: Int, height: Int, screenW: Int, screenH: Int): RectF {
    require(width > 0 && height > 0 && screenW > 0 && screenH > 0)
    if (width.toFloat() / height >= 1.45f) {
        val scale = maxOf(screenW.toFloat() / width, screenH.toFloat() / height)
        val w = width * scale; val h = height * scale
        return RectF((screenW - w) / 2, (screenH - h) / 2, (screenW + w) / 2, (screenH + h) / 2)
    }
    val scale = min(screenW * .43f / width, screenH * .76f / height)
    val w = width * scale; val h = height * scale
    val right = screenW * .95f
    return RectF(right - w, (screenH - h) / 2, right, (screenH + h) / 2)
}

/** A single baked picture per recommendation: no continuously rendered blur or video. */
internal fun loadRecommendationArtwork(context: Context, uri: String): RecommendationArtwork? = runCatching {
    val bytes = when {
        uri.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readArtwork() }
        uri.startsWith("http://") || uri.startsWith("https://") -> {
            val connection = URL(uri).openConnection() as HttpURLConnection
            connection.connectTimeout = 1500; connection.readTimeout = 1500
            try { connection.inputStream.use { it.readArtwork() } } finally { connection.disconnect() }
        }
        else -> null
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    try {
        val w = 1280; val h = 720
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(Color.rgb(14, 18, 25))
        canvas.drawBitmap(source, null, recommendationArtBounds(source.width, source.height, w, h), paint)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xE6080B12.toInt(), 0xA6080B12.toInt(), 0x00080B12), floatArrayOf(0f, .34f, .76f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x18000000, 0x08000000, 0xD9080B12.toInt()), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        RecommendationArtwork(picture.asImageBitmap(), Images.frost(picture).asImageBitmap())
    } finally { source.recycle() }
}.getOrNull()

private fun java.io.InputStream.readArtwork(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
    while (true) {
        if (Thread.currentThread().isInterrupted || android.os.SystemClock.elapsedRealtime() > deadline) throw java.io.IOException("Artwork timeout")
        val n = read(buffer)
        if (n < 0) break
        require(out.size() + n <= 3 * 1024 * 1024)
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
