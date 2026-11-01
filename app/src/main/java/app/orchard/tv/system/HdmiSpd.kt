package app.orchard.tv.system

/** Source Product Description, never EDID (which describes the receiving display). */
data class HdmiSpd(val vendor: String, val product: String, val sourceInfo: Int) {
    val name: String get() = product.ifBlank { vendor }

    companion object {
        /** HDMI 1.x SPD: type 0x83, version 1, 25 payload bytes, checksum over all 29 bytes. */
        fun frame(bytes: ByteArray): HdmiSpd? {
            if (bytes.size != 29 || bytes[0].toInt() and 255 != 0x83 ||
                bytes[1].toInt() != 1 || bytes[2].toInt() != 25) return null
            if (bytes.sumOf { it.toInt() and 255 } and 255 != 0) return null
            return payload(bytes.copyOfRange(4, 29))
        }

        /** Only for an explicitly configured driver that returns payload without the header. */
        fun payload(bytes: ByteArray): HdmiSpd? {
            if (bytes.size != 25) return null
            fun text(start: Int, end: Int): String? {
                val field = bytes.copyOfRange(start, end)
                if (field.any { it.toInt() != 0 && (it.toInt() and 255) !in 32..126 }) return null
                val zero = field.indexOf(0)
                if (zero >= 0 && field.drop(zero).any { it.toInt() != 0 && it.toInt() != 32 }) return null
                return field.take(if (zero < 0) field.size else zero).toByteArray().toString(Charsets.US_ASCII).trim()
            }
            val vendor = text(0, 8) ?: return null
            val product = text(8, 24) ?: return null
            if (vendor.isBlank() && product.isBlank()) return null
            return HdmiSpd(vendor, product, bytes[24].toInt() and 255)
        }

        /** A driver can expose binary bytes or a strict whitespace-separated hexadecimal frame. */
        fun decode(bytes: ByteArray, payloadOnly: Boolean = false): HdmiSpd? {
            val parse = if (payloadOnly) ::payload else ::frame
            parse(bytes)?.let { return it }
            if (bytes.size > 2048) return null
            val words = bytes.toString(Charsets.US_ASCII).trim().split(Regex("\\s+"))
            if (words.size != if (payloadOnly) 25 else 29) return null
            val hex = Regex("(?:0[xX])?[0-9a-fA-F]{2}")
            if (words.any { !hex.matches(it) }) return null
            return parse(words.map { it.removePrefix("0x").removePrefix("0X").toInt(16).toByte() }.toByteArray())
        }

        /**
         * MediaTek's published RX pkt_all attribute: Dump SPD / hb / pb, not a guessed API.
         * TCLOpenSource/mt9653, merak/mtk-srccap-hdmirx/mtk_srccap_hdmirx_sysfs.c:_dump_pkt.
         * The hardware PB array can include padding beyond the checksum and 25-byte payload.
         */
        fun mediatek(bytes: ByteArray): HdmiSpd? {
            if (bytes.size > 16384) return null
            val text = bytes.toString(Charsets.US_ASCII)
            val section = Regex("(?ms)^Dump\\s+SPD\\s*,\\s*Count\\s*=\\s*(\\d+)\\s*\\n(.*?)(?=^Dump\\s|\\z)")
                .findAll(text).toList().singleOrNull() ?: return null
            if (section.groupValues[1] != "1") return null
            val packet = Regex("(?ms)^hb[ \\t]+([0-9a-fA-F]{2})[ \\t]+([0-9a-fA-F]{2})[ \\t]+([0-9a-fA-F]{2})[ \\t]*\\n^pb\\s*\\n(.*)\\z")
                .find(section.groupValues[2]) ?: return null
            val body = packet.groupValues[4].trim().split(Regex("\\s+"))
            if (body.size !in 26..32 || body.any { !Regex("[0-9a-fA-F]{2}").matches(it) }) return null
            val words = (1..3).map { packet.groupValues[it] } + body.take(26)
            return frame(words.map { it.toInt(16).toByte() }.toByteArray())
        }
    }
}
