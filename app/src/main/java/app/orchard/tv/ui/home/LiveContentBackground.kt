package app.orchard.tv.ui.home

import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.HomeContent
import kotlinx.coroutines.delay

/** The only live preview on Home: full screen, behind the fixed shelves, without channel controls. */
@Composable
internal fun LiveContentBackground(url: String, ready: Boolean, onReady: () -> Unit, onFailed: () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (ready) 1f else .01f }) {
        LiveContent(url, onReady, onFailed)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LiveContent(url: String, onReady: () -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    val failed by rememberUpdatedState(onFailed)
    var firstFrame by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    val player = remember(url) {
        ExoPlayer.Builder(context).setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 300, 1_000).build()).build()
    }
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
        HomeContent.previewPreparing(url)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !AerialSettings.previewSound).build()
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
            HomeContent.previewRendered(url)
            ready()
        }
    }
    LaunchedEffect(AerialSettings.previewSound) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !AerialSettings.previewSound).build()
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
