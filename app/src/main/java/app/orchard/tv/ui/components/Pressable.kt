package app.orchard.tv.ui.components

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun KeyEvent.isSelect(): Boolean = when (nativeKeyEvent.keyCode) {
    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
    NativeKeyEvent.KEYCODE_BUTTON_A, NativeKeyEvent.KEYCODE_BUTTON_SELECT -> true
    else -> false
}

class PressState {
    var focused by mutableStateOf(false)
    var pressed by mutableStateOf(false)

    /** The press under way came from the remote; a finger on the control must not end it. */
    internal var byKey = false
}

/**
 * Takes taps that land on an overlay's panel, so they neither fall through to Home nor reach a
 * scrim around the panel that closes it.
 */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * A tap here closes the overlay. Add it only while the overlay is up: an idle pointer handler
 * still takes every touch from Home beneath it.
 */
fun Modifier.dismissOnTap(onDismiss: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures { onDismiss() } }

/**
 * A focusable control for the remote. It fires on key up, and only for a press that began on it:
 * when a long press opens a menu, the key-up that ends that press must not also choose the menu's
 * first item.
 *
 * A tap (touch screen or mouse, issue #60) does the same: it focuses the control and fires when the
 * finger lifts. Touches already go only to the control they began on, and a drag that turns into a
 * scroll cancels the tap.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    focusedScale: Float = 1.06f,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable BoxScope.(PressState) -> Unit,
) {
    val state = remember { PressState() }
    val ownRequester = remember { FocusRequester() }
    val requester = focusRequester ?: ownRequester
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongPress)
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var held by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            state.pressed -> focusedScale - 0.05f
            state.focused -> focusedScale
            else -> 1f
        },
        animationSpec = if (state.pressed) tween(100, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "pressable-scale",
    )
    Box(
        modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !state.focused) Sounds.focusMoved()
                state.focused = it.isFocused
                if (!it.isFocused) {
                    holdJob?.cancel()
                    state.pressed = false
                    state.byKey = false
                }
            }
            .pointerInput(enabled, onLongPress != null) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        state.pressed = true
                        tryAwaitRelease()
                        if (!state.byKey) state.pressed = false
                    },
                    onTap = {
                        if (held) { held = false; return@detectTapGestures }
                        runCatching { requester.requestFocus() }
                        Sounds.select()
                        click()
                    },
                    onLongPress = if (onLongPress == null) null else { _ ->
                        held = true
                        state.pressed = false
                        runCatching { requester.requestFocus() }
                        Sounds.select()
                        longClick?.invoke()
                    },
                )
            }
            .onKeyEvent { e ->
                if (onKey?.invoke(e) == true) return@onKeyEvent true
                if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU && longClick != null) {
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { Sounds.select(); longClick?.invoke() }
                    return@onKeyEvent true
                }
                if (!e.isSelect()) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            state.pressed = true
                            state.byKey = true
                            held = false
                            holdJob?.cancel()
                            if (longClick != null) holdJob = scope.launch {
                                delay(600)
                                if (state.pressed && state.byKey) {
                                    held = true
                                    state.pressed = false
                                    Sounds.select()
                                    longClick?.invoke()
                                }
                            }
                        } else if (state.byKey && !held && longClick != null) {
                            held = true
                            holdJob?.cancel()
                            state.pressed = false
                            Sounds.select()
                            longClick?.invoke()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        holdJob?.cancel()
                        val was = state.pressed && state.byKey && !held
                        state.pressed = false
                        state.byKey = false
                        if (was) {
                            Sounds.select()
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            // After the focusable: the bounds a scroll container brings into view are then the
            // card's own, not the 8% larger ones of the focus animation, which made a sheet or
            // list shift a few pixels with every move of focus and settle back.
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) { content(state) }
}
