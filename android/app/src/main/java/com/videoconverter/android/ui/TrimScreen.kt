package com.videoconverter.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videoconverter.android.domain.MediaInfo
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    media: MediaInfo,
    onBack: () -> Unit,
    onSave: (MediaInfo) -> Unit,
) {
    val duration = (media.durationSecs ?: 0.0).coerceAtLeast(0.0)
    var cursor by remember { mutableFloatStateOf((media.trimStartSecs ?: 0.0).toFloat()) }
    var start by remember { mutableFloatStateOf((media.trimStartSecs ?: 0.0).toFloat()) }
    var end by remember { mutableFloatStateOf((media.trimEndSecs ?: duration).toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("裁剪") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(media.displayName, style = MaterialTheme.typography.titleMedium)
            if (supportsSystemPreview(media)) {
                VideoPreview(media.sourceUri)
            } else {
                Text("该格式使用时间轴裁剪，不提供视频预览")
                HorizontalDivider()
            }
            Text("时间轴 ${formatTime(cursor.toDouble())} / ${formatTime(duration)}")
            Slider(
                value = cursor.coerceIn(0f, duration.toFloat().coerceAtLeast(0.01f)),
                onValueChange = { cursor = it },
                valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        start = cursor.coerceAtMost(end)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("设为起点") }
                Button(
                    onClick = {
                        end = cursor.coerceAtLeast(start)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("设为终点") }
            }
            Text("范围：${formatTime(start.toDouble())} – ${formatTime(end.toDouble())}")
            Button(
                onClick = {
                    onSave(
                        media.copy(
                            trimStartSecs = start.toDouble().takeIf { it > 0.0 },
                            trimEndSecs = end.toDouble().takeIf { it < duration },
                        ),
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存裁剪") }
        }
    }
}

@Composable
private fun VideoPreview(sourceUri: String) {
    val context = LocalContext.current
    val player = remember(sourceUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(sourceUri)))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
