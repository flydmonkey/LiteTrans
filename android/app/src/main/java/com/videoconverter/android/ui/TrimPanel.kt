package com.videoconverter.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videoconverter.android.R
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.ui.theme.LightTokens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class TrimDrag { Start, End, Playhead }

@Composable
fun TrimPanel(
    media: MediaInfo,
    onChange: (MediaInfo) -> Unit,
) {
    key(media.sourceUri) {
        TrimPanelContent(media, onChange)
    }
}

@Composable
private fun TrimPanelContent(
    media: MediaInfo,
    onChange: (MediaInfo) -> Unit,
) {
    val context = LocalContext.current
    val duration = (media.durationSecs ?: 0.0).coerceAtLeast(0.01)
    val start = (media.trimStartSecs ?: 0.0).coerceIn(0.0, duration)
    val end = (media.trimEndSecs ?: duration).coerceIn(0.0, duration)
    val playPreview = canPlayPreview(media)
    val videoSurface = showVideoSurface(media)
    var playhead by remember { mutableDoubleStateOf(start) }
    var playing by remember { mutableStateOf(false) }
    val mediaState = rememberUpdatedState(media)
    val startState = rememberUpdatedState(start)
    val endState = rememberUpdatedState(end)
    val onChangeState = rememberUpdatedState(onChange)

    val player = remember(media.sourceUri, playPreview) {
        if (!playPreview) {
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(media.sourceUri)))
                prepare()
                seekTo((start * 1000).toLong())
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    LaunchedEffect(player) {
        val exo = player ?: return@LaunchedEffect
        while (true) {
            val current = exo.currentPosition / 1000.0
            playhead = current
            playing = exo.isPlaying
            if (exo.isPlaying && current >= endState.value - 0.04) {
                exo.pause()
                exo.seekTo((startState.value * 1000).toLong())
                playhead = startState.value
                playing = false
            }
            delay(50)
        }
    }

    fun seekPlayhead(seconds: Double) {
        playhead = seconds.coerceIn(0.0, duration)
        player?.seekTo((playhead * 1000).toLong())
    }

    fun applyTrim(nextStart: Double, nextEnd: Double) {
        val clamped = clampTrim(nextStart, nextEnd, duration)
        onChangeState.value(
            mediaState.value.copy(
                trimStartSecs = clamped.first,
                trimEndSecs = clamped.second,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(LightTokens.Card))
            .border(1.dp, Color(LightTokens.Border), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.trim_title), color = Color(LightTokens.Ink), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.trim_hint),
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                )
            }
            Text(
                stringResource(R.string.trim_reset),
                color = Color(LightTokens.Accent),
                modifier = Modifier.clickable {
                    onChange(media.copy(trimStartSecs = null, trimEndSecs = null))
                    seekPlayhead(0.0)
                },
            )
        }

        if (videoSurface && player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
            )
        } else if (!playPreview) {
            Text(
                stringResource(R.string.trim_no_preview),
                color = Color(LightTokens.Muted),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, Color(LightTokens.Border), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 28.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (player != null) {
                InkChip(stringResource(if (playing) R.string.trim_pause else R.string.trim_play_selection)) {
                    if (player.isPlaying) {
                        player.pause()
                        playing = false
                    } else {
                        val current = player.currentPosition / 1000.0
                        if (current < start || current >= end - 0.04) {
                            player.seekTo((start * 1000).toLong())
                            playhead = start
                        }
                        player.play()
                        playing = true
                    }
                }
            }
            InkChip(stringResource(R.string.trim_set_start)) { applyTrim(playhead, end) }
            InkChip(stringResource(R.string.trim_set_end)) { applyTrim(start, playhead) }
        }

        TrimTrack(
            duration = duration,
            start = start,
            end = end,
            playhead = playhead,
            onDragStart = { applyTrim(it, endState.value) },
            onDragEnd = { applyTrim(startState.value, it) },
            onPlayhead = ::seekPlayhead,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.trim_start, formatClock(start)), color = Color(LightTokens.Muted), fontSize = 13.sp)
            Text(stringResource(R.string.trim_current, formatClock(playhead)), color = Color(LightTokens.Muted), fontSize = 13.sp)
            Text(stringResource(R.string.trim_end, formatClock(end)), color = Color(LightTokens.Muted), fontSize = 13.sp)
            Text(stringResource(R.string.trim_keep, formatDurationLabel(end - start, LocalContext.current.resources)), color = Color(LightTokens.Muted), fontSize = 13.sp)
        }
    }
}

