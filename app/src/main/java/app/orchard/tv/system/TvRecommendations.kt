package app.orchard.tv.system

import android.content.Context
import android.net.Uri
import android.os.Build

/** Inspect only recommendations apps publish to Android TV, without reading their private data. */
object TvRecommendations {
    fun inspect(context: Context, packageName: String): String {
        if (Build.VERSION.SDK_INT < 26) return "Recommendations require Android 8 or newer"
        val results = listOf("preview_program", "watch_next_program").map { table ->
            runCatching {
                context.contentResolver.query(
                    Uri.parse("content://android.media.tv/$table"), arrayOf("_id", "package_name", "title"),
                    null, null, null,
                )?.use { cursor ->
                    val owner = cursor.getColumnIndex("package_name")
                    var count = 0
                    while (cursor.moveToNext()) if (owner >= 0 && cursor.getString(owner) == packageName) count++
                    "$table=$count"
                } ?: "$table=unavailable"
            }.getOrElse { "$table=${it.javaClass.simpleName}: ${it.message?.take(140)}" }
        }
        return results.joinToString("; ")
    }
}
