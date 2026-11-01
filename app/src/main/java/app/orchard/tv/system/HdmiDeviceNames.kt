package app.orchard.tv.system

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Names observed by the owner's ADB computer where firmware hides CEC names from apps. */
object HdmiDeviceNames {
    private const val PREFS = "detected_hdmi"
    var revision by mutableIntStateOf(0); private set
    var status: String = "not read"; private set
    private val portPattern = Regex("port_id:\\s*(\\d+)")
    private val addressPattern = Regex("physical_address:\\s*0x([0-9a-fA-F]+)")
    private val namePattern = Regex("display_name:\\s*(.*?)\\s+(?:power_status|physical_address|port_id):")
    private val typePattern = Regex("device_type:\\s*(\\d+)")
    private val genericName = Regex("^(Playback|Recorder|Tuner|Audio ?System|Audio|TV|Reserved)[ _]?\\d*$", RegexOption.IGNORE_CASE)
    private val hdmiPort = Regex("HDMI\\s*(\\d+)", RegexOption.IGNORE_CASE)

    /** Only locally stored names; no ADB client, root, privileged audio access, or shell in the app. */
    fun read(context: Context): Map<Int, String> {
        val names = runCatching {
            val json = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ports", "{}") ?: "{}")
            json.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to json.getString(key) } }.toMap()
        }.getOrDefault(emptyMap())
        status = "${names.size} observed ports; refresh with tools/sync-hdmi-names.py after changing devices"
        return names
    }

    /** A protected ConfigReceiver import; keep names only, never the complete system dump. */
    fun importSnapshot(context: Context, encoded: String): String {
        if (encoded.length > 512_000) return "CEC snapshot too large; kept existing names"
        return runCatching {
            val dump = Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
            require(dump.lineSequence().any { it.trim() == "mDeviceInfos:" }) { "No current CEC table" }
            val names = parse(dump)
            val json = JSONObject().apply { names.forEach { (port, name) -> put(port.toString(), name) } }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ports", json.toString()).apply()
            revision++
            "Imported ${names.size} HDMI device names"
        }.getOrElse { "CEC snapshot unreadable; kept existing names" }
    }

    fun port(source: TvSource): Int? = if (source.kind != SourceKind.Hdmi) null else
        sequenceOf(source.firmwareLabel, source.label).filterNotNull()
            .mapNotNull { hdmiPort.find(it)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()

    /** Current device table only: the message history can contain names of unplugged devices. */
    internal fun parse(dump: String): Map<Int, String> {
        var tableIndent: Int? = null
        val devices = mutableListOf<Triple<Int, String, Int>>()
        for (line in dump.lineSequence()) {
            val text = line.trim()
            val indent = line.length - line.trimStart().length
            if (text == "mDeviceInfos:") { tableIndent = indent; continue }
            val table = tableIndent ?: continue
            if (text.isNotEmpty() && indent <= table) { tableIndent = null; continue }
            if (!text.startsWith("CEC:")) continue
            val physical = addressPattern.find(text)?.groupValues?.get(1)?.toIntOrNull(16)
            val port = portPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: physical?.let { (it shr 12) and 0xF } ?: continue
            if (port !in 1..15 || physical == 0) continue
            val name = namePattern.find(text)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && !genericName.matches(it) } ?: continue
            val type = typePattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
            devices += Triple(port, name.take(64), if (type == 4) 0 else 1)
        }
        return buildMap {
            devices.sortedBy { it.third }.forEach { (port, name, _) -> if (port !in this) put(port, name) }
        }
    }
}
