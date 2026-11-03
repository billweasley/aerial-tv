package app.orchard.tv.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.apps.SourceDevice
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.SOURCE_KEY_PREFIX
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.SourceKind
import app.orchard.tv.system.TvSource
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/** One glass shelf for external devices, with a direct, labeled action for each device. */
@Composable
internal fun InputsCard(
    sources: List<TvSource>,
    tileHeight: Dp,
    enabled: Boolean,
    focusRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    sourceRequester: (TvSource) -> FocusRequester,
    movingKey: String?,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    onOpenSource: (TvSource) -> Unit,
    onLongPress: (TvSource, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byId = sources.associateBy { it.id }
    val shown = AerialSettings.orderedSourceIds(sources.map { it.id }).mapNotNull(byId::get)
        .filter { it.id in AerialSettings.pinnedSources }.take(AerialSettings.MAX_HOME_SOURCES)
    val allRequester = remember { FocusRequester() }
    val markSize = (tileHeight * .3f).coerceIn(18.dp, 30.dp)
    fun requester(index: Int) = sourceRequester(shown[index])

    Column(modifier.padding(horizontal = PlatePadH, vertical = PlatePadV)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEachIndexed { index, source ->
              key(source.id) {
                var bounds by remember { mutableStateOf(Rect.Zero) }
                Pressable(
                    onClick = { onOpenSource(source) }, enabled = enabled,
                    onLongPress = { if (movingKey == null) onLongPress(source, bounds) },
                    focusRequester = requester(index), focusedScale = 1.04f,
                    modifier = Modifier.weight(1f).onGloballyPositioned { bounds = it.boundsInWindow() }.focusProperties {
                        up = upRequester
                        downRequester?.let { down = it }
                        left = if (index == 0) FocusRequester.Cancel else requester(index - 1)
                        right = if (index < shown.lastIndex) requester(index + 1) else allRequester
                    },
                ) { state ->
                    LaunchedEffect(state.focused) { onFocused(state.focused) }
                    val highlighted = state.focused || movingKey == SOURCE_KEY_PREFIX + source.id
                    val foreground = if (highlighted) Palette.OnFocus else Color.White
                    val look = SourceLooks.of(source.id)
                    val manual = look?.device?.takeUnless { source.dynamicSpd }
                    val name = manual?.let { stringResource(it.label) } ?: source.deviceName
                    val note = look?.name
                    val device = manual ?: when {
                        name?.contains("playstation", true) == true || name.equals("PS5", true) -> SourceDevice.PlayStation
                        name?.contains("google tv", true) == true -> SourceDevice.GoogleTv
                        name?.contains("switch", true) == true -> SourceDevice.Switch
                        name?.contains("apple tv", true) == true -> SourceDevice.AppleTv
                        name?.contains("xbox", true) == true -> SourceDevice.Xbox
                        else -> null
                    }
                    val subtitle = listOfNotNull(note, source.label.takeIf { name != null }).joinToString(" · ").ifBlank {
                        stringResource(if (source.connected) R.string.input_connected else R.string.home_inputs_available)
                    }
                    val mark: @androidx.compose.runtime.Composable () -> Unit = {
                        if (device != null) DeviceMark(device, foreground, markSize)
                        else if (source.kind == SourceKind.Hdmi || source.kind == SourceKind.DisplayPort) MonitorMark(foreground, markSize)
                        else GlyphIcon(when (source.kind) {
                            SourceKind.Tuner -> Glyph.Antenna
                            SourceKind.Av -> Glyph.Av
                            SourceKind.Usb -> Glyph.Usb
                            else -> Glyph.Hdmi
                        }, foreground, markSize)
                    }
                    Column(Modifier.fillMaxWidth().height(tileHeight)
                        .background(if (highlighted) Palette.Focus else Color.Transparent, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        mark()
                        Spacer(Modifier.height(3.dp))
                        BasicText(name ?: source.label, style = Type.Headline.copy(color = foreground, fontSize = 14.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(subtitle, style = Type.Body.copy(color = foreground.copy(alpha = .65f), fontSize = 11.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
              }
            }
            Pressable(
                onClick = onClick, enabled = enabled,
                focusRequester = if (shown.isEmpty()) focusRequester else allRequester, focusedScale = 1.04f,
                modifier = Modifier.width(96.dp).focusProperties {
                    up = upRequester
                    downRequester?.let { down = it }
                    left = if (shown.isNotEmpty()) requester(shown.lastIndex) else FocusRequester.Cancel
                    right = FocusRequester.Cancel
                },
            ) { state ->
                LaunchedEffect(state.focused) { onFocused(state.focused) }
                val foreground = if (state.focused) Palette.OnFocus else Color.White
                Column(
                    Modifier.fillMaxWidth().height(tileHeight)
                        .background(if (state.focused) Palette.Focus else Color.Transparent, RoundedCornerShape(14.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlyphIcon(Glyph.ChevronRight, foreground, 18.dp)
                    Spacer(Modifier.height(4.dp))
                    BasicText(stringResource(R.string.home_inputs_all), style = Type.Body.copy(color = foreground, fontSize = 12.sp), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun DeviceMark(device: SourceDevice, color: Color, markSize: Dp) {
    val path = remember(device) { PathParser().parsePathString(device.mark).toPath() }
    Canvas(Modifier.size(markSize)) {
        scale(size.minDimension / device.markBox, pivot = Offset.Zero) {
            translate(0f, if (device.markBox > 24f) device.markBox else 0f) { drawPath(path, color) }
        }
    }
}

@Composable
private fun MonitorMark(color: Color, markSize: Dp) {
    Canvas(Modifier.size(markSize)) {
        val unit = size.width / 30f
        drawRoundRect(color, Offset(unit, unit * 2), Size(unit * 28, unit * 20), CornerRadius(unit * 3), style = Stroke(unit * 2))
        drawLine(color, Offset(unit * 15, unit * 22), Offset(unit * 15, unit * 27), unit * 2, StrokeCap.Round)
        drawLine(color, Offset(unit * 9, unit * 27), Offset(unit * 21, unit * 27), unit * 2, StrokeCap.Round)
    }
}
