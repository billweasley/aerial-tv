package app.orchard.tv.system

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeContentTest {
    @Test fun m3uKeepsRealTitlesLogosAndStreamsWithoutTreatingTheHeaderAsAChannel() {
        val items = HomeContent.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://images.example/one.png",Channel one
            http://192.0.2.2:8080/one.ts
            #EXTINF:-1,Channel two
            https://streams.example/two.m3u8
        """.trimIndent())
        assertEquals(listOf("Channel one", "Channel two"), items.map { it.title })
        assertEquals("https://images.example/one.png", items[0].image)
        assertEquals("http://192.0.2.2:8080/one.ts", items[0].stream)
        assertTrue(items.all { it.kind == HomeContent.Kind.Live })
    }

    @Test fun malformedOrUnsupportedSourcesProduceNoFakeContent() {
        assertTrue(HomeContent.parse("<html>network error</html>").isEmpty())
        assertTrue(HomeContent.parse("not json [{").isEmpty())
        assertTrue(HomeContent.parse("#EXTM3U\n#EXTINF:-1,Bad\nfile:///private/file").isEmpty())
        assertTrue(HomeContent.parse("[{\"title\":\"No action\"}]").isEmpty())
    }

    @Test fun txtChannelsAndJsonRecommendationsRemainDifferentKinds() {
        val live = HomeContent.parse("News,http://192.0.2.2/live").single()
        val recommended = HomeContent.parse("""{"items":[{"title":"New album","intent":"music://album/42"}]}""").single()
        assertEquals(HomeContent.Kind.Live, live.kind)
        assertEquals(HomeContent.Kind.Recommendation, recommended.kind)
        assertEquals("music://album/42", recommended.intent)
    }

    @Test fun aFeedCannotMakeTheLauncherOpenAnUnrelatedAppOrLocalFile() {
        val ctx = RuntimeEnvironment.getApplication()
        assertNull(HomeContent.contentIntent(ctx, "app.orchard.tv", "intent:#Intent;component=other.app/.MainActivity;end"))
        assertNull(HomeContent.contentIntent(ctx, "app.orchard.tv", "file:///data/local/tmp/private"))
        assertNull(HomeContent.contentIntent(ctx, "app.orchard.tv", "javascript:alert(1)"))
    }

    @Test fun configurationRejectsInvalidUpdatesAndPreservesThePreviousSource() {
        val ctx = RuntimeEnvironment.getApplication()
        val pkg = "example.video"
        assertTrue(HomeContent.configure(ctx, pkg, "https://feed.example/list.m3u", ""))
        assertFalse(HomeContent.configure(ctx, pkg, "file:///etc/config", ""))
        assertFalse(HomeContent.configure(ctx, pkg, "", "broken data"))
        assertEquals("https://feed.example/list.m3u", HomeContent.profile(ctx, pkg).feed)
    }

    @Test fun unusableRecommendationLinksFallBackInsteadOfDisplayingDeadCards() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val pkg = "example.video"
        assertTrue(HomeContent.configure(ctx, pkg, "", """[{"title":"Unavailable episode","intent":"intent:#Intent;component=other.app/.Player;end"}]"""))
        assertTrue(HomeContent.load(ctx, pkg).isEmpty())
    }

    @Test fun mytvSelectsTheConfiguredSourceAndDoesNotGuessWhenTheIndexIsInvalid() {
        val config = JSONObject("""{"iptvSourceList":{"value":[{"url":"https://example/a"},{"url":"https://example/b"}]},"iptvSourceCurrentIdx":1}""")
        assertEquals("https://example/b", MytvContent.selectedSource(config)?.getString("url"))
        config.put("iptvSourceCurrentIdx", 8)
        assertNull(MytvContent.selectedSource(config))
    }
}
