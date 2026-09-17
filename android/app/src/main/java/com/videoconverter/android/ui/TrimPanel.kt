package com.videoconverter.android.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videoconverter.android.R
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.ui.theme.ShapeTokens
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
    val previewWell = MaterialTheme.colorScheme.surfaceVariant
    val previewWellArgb = previewWell.toArgb()

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

    fun togglePlayback() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            playing = false
        } else {
            val current = exo.currentPosition / 1000.0
            if (current < start || current >= end - 0.04) {
                exo.seekTo((start * 1000).toLong())
                playhead = start
            }
            exo.play()
            playing = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp, top = 8.dp)) {
                Text(
                    stringResource(R.string.trim_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.trim_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    onChange(media.copy(trimStartSecs = null, trimEndSecs = null))
                    seekPlayhead(0.0)
                },
            ) {
                Text(stringResource(R.string.trim_reset))
            }
        }

        if (videoSurface && player != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(ShapeTokens.Panel))
                    .background(previewWell),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = false
                            controllerAutoShow = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(previewWellArgb)
                            setBackgroundColor(previewWellArgb)
                            isClickable = false
                            isFocusable = false
                            this.player = player
                        }
                    },
                    update = { view ->
                        view.player = player
                        view.useController = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                FilledIconButton(
                    onClick = ::togglePlayback,
                    modifier = Modifier.size(56.dp),
                ) {
                    PlayPauseIcon(
                        playing = playing,
                        contentDescription = stringResource(
                            if (playing) R.string.trim_pause else R.string.trim_play_selection,
                        ),
                    )
                }
            }
        } else if (!playPreview) {
            Text(
                stringResource(R.string.trim_no_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ShapeTokens.Panel))
                    .background(previewWell)
                    .padding(horizontal = 16.dp, vertical = 28.dp),
            )
        } else if (player != null) {
            FilledTonalButton(
                onClick = ::togglePlayback,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                PlayPauseIcon(
                    playing = playing,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (playing) R.string.trim_pause else R.string.trim_play_selection),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { applyTrim(playhead, end) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.trim_set_start),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = { applyTrim(start, playhead) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.trim_set_end),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

        Row(modifier = Modifier.fillMaxWidth()) {
            TrimTimeLabel(
                text = stringResource(R.string.trim_start, formatClock(start)),
                alignStart = true,
                modifier = Modifier.weight(1f),
            )
            TrimTimeLabel(
                text = stringResource(R.string.trim_end, formatClock(end)),
                alignStart = false,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TrimTimeLabel(
                text = stringResource(R.string.trim_current, formatClock(playhead)),
                alignStart = true,
                modifier = Modifier.weight(1f),
            )
            TrimTimeLabel(
                text = stringResource(R.string.trim_keep, formatDurationLabel(end - start, LocalContext.current.resources)),
                alignStart = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrimTimeLabel(
    text: String,
    alignStart: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignStart) TextAlign.Start else TextAlign.End,
    )
}

@Composable
private fun PlayPauseIcon(
    playing: Boolean,
    contentDescription: String?,
) {
    val tint = LocalContentColor.current
    if (playing) {
        Canvas(
            modifier = Modifier
                .size(24.dp)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        ) {
            val w = size.width
            val bar = w * 0.22f
            val gap = w * 0.18f
            val h = w * 0.72f
            val top = (size.height - h) / 2f
            val left = (w - bar * 2 - gap) / 2f
            val radius = CornerRadius(bar / 2f)
            drawRoundRect(tint, Offset(left, top), Size(bar, h), radius)
            drawRoundRect(tint, Offset(left + bar + gap, top), Size(bar, h), radius)
        }
    } else {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = contentDescription,
            tint = tint,
        )
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
    val rangeColor = MaterialTheme.colorScheme.onSurface
    val playheadColor = MaterialTheme.colorScheme.primary
    val handleInset = 10.dp
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = handleInset)
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    val startX = ((startState.value / duration) * width).toFloat()
                    val endX = ((endState.value / duration) * width).toFloat()
                    val hit = 24.dp.toPx()
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
                .clip(RoundedCornerShape(ShapeTokens.Chip))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .drawBehind {
                    val width = size.width
                    val startX = startRatio * width
                    val endX = endRatio * width
                    val playX = playRatio * width
                    drawRoundRect(
                        color = rangeColor,
                        topLeft = Offset(startX, 6.dp.toPx()),
                        size = Size((endX - startX).coerceAtLeast(2f), 16.dp.toPx()),
                        cornerRadius = CornerRadius(99.dp.toPx()),
                    )
                    drawRect(
                        color = playheadColor,
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
            .background(MaterialTheme.colorScheme.primary),
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
