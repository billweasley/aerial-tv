package app.orchard.tv.system

import org.json.JSONObject
import java.net.URLEncoder

/** Read-only adapter for the configuration API shipped with mytv-android 3.0.0.235. */
internal object MytvContent {
    const val PACKAGE = "com.github.mytv.android"
    private const val BASE = "http://127.0.0.1:10591"

    fun load(): List<HomeContent.Item> = runCatching {
        val configs = JSONObject(HomeContent.fetch("$BASE/api/configs"))
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

    internal fun selectedSource(configs: JSONObject): JSONObject? {
        configs.optJSONObject("iptvSourceCurrent")?.let { return it }
        val list = configs.optJSONObject("iptvSourceList")?.optJSONArray("value") ?: return null
        val index = configs.optInt("iptvSourceCurrentIdx", -1)
        if (index !in 0 until list.length()) return null
        return list.optJSONObject(index)
    }
}
