package com.videoconverter.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.documentSourceKind
import com.videoconverter.android.ui.theme.LightTokens

private val DOCUMENT_FORMAT_CHIPS = listOf(
    ChipOption("jpg", title = "JPG", hintRes = R.string.format_best_compat),
    ChipOption("png", title = "PNG", hintRes = R.string.format_lossless),
    ChipOption("webp", title = "WebP", hintRes = R.string.format_smaller),
)

private val COMPRESS_QUALITY_CHIPS = listOf(
    ChipOption("high", titleRes = R.string.quality_high, hintRes = R.string.quality_high_hint),
    ChipOption("standard", titleRes = R.string.quality_standard, hintRes = R.string.quality_standard_hint),
    ChipOption("small", titleRes = R.string.quality_smaller, hintRes = R.string.quality_smaller_hint),
)

private val QUALITY_CHIPS = listOf(
    ChipOption("original", titleRes = R.string.quality_original, hintRes = R.string.quality_original_hint),
    ChipOption("standard", titleRes = R.string.quality_standard, hintRes = R.string.quality_standard_hint),
    ChipOption("small", titleRes = R.string.quality_small, hintRes = R.string.quality_small_hint),
)

private val AUDIO_QUALITY_CHIPS = listOf(
    ChipOption("original", titleRes = R.string.quality_audio_high, hintRes = R.string.quality_audio_high_hint),
    ChipOption("standard", titleRes = R.string.quality_standard, hintRes = R.string.quality_standard_hint),
    ChipOption("small", titleRes = R.string.quality_audio_small, hintRes = R.string.quality_audio_small_hint),
)

private val SIZE_CHIPS = listOf(
    ChipOption("original", titleRes = R.string.size_original, hintRes = R.string.size_original_hint),
    ChipOption("1080p", title = "1080p", hintRes = R.string.size_1080p_hint),
    ChipOption("720p", title = "720p", hintRes = R.string.size_720p_hint),
    ChipOption("480p", title = "480p", hintRes = R.string.size_480p_hint),
)

