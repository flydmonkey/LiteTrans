package com.videoconverter.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.documentfile.provider.DocumentFile
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.PresetInfo
import com.videoconverter.android.domain.listPresets
import java.util.Locale

private val primaryPresetIds = listOf("mp4-h264", "mp4-copy", "mp4-h265", "mov-h264")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(appViewModel: AppViewModel = viewModel()) {
    val state by appViewModel.state.collectAsState()
    val context = LocalContext.current
    var trimMedia by remember { mutableStateOf<MediaInfo?>(null) }
    var showMore by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
        appViewModel::addUris,
    )
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        appViewModel::addUris,
    )
    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { it?.let(appViewModel::pickOutputTree) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { appViewModel.start() }

    trimMedia?.let { media ->
        TrimScreen(
            media = media,
            onBack = { trimMedia = null },
            onSave = appViewModel::updateTrim,
        )
        return
    }

    fun startWithNotificationPermission() {
        val preferences = context.getSharedPreferences("ui", 0)
        val firstRequest = !preferences.getBoolean("notificationAsked", false)
        if (Build.VERSION.SDK_INT >= 33 && firstRequest) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            appViewModel.start()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("轻转码")
                        Text(
                            "不上传 · 不联网",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = ::startWithNotificationPermission,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("开始转码") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StepCard(number = "1", title = "添加视频") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                galleryPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.VideoOnly,
                                    ),
                                )
                            },
                        ) { Text("从相册添加") }
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("video/*")) }) {
                            Text("从文件添加")
                        }
                    }
                    state.sources.forEach { source ->
                        SourceCard(
                            item = source,
                            canRemove = state.jobs.none {
                                it.sourceUri == source.media.sourceUri &&
                                    it.status == JobStatus.Running
                            },
                            onOpen = { trimMedia = source.media.takeUnless { source.probing } },
                            onRemove = { appViewModel.remove(source.media.sourceUri) },
                        )
                    }
                }
            }
            item {
                StepCard(number = "2", title = "选择格式与画质") {
                    val presets = listPresets()
                    PresetList(
                        presets = presets.filter { it.id in primaryPresetIds }
                            .sortedBy { primaryPresetIds.indexOf(it.id) },
                        selected = state.preset,
                        onSelect = appViewModel::setPreset,
                    )
                    if (showMore) {
                        PresetList(
                            presets = presets.filterNot { it.id in primaryPresetIds },
                            selected = state.preset,
                            onSelect = appViewModel::setPreset,
                        )
                    }
                    TextButton(onClick = { showMore = !showMore }) {
                        Text(if (showMore) "收起" else "更多")
                    }
                    OptionRow(
                        title = if (state.preset.startsWith("audio-")) "音质" else "画质",
                        options = listOf(
                            "original" to "原画",
                            "standard" to "标准",
                            "small" to "节省体积",
                        ),
                        selected = state.quality,
                        onSelect = appViewModel::setQuality,
                    )
                    if (shouldShowResolution(state.preset)) {
                        OptionRow(
                            title = "分辨率",
                            options = listOf(
                                "original" to "原尺寸",
                                "1080p" to "1080p",
                                "720p" to "720p",
                                "480p" to "480p",
                            ),
                            selected = state.size,
                            onSelect = appViewModel::setSize,
                        )
                    }
                }
            }
            item {
                StepCard(number = "3", title = "输出与任务") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("输出到", style = MaterialTheme.typography.labelLarge)
                            Text(outputLabel(context, state.output))
                        }
                        TextButton(onClick = { outputPicker.launch(null) }) { Text("更改") }
                    }
                    if (state.jobs.any { it.status !in setOf(JobStatus.Queued, JobStatus.Running) }) {
                        TextButton(onClick = appViewModel::clearFinished) { Text("清除已结束") }
                    }
                    state.jobs.forEach { job ->
                        JobCard(
                            job = job,
                            onCancel = { appViewModel.cancel(job.id) },
                            onRetry = { appViewModel.retry(job.id) },
                            onOpen = { launchOutput(context, appViewModel.outputIntent(job, false)) },
                            onShare = { launchOutput(context, appViewModel.outputIntent(job, true)) },
                        )
                    }
                }
            }
            state.message?.let { message ->
                item {
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(message, modifier = Modifier.weight(1f))
                            TextButton(onClick = appViewModel::clearMessage) { Text("知道了") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun StepCard(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$number. $title", style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SourceCard(
    item: SourceItem,
    canRemove: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !item.probing, onClick = onOpen),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.media.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    item.probing -> "正在读取格式…"
                    item.media.error != null -> item.media.error
                    else -> mediaSummary(item.media)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (canRemove) {
                TextButton(onClick = onRemove) { Text("移除") }
            }
        }
    }
}

@Composable
private fun PresetList(
    presets: List<PresetInfo>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    presets.forEach { preset ->
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onSelect(preset.id) },
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(preset.label, style = MaterialTheme.typography.titleSmall)
                    if (selected == preset.id) Text("已选择")
                }
                Text(preset.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            FilterChip(
                selected = selected == option.first,
                onClick = { onSelect(option.first) },
                label = { Text(option.second) },
            )
        }
    }
}

@Composable
private fun JobCard(
    job: Job,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.displayName, style = MaterialTheme.typography.titleSmall)
            Text(statusLabel(job.status))
            if (job.status == JobStatus.Queued || job.status == JobStatus.Running) {
                LinearProgressIndicator(
                    progress = { (job.progress / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onCancel) { Text("取消") }
            }
            if (job.status == JobStatus.Failed || job.status == JobStatus.Cancelled) {
                job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onRetry) { Text("重试") }
            }
            if (job.status == JobStatus.Completed) {
                Row {
                    TextButton(onClick = onOpen) { Text("打开") }
                    TextButton(onClick = onShare) { Text("分享") }
                }
            }
        }
    }
}

private fun mediaSummary(media: MediaInfo): String {
    val resolution = if (media.width != null && media.height != null) {
        "${media.width}×${media.height}"
    } else {
        "未知尺寸"
    }
    val fps = media.frameRate?.let { String.format(Locale.US, "%.2f fps", it) } ?: "未知 fps"
    val duration = media.durationSecs?.let { "${it.toInt()} 秒" } ?: "未知时长"
    return listOf(media.container, media.videoCodec, resolution, fps, duration)
        .filterNotNull()
        .joinToString(" · ")
}

private fun outputLabel(context: android.content.Context, output: OutputTarget): String = when (output.kind) {
    OutputTarget.Kind.Downloads -> "下载/轻转码"
    OutputTarget.Kind.SafTree -> output.treeUri
        ?.let(Uri::parse)
        ?.let { DocumentFile.fromTreeUri(context, it)?.name }
        ?: "所选文件夹"
    OutputTarget.Kind.AppExternal -> "应用输出目录"
}

private fun launchOutput(context: android.content.Context, intent: Intent?) {
    intent ?: return
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
