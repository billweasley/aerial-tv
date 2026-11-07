package app.orchard.tv.system

import org.json.JSONObject
import java.net.URLEncoder

/** Read-only adapter for the configuration API shipped with mytv-android 3.0.0.235. */
internal object MytvContent {
    const val PACKAGE = "com.github.mytv.android"
    private const val BASE = "http://127.0.0.1:10591"

    fun state(): JSONObject? = runCatching { JSONObject(HomeContent.fetch("$BASE/api/configs")) }.getOrNull()

    /** This installed version serializes IPTV_CHANNEL_LAST_PLAY directly in GET /api/configs. */
    internal fun currentChannel(configs: JSONObject?): HomeContent.Item? {
        val channel = configs?.optJSONObject("iptvChannelLastPlay") ?: return null
        val name = channel.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return HomeContent.Item("mytv:current", name, channel.optString("standardName").takeUnless { it == "null" }.orEmpty(),
            kind = HomeContent.Kind.Continue)
    }

    internal fun resolveChannel(channels: List<HomeContent.Item>, configs: JSONObject?,
        history: () -> HomeContent.Item?, openedAt: () -> Long = { 0L }): List<HomeContent.Item> {
        val current = currentChannel(configs)
        return if (current != null) followRecent(channels, current)
        else followRecent(channels, history(), openedAt())
    }

    fun load(configs: JSONObject? = state()): List<HomeContent.Item> = runCatching {
        if (configs == null) return emptyList()
        val source = selectedSource(configs) ?: return emptyList()
        val url = source.optString("url")
        when {
            source.optInt("sourceType", 0) == 1 && url.isNotBlank() ->
                HomeContent.parse(HomeContent.fetch("$BASE/api/file/content?path=" + URLEncoder.encode(url, "UTF-8")))
            source.optString("protocol") in listOf("", "null", "http", "https") && HomeContent.httpUrl(url) ->
                HomeContent.parse(HomeContent.fetch(url))
            // SMB credentials belong to mytv. An authorized computer can import the resolved M3U;
            // no guessed cache paths, root access, or background launch of the other app.
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** Watch Next is published by the player process even when its configuration process is frozen. */
    internal fun followRecent(channels: List<HomeContent.Item>, recent: HomeContent.Item?, openedAt: Long = 0L): List<HomeContent.Item> {
        if (recent == null || recent.engagedAt < openedAt) return emptyList()
        fun name(text: String) = text.replace(Regex("[\\s·_-]"), "").replace(Regex("(?i)(高清|超清|标清|HD|FHD|UHD)$"), "")
        val title = name(recent.title)
        val subtitle = name(recent.subtitle)
        val decoded = runCatching { android.net.Uri.decode(recent.intent.orEmpty()) }.getOrDefault("")
        val matching = channels.filter { item ->
            val label = name(item.title)
            label.isNotEmpty() && (label == title || label == subtitle ||
                item.stream?.let { stream -> decoded.contains(stream) } == true)
        }
        return matching.singleOrNull()?.let(::listOf) ?: emptyList()
    }

    internal fun selectedSource(configs: JSONObject): JSONObject? {
        configs.optJSONObject("iptvSourceCurrent")?.let { return it }
        val list = configs.optJSONObject("iptvSourceList")?.optJSONArray("value") ?: return null
        val index = configs.optInt("iptvSourceCurrentIdx", -1)
        if (index !in 0 until list.length()) return null
        return list.optJSONObject(index)
    }
}
