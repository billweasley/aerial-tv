package app.orchard.tv.system

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HdmiSpdSourcesTest {
    private val id = "com.vendor.input/.Hdmi/HW7"
    private val source get() = TvSource(id, "HDMI 3", SourceKind.Hdmi, true, "Old PS5 snapshot", Intent())
    // A fixture endpoint, never opened. The test supplies exact source bytes through readNow.
    private val endpoints = mapOf(id to HdmiSpdSources.Endpoint("/sys/fixture/rx/spd", "frame"))

    @Test fun samePortChangesIdentityAndDoesNotRetainThePreviousConsole() {
        val responses = listOf(HdmiSpdTest.packet("SONY", "PS5"), HdmiSpdTest.packet("Nintendo", "Switch"), ByteArray(0))
        val actual = responses.map { bytes ->
            HdmiSpdSources.enrichConfigured(listOf(source), endpoints) { _, _ -> HdmiSpd.decode(bytes) }.single()
        }
        assertEquals(listOf("PS5", "Switch", null), actual.map { it.deviceName })
        assertTrue(actual.all { it.dynamicSpd })
        assertNull(actual.last().spd)
    }

    @Test fun disconnectAndUnmappedInputsNeverReadOrInheritSpd() {
        val disconnected = HdmiSpdSources.enrichConfigured(listOf(source.copy(connected = false)), endpoints) { _, _ ->
            fail("Disconnected port must not read its previous SPD"); null
        }.single()
        assertNull(disconnected.deviceName)
        val original = source
        val plain = HdmiSpdSources.enrichConfigured(listOf(original), emptyMap()) { _, _ ->
            fail("Unconfigured ports must not read guessed driver paths"); null
        }.single()
        assertSame(original, plain)
    }

    @Test fun acceptsOnlyExplicitReadOnlyPathsAndKnownFormats() {
        assertEquals("mediatek", HdmiSpdSources.parseConfig("""{"$id":{"path":"/sys/fixture/rx/pkt_all","format":"mediatek"}}""")[id]?.format)
        for (path in listOf("/dev/video0", "/data/private", "/sys/../dev/video0", "relative/path")) {
            assertTrue(runCatching { HdmiSpdSources.parseConfig("""{"$id":{"path":"$path"}}""") }.isFailure)
        }
        assertTrue(runCatching { HdmiSpdSources.parseConfig("""{"$id":{"path":"/sys/fixture/spd","format":"guess"}}""") }.isFailure)
    }
}
