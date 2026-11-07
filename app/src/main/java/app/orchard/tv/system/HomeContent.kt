package app.orchard.tv.system

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real provider rows or an owner-configured feed. No guessed private service APIs or account data. */
object HomeContent {
    enum class Kind { Recommendation, Continue, Live }
    data class Item(val id: String, val title: String, val subtitle: String = "", val image: String? = null,
        val intent: String? = null, val stream: String? = null, val kind: Kind = Kind.Recommendation,
        val engagedAt: Long = 0L)
    data class Profile(val feed: String = "", val snapshot: String = "")
    private const val PREFS = "home_content"
    const val MAX_ITEMS = 12
    const val MAX_CHANNELS = 512
    const val HOME_RECOMMENDATIONS = 3
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val providerReader = Executors.newSingleThreadExecutor { job -> Thread(job, "AerialContent").apply { isDaemon = true } }
    private val providerBusy = AtomicBoolean(false)
    var revision by mutableIntStateOf(0); private set
    @Volatile var status: Map<String, String> = emptyMap(); private set
    @Volatile private var liveRecord: Item? = null
    @Volatile private var liveRecordSeenAt = 0L
    @Volatile private var previewPreparingAt = 0L
    @Volatile private var previewFrameMs = -1L
    @Volatile private var previewStream: String? = null

    internal fun previewPreparing(stream: String) {
        previewStream = stream
        previewPreparingAt = android.os.SystemClock.elapsedRealtime()
        previewFrameMs = -1L
    }

    internal fun previewRendered(stream: String) {
        if (previewStream == stream) previewFrameMs = android.os.SystemClock.elapsedRealtime() - previewPreparingAt
    }

    fun liveTiming(): String = "record_age_ms=" +
        (if (liveRecordSeenAt == 0L) -1L else android.os.SystemClock.elapsedRealtime() - liveRecordSeenAt) +
        " first_frame_ms=$previewFrameMs"

    /** A returning launcher must not play a Watch Next record left over from before this visit. */
    fun appOpened(context: Context, pkg: String) {
        if (pkg == MytvContent.PACKAGE) context.getSharedPreferences("home_content_recent", 0).edit()
            .putLong(pkg, System.currentTimeMillis()).remove("$pkg:return_started").apply()
    }

    internal fun recentGate(openedAt: Long, returnStarted: Long, now: Long): Long =
        if (returnStarted > 0 && now >= returnStarted && now - returnStarted < 4_000L) openedAt else 0L

    private fun recentGate(context: Context, pkg: String): Long {
        val prefs = context.getSharedPreferences("home_content_recent", 0)
        val opened = prefs.getLong(pkg, 0L)
        if (opened == 0L) return 0L
        val now = System.currentTimeMillis()
        val start = prefs.getLong("$pkg:return_started", 0L).takeIf { it > 0L } ?: now.also {
            prefs.edit().putLong("$pkg:return_started", it).apply()
        }
        return recentGate(opened, start, now)
    }

