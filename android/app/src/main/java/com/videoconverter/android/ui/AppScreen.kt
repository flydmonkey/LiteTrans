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
import androidx.compose.runtime.LaunchedEffect
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
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.documentSourceKind
import com.videoconverter.android.ui.theme.LightTokens

private val DOCUMENT_FILE_MIMES = arrayOf(
    "image/*",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

private val DOCUMENT_FORMAT_CHIPS = listOf(
    ChipOption("jpg", "JPG", "兼容性最好"),
    ChipOption("png", "PNG", "无损"),
    ChipOption("webp", "WebP", "体积更小"),
)

private val COMPRESS_QUALITY_CHIPS = listOf(
    ChipOption("high", "高", "尽量保留细节"),
    ChipOption("standard", "标准", "一般观看够用"),
    ChipOption("small", "更小", "文件更小"),
)

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
fun AppScreen(
    appViewModel: AppViewModel = viewModel(),
    openLanShare: Boolean = false,
) {
    val state by appViewModel.state.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(RootTab.Transcode) }
    var minePage by remember { mutableStateOf(MinePage.Root) }
    var step by remember { mutableStateOf(WizardStep.Sources) }
    var showAll by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    var audioStep by remember { mutableStateOf(WizardStep.Sources) }
    var audioShowAll by remember { mutableStateOf(false) }
    var audioSelectedUri by remember { mutableStateOf<String?>(null) }
    var documentStep by remember { mutableStateOf(WizardStep.Sources) }
    var documentShowAll by remember { mutableStateOf(false) }
    var documentSelectedUri by remember { mutableStateOf<String?>(null) }
    var historySegment by remember { mutableStateOf(HistorySegment.Video) }
    var pickerMode by remember { mutableStateOf(ConvertMode.Video) }
    var pendingStartMode by remember { mutableStateOf(ConvertMode.Video) }
    val videoImportable = state.video.sources.count { it.media.importable }
    val audioImportable = state.audio.sources.count { it.media.importable }
    val documentImportable = state.document.sources.count { it.media.importable }
    val transcoding = state.jobs.any { it.status == JobStatus.Queued || it.status == JobStatus.Running }
    val videoProbing = state.video.sources.any { it.probing }
    val audioProbing = state.audio.sources.any { it.probing }
    val documentProbing = state.document.sources.any { it.probing }
    val videoPreview = state.video.sources.firstOrNull { it.media.sourceUri == selectedUri }?.media
        ?: state.video.sources.firstOrNull { itemHasDuration(it.media) }?.media
    val audioPreview = state.audio.sources.firstOrNull { it.media.sourceUri == audioSelectedUri }?.media
        ?: state.audio.sources.firstOrNull { itemHasDuration(it.media) }?.media
    val documentPreview = state.document.sources.firstOrNull { it.media.sourceUri == documentSelectedUri }?.media
        ?: state.document.sources.firstOrNull {
            documentSourceKind(it.media.displayName) in setOf(DocumentSourceKind.Pdf, DocumentSourceKind.Image)
        }?.media
    val versionName = remember(context) { installedVersionName(context) }
    val audioMode = tab == RootTab.Audio
    val documentMode = tab == RootTab.Document
    val currentMode = when (tab) {
        RootTab.Audio -> ConvertMode.Audio
        RootTab.Document -> ConvertMode.Document
        else -> ConvertMode.Video
    }
    val currentStep = when (tab) {
        RootTab.Audio -> audioStep
        RootTab.Document -> documentStep
        else -> step
    }
    val currentSession = sessionFor(state.sessions(), currentMode)
    val currentImportable = when (tab) {
        RootTab.Audio -> audioImportable
        RootTab.Document -> documentImportable
        else -> videoImportable
    }
    val currentProbing = when (tab) {
        RootTab.Audio -> audioProbing
        RootTab.Document -> documentProbing
        else -> videoProbing
    }
    val currentPreview = when (tab) {
        RootTab.Audio -> audioPreview
        RootTab.Document -> documentPreview
        else -> videoPreview
    }

    LaunchedEffect(openLanShare) {
        if (openLanShare) {
            tab = RootTab.Mine
            minePage = MinePage.LanShare
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { appViewModel.addUris(it, pickerMode) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { appViewModel.addUris(it, pickerMode) }
    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { it?.let { uri -> appViewModel.pickOutputTree(uri, pickerMode) } }

    fun goToHistoryAndResetWizard(mode: ConvertMode) {
        val reset = resetWizardAfterStart()
        when (mode) {
            ConvertMode.Video -> {
                step = reset.step
                showAll = reset.showAll
                selectedUri = reset.selectedUri
            }
            ConvertMode.Audio -> {
                audioStep = reset.step
                audioShowAll = reset.showAll
                audioSelectedUri = reset.selectedUri
            }
            ConvertMode.Document -> {
                documentStep = reset.step
                documentShowAll = reset.showAll
                documentSelectedUri = reset.selectedUri
            }
        }
        appViewModel.clearSources(mode)
        val preset = sessionFor(state.sessions(), mode).preset
        historySegment = historySegmentAfterEnqueue(mode, preset)
        tab = RootTab.History
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (appViewModel.start(pendingStartMode)) goToHistoryAndResetWizard(pendingStartMode)
    }

    fun startWithNotificationPermission(mode: ConvertMode) {
        pendingStartMode = mode
        val preferences = context.getSharedPreferences("ui", 0)
        val firstRequest = !preferences.getBoolean("notificationAsked", false)
        if (Build.VERSION.SDK_INT >= 33 && firstRequest) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (appViewModel.start(mode)) {
            goToHistoryAndResetWizard(mode)
        }
    }

    val backTarget = consumeRootBack(tab, minePage, currentStep)
    BackHandler(enabled = backTarget != null) {
        consumeRootBack(tab, minePage, currentStep)?.let { next ->
            tab = next.tab
            minePage = next.minePage
            when (next.tab) {
                RootTab.Audio -> audioStep = next.wizardStep
                RootTab.Document -> documentStep = next.wizardStep
                else -> step = next.wizardStep
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(LightTokens.Canvas))
            .statusBarsPadding(),
    ) {
        if (tab != RootTab.Transcode && tab != RootTab.Audio && tab != RootTab.Document) {
            state.message?.let {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    NoticeBar(it, appViewModel::clearMessage)
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                RootTab.Transcode -> TranscodePane(
                    mode = ConvertMode.Video,
                    state = state,
                    step = step,
                    showAll = showAll,
                    selectedUri = selectedUri,
                    preview = videoPreview,
                    importable = videoImportable,
                    onShowAll = { showAll = !showAll },
                    onSelectUri = { selectedUri = it },
                    onStep = { target -> if (canEnterStep(target, videoImportable)) step = target },
                    onGallery = {
                        pickerMode = ConvertMode.Video
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    onFiles = {
                        pickerMode = ConvertMode.Video
                        filePicker.launch(arrayOf("video/*"))
                    },
                    onOutput = {
                        pickerMode = ConvertMode.Video
                        outputPicker.launch(null)
                    },
                    appViewModel = appViewModel,
                )
                RootTab.Audio -> TranscodePane(
                    mode = ConvertMode.Audio,
                    state = state,
                    step = audioStep,
                    showAll = audioShowAll,
                    selectedUri = audioSelectedUri,
                    preview = audioPreview,
                    importable = audioImportable,
                    onShowAll = { audioShowAll = !audioShowAll },
                    onSelectUri = { audioSelectedUri = it },
                    onStep = { target -> if (canEnterStep(target, audioImportable)) audioStep = target },
                    onGallery = {
                        pickerMode = ConvertMode.Audio
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    onFiles = {
                        pickerMode = ConvertMode.Audio
                        filePicker.launch(arrayOf("audio/*", "video/*"))
                    },
                    onMusic = {
                        pickerMode = ConvertMode.Audio
                        filePicker.launch(arrayOf("audio/*"))
                    },
                    onOutput = {
                        pickerMode = ConvertMode.Audio
                        outputPicker.launch(null)
                    },
                    appViewModel = appViewModel,
                )
                RootTab.Document -> TranscodePane(
                    mode = ConvertMode.Document,
                    state = state,
                    step = documentStep,
                    showAll = documentShowAll,
                    selectedUri = documentSelectedUri,
                    preview = documentPreview,
                    importable = documentImportable,
                    onShowAll = { documentShowAll = !documentShowAll },
                    onSelectUri = { documentSelectedUri = it },
                    onStep = { target -> if (canEnterStep(target, documentImportable)) documentStep = target },
                    onGallery = {
                        pickerMode = ConvertMode.Document
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onFiles = {
                        pickerMode = ConvertMode.Document
                        filePicker.launch(DOCUMENT_FILE_MIMES)
                    },
                    onOutput = {
                        pickerMode = ConvertMode.Document
                        outputPicker.launch(null)
                    },
                    appViewModel = appViewModel,
                )
                RootTab.History -> HistoryScreen(
                    segment = historySegment,
                    onSegment = { historySegment = it },
                    jobs = historyJobs(state.jobs, historySegment),
                    emptyLabel = historyEmptyLabel(historySegment),
                    onCancel = appViewModel::cancel,
                    onRetry = appViewModel::retry,
                    onOpen = { launchOutput(context, appViewModel.outputIntent(it, false)) },
                    onShare = { launchOutput(context, appViewModel.outputIntent(it, true)) },
                    onRename = { job, name -> appViewModel.rename(job.id, name) },
                    onDelete = appViewModel::delete,
                )
                RootTab.Mine -> if (minePage == MinePage.LanShare) {
                    LanShareScreen(onBack = { minePage = MinePage.Root })
                } else {
                    MineScreen(
                        page = minePage,
                        versionName = versionName,
                        onOpen = { minePage = it },
                        onBack = { minePage = MinePage.Root },
                    )
                }
            }
        }
        if ((tab == RootTab.Transcode || tab == RootTab.Audio || tab == RootTab.Document) &&
            currentStep == WizardStep.Format
        ) {
            FormatDetailPanel(
                preset = currentSession.preset,
                quality = currentSession.quality,
                size = currentSession.size,
                container = currentSession.container,
                onQuality = { appViewModel.setQuality(it, currentMode) },
                onSize = { appViewModel.setSize(it, currentMode) },
                onContainer = { appViewModel.setContainer(it, currentMode) },
            )
        }
        if (tab == RootTab.Transcode || tab == RootTab.Audio || tab == RootTab.Document) {
            WizardDock(
                step = currentStep,
                summary = dockSummary(
                    step = currentStep,
                    importableCount = currentImportable,
                    presetTitle = presetTitle(currentSession.preset),
                    qualityLabel = qualityLabel(currentSession.quality),
                    sizeLabel = sizeLabel(currentSession.size),
                    audioOnly = isAudioPreset(currentSession.preset),
                    copyOnly = isCopyPreset(currentSession.preset),
                    trimLabel = trimLabel(currentSession.sources.map { it.media }, currentPreview),
                    outputLabel = outputFolderLabel(context, currentSession.output),
                    formatPreview = conversionPreview(
                        currentSession.sources.map { it.media },
                        presetTitle(currentSession.preset),
                    ),
                    audioMode = audioMode,
                    losslessAudio = isLosslessAudioPreset(currentSession.preset),
                    documentMode = documentMode,
                    pageRangeLabel = pageRangeLabel(currentSession.sources.map { it.media }),
                ),
                action = dockActionLabel(
                    currentStep,
                    busy = false,
                    transcoding = transcoding,
                    startLabel = if (audioMode || documentMode) "开始转换" else "开始转码",
                ),
                actionEnabled = when (currentStep) {
                    WizardStep.Sources, WizardStep.Format -> currentImportable > 0 && !currentProbing
                    WizardStep.Output -> currentImportable > 0 && !transcoding && !currentProbing &&
                        !(currentSession.output.kind == OutputTarget.Kind.SafTree &&
                            currentSession.output.treeUri == null)
                },
                onBack = {
                    retreatStep(currentStep)?.let { next ->
                        when {
                            audioMode -> audioStep = next
                            documentMode -> documentStep = next
                            else -> step = next
                        }
                    }
                },
                onAction = {
                    when (currentStep) {
                        WizardStep.Sources, WizardStep.Format ->
                            advanceStep(currentStep, currentImportable)?.let { next ->
                                when {
                                    audioMode -> audioStep = next
                                    documentMode -> documentStep = next
                                    else -> step = next
                                }
                            }
                        WizardStep.Output -> startWithNotificationPermission(currentMode)
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
    mode: ConvertMode,
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
    onMusic: (() -> Unit)? = null,
) {
    val session = sessionFor(state.sessions(), mode)
    val audioMode = mode == ConvertMode.Audio
    val documentMode = mode == ConvertMode.Document
    val documentKind = documentKindOf(session.sources) ?: DocumentSourceKind.Image
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = wizardScreenTitle(step),
            subtitle = when (step) {
                WizardStep.Sources -> if (documentMode) "预览，PDF 可选择页范围" else "预览并裁切要保留的片段"
                WizardStep.Format -> conversionPreview(
                    session.sources.map { it.media },
                    presetTitle(session.preset),
                )
                WizardStep.Output -> outputFolderLabel(LocalContext.current, session.output)
            },
            below = { StepTabs(step, importable, onStep) },
        )
        state.message?.let {
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                NoticeBar(it, appViewModel::clearMessage)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (step == WizardStep.Sources && session.sources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Dropzone(
                        onGallery = onGallery,
                        onFiles = onFiles,
                        onMusic = onMusic,
                        centered = true,
                        audioMode = audioMode,
                        documentMode = documentMode,
                    )
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
                            item {
                                Dropzone(
                                    onGallery = onGallery,
                                    onFiles = onFiles,
                                    onMusic = onMusic,
                                    audioMode = audioMode,
                                    documentMode = documentMode,
                                )
                            }
                            items(session.sources, key = { it.media.sourceUri }) { source ->
                                FileRow(
                                    name = source.media.displayName,
                                    line = sourceFormatLine(source.media, source.probing),
                                    selected = source.media.sourceUri == (selectedUri ?: preview?.sourceUri),
                                    importable = source.media.importable || source.probing,
                                    canRemove = state.jobs.none {
                                        it.sourceUri == source.media.sourceUri && it.status == JobStatus.Running
                                    },
                                    onOpen = {
                                        if (documentMode || itemHasDuration(source.media)) {
                                            onSelectUri(source.media.sourceUri)
                                        }
                                    },
                                    onRemove = { appViewModel.remove(source.media.sourceUri, mode) },
                                )
                            }
                            if (documentMode && preview != null) {
                                item(key = "document-${preview.sourceUri}") {
                                    DocumentSourcePreview(preview) { appViewModel.updateTrim(it, mode) }
                                }
                            } else if (!documentMode && preview != null) {
                                item(key = "trim-${preview.sourceUri}") {
                                    TrimPanel(preview) { appViewModel.updateTrim(it, mode) }
                                }
                            }
                        }
                        WizardStep.Format -> {
                            item {
                                PresetGrid(
                                    cards = when {
                                        audioMode -> AUDIO_PRESET_CARDS
                                        documentMode -> documentCardsFor(documentKind)
                                        else -> collapsedPresetCards(session.preset, showAll)
                                    },
                                    selected = session.preset,
                                    showAll = showAll,
                                    showMore = !audioMode && !documentMode,
                                    onSelect = { appViewModel.setPreset(it, mode) },
                                    onToggleMore = onShowAll,
                                )
                            }
                        }
                        WizardStep.Output -> {
                            item {
                                OutputChoiceGrid(
                                    cards = when {
                                        audioMode -> AUDIO_OUTPUT_CHOICE_CARDS
                                        documentMode -> outputChoicesForDocument(session.preset)
                                        else -> OUTPUT_CHOICE_CARDS
                                    },
                                    selectedId = outputChoiceId(session.output),
                                    customHint = if (outputChoiceId(session.output) == OUTPUT_CHOICE_CUSTOM) {
                                        outputFolderLabel(LocalContext.current, session.output)
                                    } else {
                                        null
                                    },
                                    onSelect = { id ->
                                        if (id == OUTPUT_CHOICE_CUSTOM) onOutput()
                                        else appViewModel.setOutputChoice(id, mode)
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

private fun pageRangeLabel(sources: List<MediaInfo>): String {
    val pdfs = sources.filter {
        it.importable && documentSourceKind(it.displayName) == DocumentSourceKind.Pdf
    }
    if (pdfs.isEmpty()) return ""
    if (pdfs.size == 1) {
        val media = pdfs.first()
        val start = media.pageStart ?: 1
        val end = media.pageEnd ?: media.pageCount ?: start
        return " · 第 $start–$end 页"
    }
    return " · ${pdfs.size} 个文件已选页"
}

@Composable
private fun FormatDetailPanel(
    preset: String,
    quality: String,
    size: String,
    container: String?,
    onQuality: (String) -> Unit,
    onSize: (String) -> Unit,
    onContainer: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(LightTokens.Canvas))
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            preset == "office-pdf" -> {
                Text(
                    "简单文字和表格可以，复杂排版会对不齐",
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(LightTokens.Card))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            preset == "pdf-txt" -> {
                Text(
                    "扫描件抽不出字",
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(LightTokens.Card))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            preset == "pdf-image" -> {
                CompactChips(
                    title = "图片格式",
                    options = DOCUMENT_FORMAT_CHIPS,
                    selected = container ?: "jpg",
                    onSelect = onContainer,
                )
            }
            preset == "image-compress" || preset == "pdf-compress" -> {
                CompactChips(
                    title = "压缩",
                    options = COMPRESS_QUALITY_CHIPS,
                    selected = quality,
                    onSelect = onQuality,
                )
            }
            isLosslessAudioPreset(preset) -> {
                Text(
                    if (preset == "audio-flac") "无损压缩，比 WAV 小很多，播放器支持也广。"
                    else "原始采样，不压缩，文件更大",
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(LightTokens.Card))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            isCopyPreset(preset) -> {
                Text(
                    "不重编码只换文件外壳，画质和分辨率都保持原样。源视频编码必须能放进 MP4，不行的文件会提示改用普通转码。",
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(LightTokens.Card))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            preset.startsWith("image-") || preset.startsWith("pdf-") -> Unit
            else -> {
                if (preset == "audio-amr") {
                    Text(
                        "通话录音常用。会转成 8kHz 单声道，适合语音，不适合音乐。",
                        color = Color(LightTokens.Muted),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(LightTokens.Card))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
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