@Composable
private fun TrimTrack(
    duration: Double,
    start: Double,
    end: Double,
    playhead: Double,
    onDragStart: (Double) -> Unit,
    onDragEnd: (Double) -> Unit,
    onPlayhead: (Double) -> Unit,
) {
    val startRatio = (start / duration).toFloat().coerceIn(0f, 1f)
    val endRatio = (end / duration).toFloat().coerceIn(0f, 1f)
    val playRatio = (playhead / duration).toFloat().coerceIn(0f, 1f)
    val startState = rememberUpdatedState(start)
    val endState = rememberUpdatedState(end)
    val onDragStartState = rememberUpdatedState(onDragStart)
    val onDragEndState = rememberUpdatedState(onDragEnd)
    val onPlayheadState = rememberUpdatedState(onPlayhead)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    val startX = ((startState.value / duration) * width).toFloat()
                    val endX = ((endState.value / duration) * width).toFloat()
                    val hit = 18.dp.toPx()
                    val kind = when {
                        abs(down.position.x - startX) <= hit -> TrimDrag.Start
                        abs(down.position.x - endX) <= hit -> TrimDrag.End
                        else -> TrimDrag.Playhead
                    }
                    val first = timeAt(down.position.x, width, duration)
                    when (kind) {
                        TrimDrag.Start -> onDragStartState.value(first)
                        TrimDrag.End -> onDragEndState.value(first)
                        TrimDrag.Playhead -> onPlayheadState.value(first)
                    }
                    drag(down.id) { change ->
                        change.consume()
                        val next = timeAt(change.position.x, width, duration)
                        when (kind) {
                            TrimDrag.Start -> onDragStartState.value(next)
                            TrimDrag.End -> onDragEndState.value(next)
                            TrimDrag.Playhead -> onPlayheadState.value(next)
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(99.dp))
                .background(Color(LightTokens.Chip))
                .drawBehind {
                    val width = size.width
                    val startX = startRatio * width
                    val endX = endRatio * width
                    val playX = playRatio * width
                    drawRoundRect(
                        color = Color(LightTokens.Ink),
                        topLeft = Offset(startX, 6.dp.toPx()),
                        size = Size((endX - startX).coerceAtLeast(2f), 16.dp.toPx()),
                        cornerRadius = CornerRadius(99.dp.toPx()),
                    )
                    drawRect(
                        color = Color(LightTokens.Accent),
                        topLeft = Offset(playX - 1.dp.toPx(), 2.dp.toPx()),
                        size = Size(2.dp.toPx(), 24.dp.toPx()),
                    )
                },
        )
        val widthPx = constraints.maxWidth.toFloat()
        Handle(x = startRatio * widthPx)
        Handle(x = endRatio * widthPx)
    }
}

@Composable
private fun Handle(x: Float) {
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (x - 9.dp.toPx()).roundToInt(),
                    ((28.dp.toPx() - 18.dp.toPx()) / 2f).roundToInt(),
                )
            }
            .size(18.dp)
            .border(2.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .background(Color(LightTokens.Accent)),
    )
}

@Composable
private fun InkChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(LightTokens.Ink),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(LightTokens.Chip))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        fontWeight = FontWeight.SemiBold,
    )
}

private fun formatDurationLabel(seconds: Double, resources: android.content.res.Resources): String {
    val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
    if (total < 60) return resources.getString(R.string.duration_seconds, total)
    val mins = total / 60
    val secs = total % 60
    return if (secs == 0) {
        resources.getString(R.string.duration_minutes, mins)
    } else {
        resources.getString(R.string.duration_min_sec, mins, secs)
    }
}