@Composable
fun ConvertScreen(
    mode: ConvertMode,
    state: AppUiState,
    page: ConvertPage,
    showAll: Boolean,
    selectedUri: String?,
    preview: MediaInfo?,
    importable: Int,
    transcoding: Boolean,
    onShowAll: () -> Unit,
    onSelectUri: (String) -> Unit,
    onPage: (ConvertPage) -> Unit,
    onMode: (ConvertMode) -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onOutput: () -> Unit,
    onStart: () -> Unit,
    appViewModel: AppViewModel,
    onMusic: (() -> Unit)? = null,
) {
    val session = sessionFor(state.sessions(), mode)
    val audioMode = mode == ConvertMode.Audio
    val documentMode = mode == ConvertMode.Document
    val documentKind = documentKindOf(session.sources) ?: DocumentSourceKind.Image
    val displayedPreset = if (audioMode || documentMode) session.preset else coerceVideoPreset(session.preset)
    val context = LocalContext.current
    val probing = session.sources.any { it.probing }
    val startEnabled = importable > 0 && !transcoding && !probing && outputReadyToStart(session.output)
    Column(modifier = Modifier.fillMaxSize()) {
        if (page == ConvertPage.Home) {
            IosLargeTitle(
                title = stringResource(R.string.tab_convert),
                below = {
                    IosSegmented(
                        options = ConvertMode.entries.map { target ->
                            stringResource(convertModeLabelRes(target)) to (mode == target)
                        },
                        onSelect = { onMode(ConvertMode.entries[it]) },
                    )
                },
            )
        } else {
            IosNavBar(
                title = stringResource(
                    when (page) {
                        ConvertPage.Quality -> convertSettingTitleRes(ConvertSetting.Quality, displayedPreset)
                        else -> convertPageTitleRes(page)
                    },
                ),
                backLabel = stringResource(R.string.tab_convert),
                onBack = { onPage(ConvertPage.Home) },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        ) {
            when (page) {
                ConvertPage.Home -> {
                    if (preview != null) {
                        item(key = "preview-${preview.sourceUri}") {
                            IosSection {
                                Box(modifier = Modifier.padding(12.dp)) {
                                    if (documentMode) {
                                        DocumentSourcePreview(preview) { appViewModel.updateTrim(it, mode) }
                                    } else {
                                        TrimPanel(preview) { appViewModel.updateTrim(it, mode) }
                                    }
                                }
                            }
                        }
                    }
                    item("files") {
                        FilesSection(
                            session = session,
                            selectedUri = selectedUri ?: preview?.sourceUri,
                            documentMode = documentMode,
                            audioMode = audioMode,
                            runningUris = state.jobs.filter { it.status == JobStatus.Running }.map { it.sourceUri }.toSet(),
                            onSelectUri = onSelectUri,
                            onRemove = { appViewModel.remove(it, mode) },
                            onGallery = onGallery,
                            onFiles = onFiles,
                            onMusic = onMusic,
                        )
                    }
                    item("settings") {
                        val settings = convertSettingsFor(displayedPreset)
                        IosSection(title = stringResource(R.string.section_settings)) {
                            settings.forEachIndexed { index, setting ->
                                IosRow(
                                    title = stringResource(convertSettingTitleRes(setting, displayedPreset)),
                                    value = settingValue(
                                        setting = setting,
                                        preset = displayedPreset,
                                        quality = session.quality,
                                        size = session.size,
                                        output = outputFolderLabel(context, session.output),
                                        container = session.container,
                                    ),
                                    showDivider = index < settings.lastIndex,
                                    onClick = { onPage(convertPageFor(setting)) },
                                )
                            }
                        }
                    }
                    item("start") {
                        IosPrimaryButton(
                            label = stringResource(R.string.wizard_start_convert),
                            enabled = startEnabled,
                            onClick = onStart,
                        )
                    }
                }
                ConvertPage.Format -> {
                    item("formats") {
                        val cards = when {
                            audioMode -> AUDIO_PRESET_CARDS
                            documentMode -> documentCardsFor(documentKind)
                            else -> collapsedPresetCards(displayedPreset, showAll)
                        }
                        IosSection {
                            cards.forEachIndexed { index, card ->
                                IosRow(
                                    title = presetCardTitle(card),
                                    subtitle = presetCardHint(card),
                                    chevron = false,
                                    checked = displayedPreset == card.id,
                                    showDivider = index < cards.lastIndex || (!audioMode && !documentMode),
                                    onClick = { appViewModel.setPreset(card.id, mode) },
                                )
                            }
                            if (!audioMode && !documentMode) {
                                IosRow(
                                    title = stringResource(if (showAll) R.string.action_collapse else R.string.action_more),
                                    subtitle = stringResource(R.string.wizard_more_formats),
                                    chevron = false,
                                    accentTitle = true,
                                    showDivider = false,
                                    onClick = onShowAll,
                                )
                            }
                        }
                    }
                    formatHintRes(displayedPreset)?.let { hint ->
                        item("hint") {
                            Text(
                                stringResource(hint),
                                color = Color(LightTokens.Muted),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    if (displayedPreset == "pdf-image") {
                        item("container") {
                            IosSection(title = stringResource(R.string.format_image_title)) {
                                DOCUMENT_FORMAT_CHIPS.forEachIndexed { index, option ->
                                    IosRow(
                                        title = chipTitle(option),
                                        subtitle = chipHint(option),
                                        chevron = false,
                                        checked = (session.container ?: "jpg") == option.id,
                                        showDivider = index < DOCUMENT_FORMAT_CHIPS.lastIndex,
                                        onClick = { appViewModel.setContainer(option.id, mode) },
                                    )
                                }
                            }
                        }
                    }
                }
                ConvertPage.Quality -> {
                    item("quality") {
                        val options = qualityOptions(displayedPreset)
                        IosSection {
                            options.forEachIndexed { index, option ->
                                IosRow(
                                    title = chipTitle(option),
                                    subtitle = chipHint(option),
                                    chevron = false,
                                    checked = session.quality == option.id,
                                    showDivider = index < options.lastIndex,
                                    onClick = { appViewModel.setQuality(option.id, mode) },
                                )
                            }
                        }
                    }
                }
                ConvertPage.Size -> {
                    item("size") {
                        IosSection {
                            SIZE_CHIPS.forEachIndexed { index, option ->
                                IosRow(
                                    title = chipTitle(option),
                                    subtitle = chipHint(option),
                                    chevron = false,
                                    checked = session.size == option.id,
                                    showDivider = index < SIZE_CHIPS.lastIndex,
                                    onClick = { appViewModel.setSize(option.id, mode) },
                                )
                            }
                        }
                    }
                }
                ConvertPage.Output -> {
                    item("output") {
                        val cards = when {
                            audioMode -> AUDIO_OUTPUT_CHOICE_CARDS
                            documentMode -> outputChoicesForDocument(session.preset)
                            else -> OUTPUT_CHOICE_CARDS
                        }
                        val selectedId = outputChoiceId(session.output)
                        val customHint = if (
                            selectedId == OUTPUT_CHOICE_CUSTOM && !session.output.treeUri.isNullOrBlank()
                        ) {
                            outputFolderLabel(context, session.output)
                        } else {
                            null
                        }
                        IosSection {
                            cards.forEachIndexed { index, card ->
                                val selected = selectedId == card.id
                                val hint = when {
                                    card.id == OUTPUT_CHOICE_CUSTOM && selected && !customHint.isNullOrBlank() ->
                                        customHint
                                    card.id == OUTPUT_CHOICE_CUSTOM ->
                                        stringResource(
                                            customOutputHintRes(selected, hasFolder = !customHint.isNullOrBlank()),
                                        )
                                    else -> stringResource(card.hintRes)
                                }
                                IosRow(
                                    title = stringResource(card.titleRes),
                                    subtitle = hint,
                                    chevron = false,
                                    checked = selected,
                                    showDivider = index < cards.lastIndex,
                                    onClick = {
                                        if (card.id == OUTPUT_CHOICE_CUSTOM && customOutputTapOpensPicker(session.output)) {
                                            onOutput()
                                        } else {
                                            appViewModel.setOutputChoice(card.id, mode)
                                        }
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

@Composable
private fun FilesSection(
    session: WizardSession,
    selectedUri: String?,
    documentMode: Boolean,
    audioMode: Boolean,
    runningUris: Set<String>,
    onSelectUri: (String) -> Unit,
    onRemove: (String) -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onMusic: (() -> Unit)?,
) {
    val addRows = buildList {
        if (audioMode && onMusic != null) {
            add(Triple(stringResource(R.string.wizard_source_music), stringResource(R.string.wizard_source_music_hint), onMusic))
        }
        add(
            Triple(
                stringResource(R.string.wizard_source_gallery),
                stringResource(
                    if (documentMode) R.string.wizard_source_gallery_hint_image else R.string.wizard_source_gallery_hint_video,
                ),
                onGallery,
            ),
        )
        add(
            Triple(
                stringResource(R.string.wizard_source_files),
                stringResource(R.string.wizard_source_files_hint),
                onFiles,
            ),
        )
    }
    IosSection(
        title = stringResource(R.string.section_files),
        footer = if (session.sources.isEmpty()) {
            stringResource(
                when {
                    audioMode -> R.string.wizard_add_audio_hint
                    documentMode -> R.string.wizard_add_document_hint
                    else -> R.string.wizard_add_video_hint
                },
            )
        } else {
            null
        },
    ) {
        session.sources.forEachIndexed { index, source ->
            IosRow(
                title = source.media.displayName,
                subtitle = sourceFormatLine(LocalContext.current.resources, source.media, source.probing),
                chevron = false,
                accentTitle = source.media.sourceUri == selectedUri,
                trailing = if (source.media.sourceUri !in runningUris) {
                    {
                        Text(
                            stringResource(R.string.action_remove),
                            color = Color(LightTokens.Danger),
                            modifier = Modifier
                                .clickable { onRemove(source.media.sourceUri) }
                                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    null
                },
                showDivider = index < session.sources.lastIndex || addRows.isNotEmpty(),
                onClick = {
                    if (documentMode || itemHasDuration(source.media)) {
                        onSelectUri(source.media.sourceUri)
                    }
                },
            )
        }
        addRows.forEachIndexed { index, (title, hint, action) ->
            IosRow(
                title = title,
                subtitle = hint,
                accentTitle = true,
                showDivider = index < addRows.lastIndex,
                onClick = action,
            )
        }
    }
}

@Composable
private fun settingValue(
    setting: ConvertSetting,
    preset: String,
    quality: String,
    size: String,
    output: String,
    container: String?,
): String {
    val resources = LocalContext.current.resources
    return when (setting) {
        ConvertSetting.Format -> {
            if (preset == "pdf-image") {
                "${presetTitle(resources, preset)} · ${(container ?: "jpg").uppercase()}"
            } else {
                presetTitle(resources, preset)
            }
        }
        ConvertSetting.Quality -> resources.getString(qualityLabelRes(quality, isAudioPreset(preset)))
        ConvertSetting.Size -> sizeLabel(resources, size)
        ConvertSetting.Output -> output
    }
}

private fun qualityOptions(preset: String): List<ChipOption> = when {
    preset == "image-compress" || preset == "pdf-compress" -> COMPRESS_QUALITY_CHIPS
    isAudioPreset(preset) -> AUDIO_QUALITY_CHIPS
    else -> QUALITY_CHIPS
}

private fun formatHintRes(preset: String): Int? = when {
    preset == "office-pdf" -> R.string.hint_office_layout
    preset == "pdf-txt" -> R.string.hint_scan_no_text
    preset == "audio-flac" -> R.string.hint_flac
    preset == "audio-wav" -> R.string.hint_wav
    isCopyPreset(preset) -> R.string.hint_copy_mp4
    preset == "audio-amr" -> R.string.hint_amr
    else -> null
}
