package app.orchard.tv.ui.home

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecommendationArtworkTest {
    @Test fun wideArtworkFillsTheBackgroundWithoutDistortion() {
        val bounds = recommendationArtBounds(1920, 1080, 1280, 720)
        assertEquals(0f, bounds.left, .01f)
        assertEquals(0f, bounds.top, .01f)
        assertEquals(1280f, bounds.right, .01f)
        assertEquals(720f, bounds.bottom, .01f)
    }

    @Test fun albumAndPortraitCoversStayWholeAndLeaveTitleSpace() {
        for ((width, height) in listOf(600 to 900, 500 to 500)) {
            val bounds = recommendationArtBounds(width, height, 1280, 720)
            assertEquals(width.toFloat() / height, bounds.width() / bounds.height(), .001f)
            assertTrue(bounds.left >= 1280f * .5f)
            assertTrue(bounds.right <= 1280f && bounds.top >= 0 && bounds.bottom <= 720f)
        }
    }

    @Test fun unsupportedArtLocationsPreserveNormalWallpaper() {
        assertNull(loadRecommendationArtwork(RuntimeEnvironment.getApplication(), "file:///private/art.jpg"))
        assertNull(loadRecommendationArtwork(RuntimeEnvironment.getApplication(), ""))
    }
}
