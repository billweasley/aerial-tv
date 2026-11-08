package app.orchard.tv.system

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.net.URL

/** Public film/anime rankings, without cookies or account-personalized recommendation claims. */
internal object BilibiliContent {
    const val PACKAGE = "com.xiaodianshi.tv.yst"
    private const val FILMS = "https://api.bilibili.com/pgc/season/rank/web/list?day=3&season_type=2"
    private const val ANIME = "https://api.bilibili.com/pgc/web/rank/list?day=3&season_type=1"
    private var cached = emptyList<HomeContent.Item>()
    private var readAt = 0L

    @Synchronized fun load(context: Context): List<HomeContent.Item> {
        if (cached.isNotEmpty() && SystemClock.elapsedRealtime() - readAt < 60_000) return cached
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(PACKAGE)
            ?: context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return emptyList()
        val action = launch.toUri(Intent.URI_INTENT_SCHEME)
        val headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.bilibili.com/")
        cached = emptyList()
        for (endpoint in listOf(FILMS, ANIME)) {
            val result = runCatching { parse(HomeContent.fetch(endpoint, headers), action) }.getOrDefault(emptyList())
            if (result.isNotEmpty()) { cached = result; readAt = SystemClock.elapsedRealtime(); break }
        }
        return cached
    }

    internal fun parse(json: String, action: String): List<HomeContent.Item> = runCatching {
        val root = JSONObject(json)
        if (root.optInt("code", -1) != 0) return emptyList()
        val rows = (root.optJSONObject("data") ?: root.optJSONObject("result"))?.optJSONArray("list") ?: return emptyList()
        (0 until rows.length()).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = item.optString("cover").replaceFirst("http://", "https://")
            val host = runCatching { URL(cover).host }.getOrDefault("")
            if (!cover.startsWith("https://") || !host.endsWith(".hdslb.com")) return@mapNotNull null
            val id = item.optLong("season_id", -1)
            if (id <= 0) return@mapNotNull null
            HomeContent.Item("bilibili:season:$id", title.take(180), image = cover, intent = action,
                mediaType = 0, artworkAspect = 4, curatedBackground = true)
        }.distinctBy { it.id }.take(HomeContent.HOME_RECOMMENDATIONS)
    }.getOrDefault(emptyList())
}
