package com.videoconverter.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoconverter.android.R
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.documentSourceKind

private val DOCUMENT_FILE_MIMES = arrayOf(
    "image/*",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

@Composable
fun AppScreen(
    appViewModel: AppViewModel = viewModel(),
    openLanShare: Boolean = false,
    onOpenLanShareConsumed: () -> Unit = {},
) {
    val state by appViewModel.state.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(RootTab.Convert) }
    var minePage by rememberSaveable { mutableStateOf(MinePage.Root) }
    var convertMode by rememberSaveable { mutableStateOf(ConvertMode.Video) }
    var convertPage by rememberSaveable { mutableStateOf(ConvertPage.Home) }
    var showAll by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    var audioPage by rememberSaveable { mutableStateOf(ConvertPage.Home) }
    var audioShowAll by remember { mutableStateOf(false) }
    var audioSelectedUri by remember { mutableStateOf<String?>(null) }
    var documentPage by rememberSaveable { mutableStateOf(ConvertPage.Home) }
    var documentShowAll by remember { mutableStateOf(false) }
    var documentSelectedUri by remember { mutableStateOf<String?>(null) }
    var historySegment by remember { mutableStateOf(HistorySegment.Video) }
    var pickerMode by remember { mutableStateOf(ConvertMode.Video) }
    var pendingStartMode by remember { mutableStateOf(ConvertMode.Video) }
    val transcoding = state.jobs.any { it.status == JobStatus.Queued || it.status == JobStatus.Running }
    val videoPreview = state.video.sources.firstOrNull { it.media.sourceUri == selectedUri }?.media
        ?: state.video.sources.firstOrNull { itemHasDuration(it.media) }?.media
    val audioPreview = state.audio.sources.firstOrNull { it.media.sourceUri == audioSelectedUri }?.media
        ?: state.audio.sources.firstOrNull { itemHasDuration(it.media) }?.media
    val documentPreview = state.document.sources.firstOrNull { it.media.sourceUri == documentSelectedUri }?.media
        ?: state.document.sources.firstOrNull {
            documentSourceKind(it.media.displayName) in setOf(DocumentSourceKind.Pdf, DocumentSourceKind.Image)
        }?.media
    val versionName = remember(context) { installedVersionName(context) }
    val currentMode = convertMode
    val currentPage = when (convertMode) {
        ConvertMode.Audio -> audioPage
        ConvertMode.Document -> documentPage
        ConvertMode.Video -> convertPage
    }
    val currentImportable = when (convertMode) {
        ConvertMode.Audio -> state.audio.sources.count { it.media.importable }
        ConvertMode.Document -> state.document.sources.count { it.media.importable }
        ConvertMode.Video -> state.video.sources.count { it.media.importable }
    }
    val currentPreview = when (convertMode) {
        ConvertMode.Audio -> audioPreview
        ConvertMode.Document -> documentPreview
        ConvertMode.Video -> videoPreview
    }

    LaunchedEffect(openLanShare) {
        if (openLanShare) {
            tab = RootTab.Mine
            minePage = MinePage.LanShare
            onOpenLanShareConsumed()
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
                convertPage = ConvertPage.Home
                showAll = reset.showAll
                selectedUri = reset.selectedUri
            }
            ConvertMode.Audio -> {
                audioPage = ConvertPage.Home
                audioShowAll = reset.showAll
                audioSelectedUri = reset.selectedUri
            }
            ConvertMode.Document -> {
                documentPage = ConvertPage.Home
                documentShowAll = reset.showAll
                documentSelectedUri = reset.selectedUri
            }
        }
        if (reset.clearSources) appViewModel.clearSources(mode)
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

    val backTarget = consumeRootBack(tab, minePage, currentPage)
    BackHandler(enabled = backTarget != null) {
        consumeRootBack(tab, minePage, currentPage)?.let { next ->
            tab = next.tab
            minePage = next.minePage
            if (next.tab == RootTab.Convert) {
                when (convertMode) {
                    ConvertMode.Audio -> audioPage = next.convertPage
                    ConvertMode.Document -> documentPage = next.convertPage
                    ConvertMode.Video -> convertPage = next.convertPage
                }
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            RootNavigationBar(
                selected = tab,
                activeHistoryCount = historyActiveCount(state.jobs),
                onSelect = { next ->
                    minePage = minePageAfterLeavingTab(next, minePage)
                    tab = next
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            state.message?.let { MessageBanner(it, appViewModel::clearMessage) }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    RootTab.Convert -> ConvertScreen(
                    mode = convertMode,
                    state = state,
                    page = currentPage,
                    showAll = when (convertMode) {
                        ConvertMode.Audio -> audioShowAll
                        ConvertMode.Document -> documentShowAll
                        ConvertMode.Video -> showAll
                    },
                    selectedUri = when (convertMode) {
                        ConvertMode.Audio -> audioSelectedUri
                        ConvertMode.Document -> documentSelectedUri
                        ConvertMode.Video -> selectedUri
                    },
                    preview = currentPreview,
                    importable = currentImportable,
                    transcoding = transcoding,
                    onShowAll = {
                        when (convertMode) {
                            ConvertMode.Audio -> audioShowAll = !audioShowAll
                            ConvertMode.Document -> documentShowAll = !documentShowAll
                            ConvertMode.Video -> showAll = !showAll
                        }
                    },
                    onSelectUri = { uri ->
                        when (convertMode) {
                            ConvertMode.Audio -> audioSelectedUri = uri
                            ConvertMode.Document -> documentSelectedUri = uri
                            ConvertMode.Video -> selectedUri = uri
                        }
                    },
                    onPage = { target ->
                        when (convertMode) {
                            ConvertMode.Audio -> audioPage = target
                            ConvertMode.Document -> documentPage = target
                            ConvertMode.Video -> convertPage = target
                        }
                    },
                    onMode = { convertMode = it },
                    onGallery = {
                        pickerMode = convertMode
                        galleryPicker.launch(
                            PickVisualMediaRequest(
                                if (convertMode == ConvertMode.Document) {
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                } else {
                                    ActivityResultContracts.PickVisualMedia.VideoOnly
                                },
                            ),
                        )
                    },
                    onFiles = {
                        pickerMode = convertMode
                        filePicker.launch(
                            when (convertMode) {
                                ConvertMode.Audio -> arrayOf("audio/*", "video/*")
                                ConvertMode.Document -> DOCUMENT_FILE_MIMES
                                ConvertMode.Video -> arrayOf("video/*")
                            },
                        )
                    },
                    onMusic = if (convertMode == ConvertMode.Audio) {
                        {
                            pickerMode = ConvertMode.Audio
                            filePicker.launch(arrayOf("audio/*"))
                        }
                    } else {
                        null
                    },
                    onOutput = {
                        pickerMode = convertMode
                        outputPicker.launch(null)
                    },
                    onStart = { startWithNotificationPermission(currentMode) },
                    appViewModel = appViewModel,
                )
                RootTab.History -> HistoryScreen(
                    segment = historySegment,
                    onSegment = { historySegment = it },
                    jobs = historyJobs(state.jobs, historySegment),
                    emptyLabel = stringResource(historyEmptyLabelRes(historySegment)),
                    onCancel = appViewModel::cancel,
                    onRetry = appViewModel::retry,
                    onOpen = { launchOutput(context, appViewModel.outputIntent(it, false)) },
                    onShare = { launchOutput(context, appViewModel.outputIntent(it, true)) },
                    onRename = { job, name -> appViewModel.rename(job.id, name) },
                    onDelete = appViewModel::delete,
                    onClearFinished = { appViewModel.clearFinished(historySegment) },
                    onConvertAgain = {
                        convertMode = convertModeForHistorySegment(historySegment)
                        tab = RootTab.Convert
                    },
                )
                RootTab.Mine -> when (minePage) {
                    MinePage.LanShare -> LanShareScreen(onBack = { minePage = MinePage.Root })
                    MinePage.Language -> LanguageScreen(onBack = { minePage = MinePage.Root })
                    else -> MineScreen(
                        page = minePage,
                        versionName = versionName,
                        onOpen = { minePage = it },
                        onBack = { minePage = MinePage.Root },
                    )
                }
            }
        }
        }
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
