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
    @Test fun directPlayerChannelOutranksDelayedWatchNextWithoutReadingHistory() {
        val channels = HomeContent.parse("Inner Mongolia,http://192.0.2.2/old.ts\nHunan,http://192.0.2.2/new.ts")
        val state = JSONObject("""{"iptvChannelLastPlay":{"name":"Hunan","standardName":"Hunan"}}""")
        val selected = MytvContent.resolveChannel(channels, state, { error("History must not override direct state") })
        assertEquals("Hunan", selected.single().title)
        state.getJSONObject("iptvChannelLastPlay").put("name", "Unavailable").put("standardName", "Unavailable")
        assertTrue(MytvContent.resolveChannel(channels, state, { error("Do not play an old channel on match failure") }).isEmpty())
    }

    @Test fun absentDirectStateUsesPublishedHistoryAndDoesNotInventAChannel() {
        val channels = HomeContent.parse("News,http://192.0.2.2/news.ts")
        val history = HomeContent.Item("history", "News", kind = HomeContent.Kind.Continue)
        assertEquals(channels, MytvContent.resolveChannel(channels, null, { history }))
        assertTrue(MytvContent.resolveChannel(channels, JSONObject(), { null }).isEmpty())
        assertNull(MytvContent.currentChannel(JSONObject("""{"iptvChannelLastPlay":{"name":null}}""")))
    }

    @Test fun previewLayoutConflictRequiresBothPinnedInputsAndTwoRows() {
        val settings = app.orchard.tv.settings.AerialSettings
        assertFalse(settings.previewLayoutConflict(true, true))
        assertFalse(settings.previewLayoutConflict(false, false))
        assertFalse(settings.previewLayoutConflict(true, false))
        assertTrue(settings.previewLayoutConflict(false, true))
    }

    @Test fun aReturnWaitsForNewHistoryButDoesNotPermanentlyDisableTheLastChannelAfterExit() {
        val channel = HomeContent.Item("live", "News", stream = "http://192.0.2.2/live.ts", kind = HomeContent.Kind.Live)
        val oldRecord = HomeContent.Item("recent", "News", kind = HomeContent.Kind.Continue, engagedAt = 1_000L)
        assertTrue(MytvContent.followRecent(listOf(channel), oldRecord,
            HomeContent.recentGate(2_000L, 10_000L, 11_000L)).isEmpty())
        assertEquals(listOf(channel), MytvContent.followRecent(listOf(channel), oldRecord,
            HomeContent.recentGate(2_000L, 10_000L, 14_000L)))
        assertEquals(0L, HomeContent.recentGate(2_000L, 10_000L, 9_000L))
    }

    @Test fun ignoresAChannelRecordThatPredatesTheLatestVisitToTheLiveApp() {
        val channel = HomeContent.Item("live", "News", stream = "http://192.0.2.2/live.ts", kind = HomeContent.Kind.Live)
        val recent = HomeContent.Item("recent", "News", kind = HomeContent.Kind.Continue, engagedAt = 1_000)
        assertTrue(MytvContent.followRecent(listOf(channel), recent, openedAt = 2_000).isEmpty())
        assertEquals(listOf(channel), MytvContent.followRecent(listOf(channel), recent.copy(engagedAt = 2_500), openedAt = 2_000))
        assertTrue(MytvContent.followRecent(listOf(channel), recent.copy(engagedAt = 0), openedAt = 2_000).isEmpty())
    }

    @Test fun followsThePublishedLastChannelBeyondTheFirstTwelveAndNeverGuesses() {
        val channels = HomeContent.parse((1..131).joinToString("\n") { "Channel $it,http://192.0.2.2/$it.ts" })
        assertEquals(131, channels.size)
        val recent = HomeContent.Item("recent", "Channel 100", kind = HomeContent.Kind.Continue)
        assertEquals("Channel 100", MytvContent.followRecent(channels, recent).single().title)
        assertTrue(MytvContent.followRecent(channels, null).isEmpty())
        assertTrue(MytvContent.followRecent(channels, recent.copy(title = "Missing")).isEmpty())
        assertTrue(MytvContent.followRecent(channels + channels[99].copy(id = "duplicate", stream = "http://192.0.2.2/other"), recent).isEmpty())
    }

    @Test fun homeRecommendationsAreLimitedToThreeAndLiveHasNoChannelPicker() {
        val recs = (0..11).map { HomeContent.Item("r:$it", "Recommendation $it", intent = "https://example.com/$it") }
        val channels = (0..11).map { HomeContent.Item("l:$it", "Channel $it", stream = "https://example.com/$it.ts", kind = HomeContent.Kind.Live) }
        assertEquals(3, HomeContent.displayItems(recs, true, false).size)
        assertEquals(1, HomeContent.displayItems(channels, true, true).size)
        assertTrue(HomeContent.displayItems(channels, true, false).isEmpty())
    }

    @Test fun liveAndRecommendationsCanBeEnabledIndependently() {
        val rec = HomeContent.Item("r", "An episode", intent = "https://example.com/watch")
        val live = HomeContent.Item("l", "A channel", stream = "https://example.com/live.ts", kind = HomeContent.Kind.Live)
        assertEquals(listOf(live), HomeContent.displayItems(listOf(rec, live), false, true))
        assertEquals(listOf(rec), HomeContent.displayItems(listOf(rec, live), true, false))
        assertTrue(HomeContent.displayItems(listOf(rec, live), false, false).isEmpty())
    }

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
