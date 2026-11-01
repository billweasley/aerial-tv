package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiDeviceNamesTest {
    @Test
    fun readsCurrentDevicesIncludingStandbyWithoutLocalTvOrMessageHistory() {
        val dump = """
            HDMI CEC Network
              mDeviceInfos:
                CEC: logical_address: 0x00 device_type: 0 display_name: Television power_status: 0 physical_address: 0x0000 port_id: 0
                CEC: logical_address: 0x04 device_type: 4 display_name: Google TV Stre power_status: 0 physical_address: 0x1000 port_id: 1
                CEC: logical_address: 0x08 device_type: 4 display_name: PlayStation 5 power_status: 1 physical_address: 0x3100 port_id: 3
            mCecController:
              CEC: logical_address: 0x04 device_type: 4 display_name: Old Device power_status: 0 physical_address: 0x2000 port_id: 2
        """.trimIndent()
        assertEquals(mapOf(1 to "Google TV Stre", 3 to "PlayStation 5"), HdmiDeviceNames.parse(dump))
    }

    @Test
    fun prefersPlaybackDeviceBehindReceiverOnSamePort() {
        val dump = """
              mDeviceInfos:
                CEC: logical_address: 0x05 device_type: 5 display_name: Receiver power_status: 0 physical_address: 0x3000 port_id: 3
                CEC: logical_address: 0x08 device_type: 4 display_name: Game Console power_status: 1 physical_address: 0x3100 port_id: 3
        """.trimIndent()
        assertEquals(mapOf(3 to "Game Console"), HdmiDeviceNames.parse(dump))
    }

    @Test
    fun derivesPortFromPhysicalAddressAndRejectsGenericNames() {
        val dump = """
              mDeviceInfos:
                CEC: logical_address: 0x04 device_type: 4 display_name: Set Top Box power_status: 0 physical_address: 0x2100
                CEC: logical_address: 0x08 device_type: 4 display_name: Playback_1 power_status: 0 physical_address: 0x3000 port_id: 3
        """.trimIndent()
        assertEquals(mapOf(2 to "Set Top Box"), HdmiDeviceNames.parse(dump))
    }

    @Test
    fun missingPermissionOrMissingServiceDoesNotInventADevice() {
        assertTrue(HdmiDeviceNames.parse("Permission Denial: cannot dump HdmiControlService").isEmpty())
        assertTrue(HdmiDeviceNames.parse("Can't find service: hdmi_control").isEmpty())
    }
}
