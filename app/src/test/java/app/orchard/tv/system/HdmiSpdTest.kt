package app.orchard.tv.system

import org.junit.Assert.*
import org.junit.Test

class HdmiSpdTest {
    @Test fun readsStandardVendorAndProduct() {
        assertEquals(HdmiSpd("SONY", "PS5", 8), HdmiSpd.frame(packet("SONY", "PS5")))
        assertEquals("Nintendo Switch", HdmiSpd.frame(packet("Nintendo", "Nintendo Switch"))?.name)
    }

    @Test fun verifiesHeaderLengthAndChecksum() {
        val valid = packet("SONY", "PS5")
        assertNull(HdmiSpd.frame(valid.copyOf(28)))
        assertNull(HdmiSpd.frame(valid.copyOf(30)))
        for (offset in listOf(0, 1, 2, 3, 15)) {
            assertNull(HdmiSpd.frame(valid.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }))
        }
        assertNull(HdmiSpd.frame(ByteArray(29)))
    }

    @Test fun doesNotTreatEdidOrRandomTextAsSourceIdentity() {
        assertNull(HdmiSpd.decode(byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0) + ByteArray(120)))
        assertNull(HdmiSpd.decode("PlayStation 5 connected HDMI 3".toByteArray()))
        assertNull(HdmiSpd.payload(ByteArray(25)))
        assertNull(HdmiSpd.payload(packet("SONY", "PS5").copyOfRange(4, 29).also { it[10] = 1 }))
    }

    @Test fun payloadRequiresExplicitFormatAndHexIsStrict() {
        val raw = packet("Google", "Google TV")
        val hex = raw.joinToString(" ") { "%02X".format(it.toInt() and 255) }.toByteArray()
        assertEquals(HdmiSpd.frame(raw), HdmiSpd.decode(hex))
        assertNull(HdmiSpd.decode(raw.copyOfRange(4, 29)))
        assertEquals(HdmiSpd.frame(raw), HdmiSpd.decode(raw.copyOfRange(4, 29), payloadOnly = true))
        assertNull(HdmiSpd.decode(hex + " 00".toByteArray()))
    }

    @Test fun readsPublishedMediaTekRxDumpWithoutChoosingAnotherPacket() {
        val raw = packet("SONY", "PS5")
        val dump = "Dump AVI , Count = 0\n" + mtk(raw) + "Dump VS , Count = 0\n"
        assertEquals(HdmiSpd.frame(raw), HdmiSpd.mediatek(dump.toByteArray()))
        assertNull(HdmiSpd.mediatek(dump.replace("Dump SPD", "Dump MPEG").toByteArray()))
        assertNull(HdmiSpd.mediatek(dump.replace("SPD , Count = 1", "SPD , Count = 0").toByteArray()))
        assertNull(HdmiSpd.mediatek((mtk(raw) + mtk(packet("Nintendo", "Switch"))).toByteArray()))
        assertNull(HdmiSpd.mediatek(mtk(raw.also { it[4] = 0 }).toByteArray()))
    }

    companion object {
        fun packet(vendor: String, product: String): ByteArray {
            val bytes = ByteArray(29)
            bytes[0] = 0x83.toByte(); bytes[1] = 1; bytes[2] = 25
            vendor.toByteArray(Charsets.US_ASCII).copyInto(bytes, 4, endIndex = minOf(8, vendor.length))
            product.toByteArray(Charsets.US_ASCII).copyInto(bytes, 12, endIndex = minOf(16, product.length))
            bytes[28] = 8
            bytes[3] = (-bytes.sumOf { it.toInt() and 255 }).toByte()
            return bytes
        }

        private fun mtk(bytes: ByteArray) = "Dump SPD , Count = 1\nhb " +
            bytes.take(3).joinToString(" ") { "%02X".format(it.toInt() and 255) } + "\npb\n" +
            (bytes.drop(3) + listOf(0.toByte(), 0.toByte())).chunked(8).joinToString("\n") { row ->
                row.joinToString(" ") { "%02X".format(it.toInt() and 255) }
            } + "\n"
    }
}
