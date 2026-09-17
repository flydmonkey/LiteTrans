package com.videoconverter.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.ui.theme.LightTokens

private val QUALITY_CHIPS = listOf(
    ChipOption("original", "原画", "尽量保留细节"),
    ChipOption("standard", "标准", "一般观看够用"),
    ChipOption("small", "节省体积", "文件更小，会糊一点"),
)

private val SIZE_CHIPS = listOf(
    ChipOption("original", "原尺寸", "不缩小画面"),
    ChipOption("1080p", "1080p", "全高清"),
    ChipOption("720p", "720p", "高清"),
    ChipOption("480p", "480p", "更小画面"),
)

@Composable
fun AppScreen(appViewModel: AppViewModel = viewModel()) {
    val state by appViewModel.state.collectAsState()
    val context = LocalContext.current
    var step by remember { mutableStateOf(WizardStep.Sources) }
    var showAll by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    val importable = state.sources.count { it.media.importable }
    val transcoding = state.jobs.any { it.status == JobStatus.Queued || it.status == JobStatus.Running }
    val probing = state.sources.any { it.probing }
    val preview = state.sources.firstOrNull { it.media.sourceUri == selectedUri }?.media
        ?: state.sources.firstOrNull { itemHasDuration(it.media) }?.media

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

    BackHandler(enabled = step != WizardStep.Sources) {
        retreatStep(step)?.let { step = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(LightTokens.Canvas))
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WizardHeader()
            StepTabs(step, importable) { target ->
                if (canEnterStep(target, importable)) step = target
            }
            state.message?.let { NoticeBar(it, appViewModel::clearMessage) }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
        ) {
            when (step) {
                WizardStep.Sources -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "添加文件",
                                color = Color(LightTokens.Ink),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "预览并裁切要保留的片段",
                                color = Color(LightTokens.Muted),
                            )
                        }
                    }
                    item {
                        Dropzone(
                            onGallery = {
                                galleryPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.VideoOnly,
                                    ),
                                )
                            },
                            onFiles = { filePicker.launch(arrayOf("video/*")) },
                        )
                    }
                    items(state.sources, key = { it.media.sourceUri }) { source ->
                        FileRow(
                            name = source.media.displayName,
                            line = sourceFormatLine(source.media, source.probing),
                            selected = source.media.sourceUri == (selectedUri ?: preview?.sourceUri),
                            importable = source.media.importable || source.probing,
                            canRemove = state.jobs.none {
                                it.sourceUri == source.media.sourceUri && it.status == JobStatus.Running
                            },
                            onOpen = {
                                if (itemHasDuration(source.media)) selectedUri = source.media.sourceUri
                            },
                            onRemove = { appViewModel.remove(source.media.sourceUri) },
                        )
                    }
                    if (preview != null) {
                        item(key = "trim-${preview.sourceUri}") {
                            TrimPanel(preview, appViewModel::updateTrim)
                        }
                    }
                }
                WizardStep.Format -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "转成",
                                color = Color(LightTokens.Ink),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                conversionPreview(state.sources.map { it.media }, presetTitle(state.preset)),
                                color = Color(LightTokens.Muted),
                            )
                        }
                    }
                    item {
                        PresetGrid(
                            cards = collapsedPresetCards(state.preset, showAll),
                            selected = state.preset,
                            showAll = showAll,
                            onSelect = appViewModel::setPreset,
                            onToggleMore = { showAll = !showAll },
                        )
                    }
                    if (isCopyPreset(state.preset)) {
                        item {
                            Text(
                                "不重编码只换文件外壳，画质和分辨率都保持原样。源视频编码必须能放进 MP4，不行的文件会提示改用普通转码。",
                                color = Color(LightTokens.Muted),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(LightTokens.Card), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    } else {
                        item {
                            OptionChips(
                                title = if (isAudioPreset(state.preset)) "音质" else "画质",
                                description = if (isAudioPreset(state.preset)) {
                                    "声音保留多少，和画面大小无关"
                                } else {
                                    "画质管「压得紧不紧」，分辨率管「画面有多大」。可以原画 + 1080p：画面缩小，细节尽量留着。"
                                },
                                options = QUALITY_CHIPS,
                                selected = state.quality,
                                onSelect = appViewModel::setQuality,
                            )
                        }
                        if (shouldShowResolution(state.preset)) {
                            item {
                                OptionChips(
                                    title = "分辨率",
                                    description = "画面有多少像素。原尺寸就是不缩小。",
                                    options = SIZE_CHIPS,
                                    selected = state.size,
                                    onSelect = appViewModel::setSize,
                                )
                            }
                        }
                    }
                }
                WizardStep.Output -> {
                    item {
                        OutputBar(
                            label = outputFolderLabel(context, state.output),
                            onChange = { outputPicker.launch(null) },
                        )
                    }
                    if (state.jobs.any { it.status !in setOf(JobStatus.Queued, JobStatus.Running) }) {
                        item {
                            Text(
                                "清空已完成",
                                color = Color(LightTokens.Accent),
                                modifier = Modifier.clickable(onClick = appViewModel::clearFinished),
                            )
                        }
                    }
                    items(state.jobs, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            onCancel = { appViewModel.cancel(job.id) },
                            onRetry = { appViewModel.retry(job.id) },
                            onOpen = { launchOutput(context, appViewModel.outputIntent(job, false)) },
                            onShare = { launchOutput(context, appViewModel.outputIntent(job, true)) },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            WizardDock(
                step = step,
                summary = dockSummary(
                    step = step,
                    importableCount = importable,
                    presetTitle = presetTitle(state.preset),
                    qualityLabel = qualityLabel(state.quality),
                    sizeLabel = sizeLabel(state.size),
                    audioOnly = isAudioPreset(state.preset),
                    copyOnly = isCopyPreset(state.preset),
                    trimLabel = trimLabel(state.sources.map { it.media }, preview),
                    outputLabel = outputFolderLabel(context, state.output),
                    formatPreview = conversionPreview(
                        state.sources.map { it.media },
                        presetTitle(state.preset),
                    ),
                ),
                action = dockActionLabel(step, busy = false, transcoding = transcoding),
                actionEnabled = when (step) {
                    WizardStep.Sources, WizardStep.Format -> importable > 0 && !probing
                    WizardStep.Output -> importable > 0 && !transcoding && !probing
                },
                onBack = { retreatStep(step)?.let { step = it } },
                onAction = {
                    when (step) {
                        WizardStep.Sources, WizardStep.Format ->
                            advanceStep(step, importable)?.let { step = it }
                        WizardStep.Output -> startWithNotificationPermission()
                    }
                },
            )
        }
    }
}

private fun trimLabel(sources: List<MediaInfo>, preview: MediaInfo?): String {
    val trimmed = sources.filter(::isTrimmed)
    if (trimmed.isEmpty()) return ""
    return if (trimmed.size == 1 && preview != null && isTrimmed(preview)) {
        val duration = preview.durationSecs ?: 0.0
        " · 裁 ${formatClock(preview.trimStartSecs ?: 0.0)}–${formatClock(preview.trimEndSecs ?: duration)}"
    } else {
        " · ${trimmed.size} 个文件已裁剪"
    }
}

private fun launchOutput(context: android.content.Context, intent: Intent?) {
    intent ?: return
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
