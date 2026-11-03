package app.orchard.tv.ui.home

import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.HomeContent
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.smoothBorder
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URL

/** A restrained Top Shelf: up to three wide covers, above the permanently visible input shelf. */
@Composable
internal fun ContentShelf(app: AppEntry, items: List<HomeContent.Item>, selected: Int,
    modifier: Modifier, firstRequester: FocusRequester, downRequester: FocusRequester,
    upRequester: FocusRequester, onSelected: (Int) -> Unit, onFocused: (Boolean) -> Unit,
    onOpen: (HomeContent.Item) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cardWidth = (maxWidth - 28.dp) / 3
        val posterHeight = (maxHeight - 62.dp).coerceIn(48.dp, 102.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            BasicText(app.label, style = Type.Headline,
                maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                items.take(HomeContent.HOME_RECOMMENDATIONS).forEachIndexed { index, entry ->
                    key(entry.id, index) {
                        Pressable(onClick = { onOpen(entry) },
                            focusRequester = if (index == selected) firstRequester else null,
                            modifier = Modifier.width(cardWidth).focusProperties {
                                down = downRequester; up = upRequester
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == items.lastIndex) right = FocusRequester.Cancel
                            }, focusedScale = 1.035f,
                        ) { state ->
                            LaunchedEffect(state.focused) { if (state.focused) { onSelected(index); onFocused(true) } }
                            Column(Modifier.clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = .36f))
                                .smoothBorder(if (state.focused) 2.dp else 1.dp,
                                    if (state.focused) Color.White else Color.White.copy(alpha = .16f), 14.dp)) {
                                ContentArt(entry.image, app.art.back, Modifier.fillMaxWidth().height(posterHeight))
                                BasicText(entry.title, style = Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The only live preview on Home: full screen, behind the fixed shelves, without channel controls. */
@Composable
internal fun LiveContentBackground(url: String, ready: Boolean, onReady: () -> Unit, onFailed: () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (ready) 1f else .01f }) {
        LiveContent(url, onReady, onFailed)
    }
}

@Composable
private fun ContentArt(uri: String?, fallback: ImageBitmap, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = null
        if (uri.isNullOrBlank()) return@produceState
        value = runInterruptible(Dispatchers.IO) {
            runCatching {
                val bytes = if (uri.startsWith("content://")) {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBounded() }
                } else if (uri.startsWith("https://") || uri.startsWith("http://")) {
                    val c = URL(uri).openConnection() as HttpURLConnection
                    c.connectTimeout = 1500; c.readTimeout = 1500
                    try { c.inputStream.use { it.readBounded() } } finally { c.disconnect() }
                } else null
                if (bytes == null) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, bounds.outWidth / 640, bounds.outHeight / 360)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Image(bitmap ?: fallback, null, modifier, contentScale = ContentScale.Crop)
}

private fun java.io.InputStream.readBounded(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
    while (true) {
        if (Thread.currentThread().isInterrupted || android.os.SystemClock.elapsedRealtime() > deadline) throw java.io.IOException("Artwork timeout")
        val n = read(buf)
        if (n < 0) break
        require(out.size() + n <= 3 * 1024 * 1024)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LiveContent(url: String, onReady: () -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    val failed by rememberUpdatedState(onFailed)
    var firstFrame by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    val player = remember(url) { ExoPlayer.Builder(context).build() }
    var texture by remember(player) { mutableStateOf<TextureView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                // Compose may not draw another frame while the other app starts its decoder.
                texture?.visibility = android.view.View.INVISIBLE
                player.stop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                texture?.let { fitLiveTexture(it, videoSize) }
            }
            override fun onPlayerError(error: PlaybackException) { failed() }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) failed()
            }
        }
        player.addListener(listener)
        player.volume = if (AerialSettings.previewSound) 1f else 0f
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(url, firstFrame) {
        if (firstFrame) {
            // The render callback can precede TextureView composition. Reveal after it draws.
            withFrameNanos { }; withFrameNanos { }
            ready()
        }
    }
    LaunchedEffect(AerialSettings.previewSound) {
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, AerialSettings.previewSound)
        player.volume = if (AerialSettings.previewSound) 1f else 0f
    }
    LaunchedEffect(url, buffering, firstFrame) {
        if (buffering || !firstFrame) { delay(8_000); failed() }
    }
    // AndroidView retains its factory across recompositions. A channel needs a fresh view as
    // well as a fresh player, otherwise the new player has no surface and the old frame remains.
    key(url) {
        AndroidView(factory = { ctx -> TextureView(ctx).also { view ->
            texture = view
            view.isFocusable = false
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitLiveTexture(view, player.videoSize) }
            player.setVideoTextureView(view)
        } }, update = { fitLiveTexture(it, player.videoSize) }, modifier = Modifier.fillMaxSize())
    }
}

private fun fitLiveTexture(view: TextureView, size: androidx.media3.common.VideoSize) {
    if (view.width <= 0 || view.height <= 0 || size.width <= 0 || size.height <= 0) return
    val videoAspect = size.width * size.pixelWidthHeightRatio / size.height
    val viewAspect = view.width.toFloat() / view.height
    val sx = maxOf(1f, videoAspect / viewAspect)
    val sy = maxOf(1f, viewAspect / videoAspect)
    view.setTransform(android.graphics.Matrix().apply {
        setScale(sx, sy, view.width / 2f, view.height / 2f)
    })
}