    /** Observe actual publisher changes; short retries also cover delayed writes on return. */
    internal fun refreshes(context: Context, pkg: String) = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val registered = Build.VERSION.SDK_INT >= 26 && runCatching {
            resolver.registerContentObserver(Uri.parse("content://android.media.tv"), true, observer)
            true
        }.getOrDefault(false)
        val timer = launch {
            send(Unit)
            if (pkg == MytvContent.PACKAGE) repeat(8) { delay(1_000); send(Unit) }
            while (true) { delay(if (pkg == MytvContent.PACKAGE) 2_000L else 60_000L); send(Unit) }
        }
        awaitClose { timer.cancel(); if (registered) resolver.unregisterContentObserver(observer) }
    }.conflate()

    internal fun displayItems(items: List<Item>, recommendations: Boolean, liveBackground: Boolean): List<Item> {
        if (liveBackground) items.firstOrNull { it.kind == Kind.Live }?.let { return listOf(it) }
        return if (recommendations) items.filter { it.kind != Kind.Live }.take(HOME_RECOMMENDATIONS) else emptyList()
    }

    fun profile(context: Context, pkg: String): Profile = runCatching {
        val obj = JSONObject(context.getSharedPreferences(PREFS, 0).getString(pkg, "{}") ?: "{}")
        Profile(obj.optString("feed"), obj.optString("snapshot"))
    }.getOrDefault(Profile())

    /** Only the authenticated local phone page or the protected ConfigReceiver calls this. */
    fun configure(context: Context, pkg: String, feed: String, snapshot: String): Boolean {
        if (!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) || feed.length > 2048 || snapshot.length > MAX_BYTES) return false
        if (feed.isNotBlank() && !httpUrl(feed)) return false
        if (snapshot.isNotBlank() && parse(snapshot).isEmpty()) return false
        val obj = JSONObject().put("feed", feed.trim()).put("snapshot", snapshot)
        context.getSharedPreferences(PREFS, 0).edit().putString(pkg, obj.toString()).apply()
        revision++
        return true
    }

    suspend fun load(context: Context, pkg: String): List<Item> {
        val loaded = withTimeoutOrNull(4_000) {
            runInterruptible(Dispatchers.IO) {
                val config = profile(context, pkg)
                val mytvState = if (pkg == MytvContent.PACKAGE) MytvContent.state() else null
                val items = if (config.feed.isNotBlank()) parse(fetch(config.feed))
                else if (config.snapshot.isNotBlank()) parse(config.snapshot)
                else if (pkg == MytvContent.PACKAGE) MytvContent.load(mytvState)
                else boundedProvider(context.applicationContext, pkg)
                val usable = items.filter { it.kind == Kind.Live || contentIntent(context, pkg, it.intent) != null }
                if (pkg == MytvContent.PACKAGE && usable.any { it.kind == Kind.Live }) {
                    // A valid direct channel outranks delayed Watch Next. An unmatched direct
                    // channel must fall back to wallpaper, never a different historical channel.
                    MytvContent.resolveChannel(usable, mytvState,
                        { recentItem(context, pkg) }, { recentGate(context, pkg) })
                }
                else usable
            }
        }.orEmpty()
        status = status + (pkg to if (loaded.isEmpty()) "no current content; using wallpaper" else "content available: ${loaded.size}")
        return loaded
    }

    internal fun recentItem(context: Context, pkg: String): Item? =
        boundedProvider(context.applicationContext, pkg, recentOnly = true).firstOrNull().also { item ->
            if (pkg == MytvContent.PACKAGE && item != null && item != liveRecord) {
                liveRecord = item
                liveRecordSeenAt = android.os.SystemClock.elapsedRealtime()
                DiagLog.i("live preview: changed Watch Next record observed")
            }
        }

    private fun boundedProvider(context: Context, pkg: String, recentOnly: Boolean = false): List<Item> {
        if (!providerBusy.compareAndSet(false, true)) return emptyList()
        val cancel = CancellationSignal()
        val task = FutureTask {
            try { provider(context, pkg, cancel, recentOnly) } finally { providerBusy.set(false) }
        }
        providerReader.execute(task)
        return try { task.get(2, TimeUnit.SECONDS) }
        catch (_: java.util.concurrent.TimeoutException) { cancel.cancel(); emptyList() }
        catch (e: InterruptedException) { cancel.cancel(); throw e }
        catch (_: Exception) { emptyList() }
    }

    /** Android TV preview channels and Watch Next remain distinct, rather than relabeling history. */
    private fun provider(context: Context, pkg: String, cancel: CancellationSignal, recentOnly: Boolean): List<Item> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val result = ArrayList<Item>()
        val tables = if (recentOnly) listOf("watch_next_program" to Kind.Continue)
            else listOf("preview_program" to Kind.Recommendation, "watch_next_program" to Kind.Continue)
        for ((table, kind) in tables) {
            runCatching {
                val fields = arrayOf("_id", "package_name", "title", "short_description", "poster_art_uri", "intent_uri") +
                    if (table == "watch_next_program") arrayOf("last_engagement_time_utc_millis") else emptyArray()
                context.contentResolver.query(Uri.parse("content://android.media.tv/$table"), fields,
                    null, null, if (table == "watch_next_program") "last_engagement_time_utc_millis DESC" else "_id DESC", cancel)?.use { c ->
                    fun field(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
                    var scanned = 0
                    while (result.size < MAX_ITEMS && scanned++ < 4096 && c.moveToNext()) {
                        if (Thread.currentThread().isInterrupted) { cancel.cancel(); break }
                        if (field("package_name") != pkg) continue
                        val title = field("title")?.takeIf { it.isNotBlank() } ?: continue
                        val action = field("intent_uri")?.takeIf { it.isNotBlank() } ?: continue
                        if (contentIntent(context, pkg, action) == null) continue
                        result += Item("$table:${field("_id")}", title.take(180), field("short_description").orEmpty().take(360),
                            field("poster_art_uri"), action, kind = kind,
                            engagedAt = field("last_engagement_time_utc_millis")?.toLongOrNull() ?: 0L)
                    }
                }
            }
        }
        return result.distinctBy { it.intent }.take(MAX_ITEMS)
    }

    /** A deep link may open only the selected app, never an unrelated package or an exported command. */
    fun contentIntent(context: Context, pkg: String, uri: String?): Intent? = runCatching {
        if (uri.isNullOrBlank()) return null
        val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        val scheme = intent.data?.scheme?.lowercase()
        if (scheme in setOf("javascript", "file", "data")) return null
        if (intent.component?.packageName?.let { it != pkg } == true || intent.`package`?.let { it != pkg } == true) return null
        intent.selector = null
        intent.clipData = null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.setPackage(pkg)
        val resolved = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        if (!resolved.exported || !resolved.enabled || resolved.packageName != pkg) return null
        intent
    }.getOrNull()

    internal fun httpUrl(text: String): Boolean = runCatching {
        val u = URL(text)
        u.protocol in setOf("http", "https") && u.host.isNotBlank()
    }.getOrDefault(false)

    internal fun fetch(url: String): String {
        require(httpUrl(url))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        c.instanceFollowRedirects = false
        try {
            require(c.responseCode in 200..299)
            val bytes = c.inputStream.use { it.readBytesBounded(MAX_BYTES) }
            return bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val n = read(buf)
            if (n < 0) break
            require(out.size() + n <= limit)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** JSON recommendation feeds or an imported M3U/TXT channel list; nothing fabricated on failure. */
    internal fun parse(text: String): List<Item> = runCatching {
        require(text.length <= MAX_BYTES)
        val clean = text.trim().removePrefix("\uFEFF")
        if (clean.startsWith("[") || clean.startsWith("{")) {
            val rows = if (clean.startsWith("[")) JSONArray(clean) else JSONObject(clean).getJSONArray("items")
            (0 until rows.length()).asSequence().mapNotNull { i ->
                val o = rows.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").take(180).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val live = o.optBoolean("live", false)
                val stream = o.optString("stream").takeIf(::httpUrl)
                val action = o.optString("intent").takeIf { it.isNotBlank() }
                if (if (live) stream == null else action == null) return@mapNotNull null
                Item(o.optString("id", "item:$i"), title, o.optString("subtitle").take(360),
                    o.optString("image").takeIf { it.isNotBlank() }, action, stream,
                    if (live) Kind.Live else Kind.Recommendation)
            }.distinctBy { listOf(it.title, it.intent, it.stream) }.take(MAX_ITEMS).toList()
        } else {
            val result = ArrayList<Item>()
            var name = ""
            var logo: String? = null
            clean.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF:", true) -> {
                        name = line.substringAfterLast(',').trim()
                        logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    }
                    line.isBlank() || line.startsWith('#') -> Unit
                    else -> {
                        val url = if (httpUrl(line)) line else line.substringAfter(',', "")
                        val label = if (httpUrl(line)) name else line.substringBefore(',')
                        if (httpUrl(url) && label.isNotBlank() && result.size < MAX_CHANNELS)
                            result += Item("live:${url.hashCode()}", label.take(180), image = logo, stream = url, kind = Kind.Live)
                        name = ""; logo = null
                    }
                }
            }
            result.distinctBy { it.stream }
        }
    }.getOrDefault(emptyList())
}
