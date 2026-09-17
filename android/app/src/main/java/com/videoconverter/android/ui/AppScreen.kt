package com.videoconverter.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var tab by remember { mutableStateOf(RootTab.Transcode) }
    var minePage by remember { mutableStateOf(MinePage.Root) }
    var step by remember { mutableStateOf(WizardStep.Sources) }
    var showAll by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    val importable = state.sources.count { it.media.importable }
    val transcoding = state.jobs.any { it.status == JobStatus.Queued || it.status == JobStatus.Running }
    val probing = state.sources.any { it.probing }
    val preview = state.sources.firstOrNull { it.media.sourceUri == selectedUri }?.media
        ?: state.sources.firstOrNull { itemHasDuration(it.media) }?.media
    val versionName = remember(context) { installedVersionName(context) }

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

    fun goToHistoryAndResetWizard() {
        val reset = resetWizardAfterStart()
        step = reset.step
        showAll = reset.showAll
        selectedUri = reset.selectedUri
        appViewModel.clearSources()
        tab = RootTab.History
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (appViewModel.start()) goToHistoryAndResetWizard()
    }

    fun startWithNotificationPermission() {
        val preferences = context.getSharedPreferences("ui", 0)
        val firstRequest = !preferences.getBoolean("notificationAsked", false)
        if (Build.VERSION.SDK_INT >= 33 && firstRequest) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (appViewModel.start()) {
            goToHistoryAndResetWizard()
        }
    }

    val backTarget = consumeRootBack(tab, minePage, step)
    BackHandler(enabled = backTarget != null) {
        consumeRootBack(tab, minePage, step)?.let { next ->
            tab = next.tab
            minePage = next.minePage
            step = next.wizardStep
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(LightTokens.Canvas))
            .statusBarsPadding(),
    ) {
        if (tab != RootTab.Transcode) {
            state.message?.let {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    NoticeBar(it, appViewModel::clearMessage)
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                RootTab.Transcode -> TranscodePane(
                    state = state,
                    step = step,
                    showAll = showAll,
                    selectedUri = selectedUri,
                    preview = preview,
                    importable = importable,
                    onShowAll = { showAll = !showAll },
                    onSelectUri = { selectedUri = it },
                    onStep = { target -> if (canEnterStep(target, importable)) step = target },
                    onGallery = {
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    onFiles = { filePicker.launch(arrayOf("video/*")) },
                    onOutput = { outputPicker.launch(null) },
                    appViewModel = appViewModel,
                )
                RootTab.History -> HistoryScreen(
                    jobs = state.jobs,
                    onCancel = appViewModel::cancel,
                    onRetry = appViewModel::retry,
                    onOpen = { launchOutput(context, appViewModel.outputIntent(it, false)) },
                    onShare = { launchOutput(context, appViewModel.outputIntent(it, true)) },
                    onRename = { job, name -> appViewModel.rename(job.id, name) },
                    onDelete = appViewModel::delete,
                    onClearFinished = appViewModel::clearFinished,
                )
                RootTab.Mine -> MineScreen(
                    page = minePage,
                    versionName = versionName,
                    onOpen = { minePage = it },
                    onBack = { minePage = MinePage.Root },
                )
            }
        }
        if (tab == RootTab.Transcode && step == WizardStep.Format) {
            FormatDetailPanel(
                preset = state.preset,
                quality = state.quality,
                size = state.size,
                onQuality = appViewModel::setQuality,
                onSize = appViewModel::setSize,
            )
        }
        if (tab == RootTab.Transcode) {
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
        RootTabBar(selected = tab) { next ->
            minePage = minePageAfterLeavingTab(next, minePage)
            tab = next
        }
    }
}

@Composable
private fun TranscodePane(
    state: AppUiState,
    step: WizardStep,
    showAll: Boolean,
    selectedUri: String?,
    preview: MediaInfo?,
    importable: Int,
    onShowAll: () -> Unit,
    onSelectUri: (String) -> Unit,
    onStep: (WizardStep) -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onOutput: () -> Unit,
    appViewModel: AppViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = wizardScreenTitle(step),
            subtitle = when (step) {
                WizardStep.Sources -> "预览并裁切要保留的片段"
                WizardStep.Format -> conversionPreview(
                    state.sources.map { it.media },
                    presetTitle(state.preset),
                )
                WizardStep.Output -> outputFolderLabel(LocalContext.current, state.output)
            },
            below = { StepTabs(step, importable, onStep) },
        )
        state.message?.let {
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                NoticeBar(it, appViewModel::clearMessage)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (step == WizardStep.Sources && state.sources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Dropzone(onGallery = onGallery, onFiles = onFiles, centered = true)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp),
                ) {
                    when (step) {
                        WizardStep.Sources -> {
                            item { Dropzone(onGallery = onGallery, onFiles = onFiles) }
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
                                        if (itemHasDuration(source.media)) onSelectUri(source.media.sourceUri)
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
                                PresetGrid(
                                    cards = collapsedPresetCards(state.preset, showAll),
                                    selected = state.preset,
                                    showAll = showAll,
                                    onSelect = appViewModel::setPreset,
                                    onToggleMore = onShowAll,
                                )
                            }
                        }
                        WizardStep.Output -> {
                            item {
                                OutputChoiceGrid(
                                    selectedId = outputChoiceId(state.output),
                                    customHint = if (outputChoiceId(state.output) == OUTPUT_CHOICE_CUSTOM) {
                                        outputFolderLabel(LocalContext.current, state.output)
                                    } else {
                                        null
                                    },
                                    onSelect = { id ->
                                        if (id == OUTPUT_CHOICE_CUSTOM) onOutput()
                                        else appViewModel.setOutputChoice(id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
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

private fun installedVersionName(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0.1.0" }

@Composable
private fun FormatDetailPanel(
    preset: String,
    quality: String,
    size: String,
    onQuality: (String) -> Unit,
    onSize: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(LightTokens.Canvas))
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isCopyPreset(preset)) {
            Text(
                "不重编码只换文件外壳，画质和分辨率都保持原样。源视频编码必须能放进 MP4，不行的文件会提示改用普通转码。",
                color = Color(LightTokens.Muted),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(LightTokens.Card))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            CompactChips(
                title = if (isAudioPreset(preset)) "音质" else "画质",
                options = QUALITY_CHIPS,
                selected = quality,
                onSelect = onQuality,
            )
            if (shouldShowResolution(preset)) {
                CompactChips(
                    title = "分辨率",
                    options = SIZE_CHIPS,
                    selected = size,
                    onSelect = onSize,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactChips(
    title: String,
    options: List<ChipOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(LightTokens.Ink), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val on = selected == option.id
                Text(
                    option.title,
                    color = if (on) Color(LightTokens.OnDark) else Color(LightTokens.Ink),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color(LightTokens.Ink) else Color.White)
                        .border(
                            1.dp,
                            if (on) Color(LightTokens.Ink) else Color(LightTokens.Border),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(option.id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
