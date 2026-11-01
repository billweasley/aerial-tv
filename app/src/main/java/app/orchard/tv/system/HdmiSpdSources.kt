package app.orchard.tv.system

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only RX driver endpoints, mapped to exact TvInputInfo ids by an owner-authorized adapter.
 * No guessed vendor methods, port numbering, device nodes, shell calls, or HDMI input switching.
 * No endpoint on the owner's MFTR0 has been verified yet; an empty configuration does no I/O.
 */
object HdmiSpdSources {
    private const val PREFS = "detected_spd"
    private const val KEY = "rx_sources"
    var revision by mutableIntStateOf(0); private set
    @Volatile var status: Map<String, String> = emptyMap(); private set
    private val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "AerialSpd").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    data class Endpoint(val path: String, val format: String)

    /** Configuration is protected by ConfigReceiver's WRITE_SECURE_SETTINGS sender permission. */
    fun configure(context: Context, text: String): String {
        if (text.length > 8192) return "SPD configuration too large; kept existing configuration"
        return runCatching {
            val parsed = parseConfig(text)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
            status = emptyMap()
            revision++
            "SPD RX sources configured: ${parsed.size} (driver accessibility not yet verified)"
        }.getOrElse { "Invalid SPD configuration; kept existing configuration: ${it.message}" }
    }

    internal fun parseConfig(text: String): Map<String, Endpoint> {
        val json = JSONObject(text)
        require(json.length() <= 8) { "At most 8 RX sources" }
        return json.keys().asSequence().associateWith { id ->
            require(id.isNotBlank() && id.length <= 256) { "Invalid input id" }
            val entry = json.getJSONObject(id)
            val path = entry.getString("path")
            require(validPath(path)) { "Expected an absolute RX attribute under /sys or /proc" }
            val format = entry.optString("format", "frame")
            require(format in setOf("frame", "payload", "mediatek")) { "Expected frame, payload or mediatek" }
            Endpoint(path, format)
        }
    }

    private fun validPath(path: String): Boolean = path.length <= 512 &&
        (path.startsWith("/sys/") || path.startsWith("/proc/")) &&
        path.split('/').none { it == ".." || it == "." } && path.none { it.isISOControl() }

    private fun configured(context: Context): Map<String, Endpoint> = runCatching {
        parseConfig(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
    }.getOrDefault(emptyMap())

    fun enabled(context: Context): Boolean = configured(context).isNotEmpty()

    /** Each refresh reads new bytes. A failed read replaces the old identity; none are persisted. */
    fun enrich(context: Context, sources: List<TvSource>): List<TvSource> {
        val endpoints = configured(context)
        if (endpoints.isEmpty()) return sources
        return enrichConfigured(sources, endpoints, ::read)
    }

    internal fun enrichConfigured(sources: List<TvSource>, endpoints: Map<String, Endpoint>,
        readNow: (String, Endpoint) -> HdmiSpd?): List<TvSource> {
        return sources.map { source ->
            val endpoint = endpoints[source.id]?.takeIf { source.kind == SourceKind.Hdmi } ?: return@map source
            val result = if (source.connected) readNow(source.id, endpoint) else {
                status = status + (source.id to "disconnected")
                null
            }
            // An imported CEC name or an owner's old device selection is not a live SPD reading.
            source.copy(deviceName = result?.name, spd = result, dynamicSpd = true)
        }
    }

    private fun read(id: String, endpoint: Endpoint): HdmiSpd? {
        if (!busy.compareAndSet(false, true)) {
            status = status + (id to "reader busy; no current identity")
            return null
        }
        val task = FutureTask {
            try {
                val file = File(endpoint.path)
                require(validPath(file.canonicalPath)) { "RX path leaves /sys or /proc" }
                val bytes = file.inputStream().use { input ->
                    val data = ByteArray(16385)
                    var count = 0
                    while (count < data.size) {
                        val n = input.read(data, count, data.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    require(count <= 16384) { "RX attribute too large" }
                    data.copyOf(count)
                }
                when (endpoint.format) {
                    "mediatek" -> HdmiSpd.mediatek(bytes)
                    "payload" -> HdmiSpd.decode(bytes, payloadOnly = true)
                    else -> HdmiSpd.decode(bytes)
                }
            } finally { busy.set(false) }
        }
        reader.execute(task)
        return try {
            task.get(150, TimeUnit.MILLISECONDS).also {
                status = status + (id to if (it == null) "no valid SPD" else "live SPD")
            }
        } catch (e: Exception) {
            // A blocked kernel read can outlive the timeout. Do not queue further reads behind it.
            // The single worker owns at most one descriptor; its finally releases the busy flag.
            status = status + (id to if (e is java.util.concurrent.TimeoutException) "RX read timed out" else "RX unavailable or permission denied")
            null
        }
    }
}
