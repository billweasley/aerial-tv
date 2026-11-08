package app.orchard.tv.system

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BilibiliContentTest {
    @Test fun filmRankingsUseOfficialPosterArtAndKeepThePublisherOrdering() {
        val data = """{"code":0,"data":{"list":[
            {"season_id":1,"title":"First film","cover":"http://i0.hdslb.com/bfs/first.png"},
            {"season_id":2,"title":"Second film","cover":"https://i1.hdslb.com/bfs/second.png"}
        ]}}"""
        val items = BilibiliContent.parse(data, "example://open")
        assertEquals(listOf("First film", "Second film"), items.map { it.title })
        assertTrue(items.all { it.curatedBackground && it.image!!.startsWith("https://") })
        assertTrue(items.all { HomeContent.eligibleArtwork(BilibiliContent.PACKAGE, it) })
    }

    @Test fun rejectedResponsesAndUnofficialImagesDoNotInventRecommendationBackgrounds() {
        assertTrue(BilibiliContent.parse("""{"code":-403,"data":{"list":[]}}""", "example://open").isEmpty())
        assertTrue(BilibiliContent.parse("<html>failed</html>", "example://open").isEmpty())
        val data = """{"code":0,"result":{"list":[{"season_id":1,"title":"Wrong CDN","cover":"https://hdslb.com.example.org/poster.png"}]}}"""
        assertTrue(BilibiliContent.parse(data, "example://open").isEmpty())
    }
}
