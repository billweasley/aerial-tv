package app.orchard.tv.guard

import org.junit.Assert.*
import org.junit.Test

class HomeKeyPolicyTest {
    @Test fun stockLauncherBypassesEvenAnEnabledOwnerCapture() {
        assertFalse(HomeKeyPolicy.wantsFilter(34, false, true, true))
        assertFalse(HomeKeyPolicy.wantsFilter(34, true, true, true))
    }

    @Test fun returningHomeRestoresCaptureWithoutChangingUpstreamDefaults() {
        assertTrue(HomeKeyPolicy.wantsFilter(34, false, true, false, aerialResumed = true))
        assertTrue(HomeKeyPolicy.wantsFilter(34, true, false, false))
        assertFalse(HomeKeyPolicy.wantsFilter(34, false, false, false))
        assertTrue(HomeKeyPolicy.wantsFilter(30, false, false, false))
    }

    @Test fun thirdPartyAppsNeverInheritTheOwnersCaptureOnModernAndroid() {
        assertFalse(HomeKeyPolicy.wantsFilter(34, false, true, false, aerialResumed = false))
        assertFalse(HomeKeyPolicy.wantsFilter(34, false, true, true, aerialResumed = false))
    }
}
