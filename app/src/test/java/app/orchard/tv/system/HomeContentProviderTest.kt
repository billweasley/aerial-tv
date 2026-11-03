package app.orchard.tv.system

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HomeContentProviderTest {
    @Test fun readsOnlyTheSelectedAppsRowsAndPreservesTheContinueWatchingLabel() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val owner = "example.video"
        for (url in listOf("https://video.example/recommended", "https://video.example/continue")) {
            val info = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = owner; name = "example.video.VideoActivity"; exported = true; enabled = true
                    applicationInfo = ApplicationInfo().apply { packageName = owner; enabled = true }
                }
            }
            shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(owner), info)
        }
        ShadowContentResolver.registerProviderInternal("android.media.tv", Provider())
        val result = HomeContent.load(context, owner)
        assertEquals(listOf("New episode", "Previous episode"), result.map { it.title })
        assertEquals(listOf(HomeContent.Kind.Recommendation, HomeContent.Kind.Continue), result.map { it.kind })
        assertFalse(result.any { it.title == "Another app's item" })
        assertEquals(2_500L, result.last().engagedAt)
        // Read Watch Next independently, even if the recommendations fill their entire quota.
        ShadowContentResolver.registerProviderInternal("android.media.tv", Provider(recommendations = 12))
        assertEquals("Previous episode", HomeContent.recentItem(context, owner)?.title)
    }

    class Provider(private val recommendations: Int = 1) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: emptyArray()
            val cursor = MatrixCursor(columns)
            fun row(id: String, owner: String, title: String, action: String) {
                val data = mapOf("_id" to id, "package_name" to owner, "title" to title,
                    "short_description" to "Published by the app", "poster_art_uri" to "https://image.example/poster.jpg", "intent_uri" to action, "last_engagement_time_utc_millis" to "2500")
                cursor.addRow(columns.map { data[it] }.toTypedArray())
            }
            if (uri.lastPathSegment == "preview_program") {
                row("1", "other.video", "Another app's item", "https://other.example/private")
                repeat(recommendations) { row((it + 2).toString(), "example.video", "New episode", "https://video.example/recommended") }
            } else row("3", "example.video", "Previous episode", "https://video.example/continue")
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
