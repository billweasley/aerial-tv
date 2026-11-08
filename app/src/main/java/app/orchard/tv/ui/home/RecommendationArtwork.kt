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

/** Bake a static poster or a film-poster wall; the UI still draws only one background picture. */
internal fun loadRecommendationArtwork(context: Context, uri: String): RecommendationArtwork? =
    loadRecommendationWall(context, listOf(uri))

internal fun loadRecommendationWall(context: Context, uris: List<String>): RecommendationArtwork? = runCatching {
    val sources = ArrayList<Bitmap>()
    try {
        uris.distinct().take(3).forEach { uri ->
            runCatching { decodeRecommendationArtwork(context, uri) }.getOrNull()?.let(sources::add)
        }
        if (sources.isEmpty()) return null
        val w = 1280; val h = 720
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(Color.rgb(14, 18, 25))
        if (sources.size == 1) {
            val source = sources.first()
            canvas.drawBitmap(source, null, recommendationArtBounds(source.width, source.height, w, h), paint)
        } else {
            canvas.save()
            canvas.rotate(-6f, w / 2f, h / 2f)
            sources.forEachIndexed { index, source ->
                val cellW = w * .42f; val cellH = h * 1.15f
                val x = -w * .04f + index * w * .37f
                val y = if (index % 2 == 0) -h * .08f else h * .08f
                val scale = min(cellW / source.width, cellH / source.height)
                val width = source.width * scale; val height = source.height * scale
                val dst = RectF(x + (cellW - width) / 2, y + (cellH - height) / 2,
                    x + (cellW + width) / 2, y + (cellH + height) / 2)
                canvas.save()
                val clip = android.graphics.Path().apply { addRoundRect(dst, 18f, 18f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                canvas.drawBitmap(source, null, dst, paint)
                canvas.restore()
            }
            canvas.restore()
        }
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xB3080B12.toInt(), 0x66080B12, 0x26080B12), floatArrayOf(0f, .34f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x18000000, 0x18000000, 0xE6080B12.toInt()), floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        RecommendationArtwork(picture.asImageBitmap(), Images.frost(picture).asImageBitmap())
    } finally { sources.forEach(Bitmap::recycle) }
}.getOrNull()

private fun decodeRecommendationArtwork(context: Context, uri: String): Bitmap? {
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
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })
}

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
