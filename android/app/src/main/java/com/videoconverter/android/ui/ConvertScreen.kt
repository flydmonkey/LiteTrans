package com.videoconverter.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo

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
            AppTopBar(title = stringResource(R.string.tab_convert))
            ConvertModeTabs(selected = mode, onSelect = onMode)
        } else {
            AppTopBar(
                title = stringResource(
                    when (page) {
                        ConvertPage.Quality -> convertSettingTitleRes(ConvertSetting.Quality, displayedPreset)
                        else -> convertPageTitleRes(page)
                    },
                ),
                onBack = { onPage(ConvertPage.Home) },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            when (page) {
                ConvertPage.Home -> {
                    if (preview != null) {
                        item(key = "preview-${preview.sourceUri}") {
                            AppCard {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                        AppCard {
                            settings.forEachIndexed { index, setting ->
                                SettingRow(
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
                }
                ConvertPage.Format -> {
                    item("formats") {
                        val cards = when {
                            audioMode -> AUDIO_PRESET_CARDS
                            documentMode -> documentCardsFor(documentKind)
                            else -> collapsedPresetCards(displayedPreset, showAll)
                        }
                        AppCard {
                            cards.forEachIndexed { index, card ->
                                RadioOptionRow(
                                    title = presetCardTitle(card),
                                    subtitle = presetCardHint(card),
                                    selected = displayedPreset == card.id,
                                    showDivider = index < cards.lastIndex || (!audioMode && !documentMode),
                                    onClick = { appViewModel.setPreset(card.id, mode) },
                                )
                            }
                            if (!audioMode && !documentMode) {
                                ListItem(
                                    headlineContent = {
                                        Text(stringResource(if (showAll) R.string.action_collapse else R.string.action_more))
                                    },
                                    supportingContent = { Text(stringResource(R.string.wizard_more_formats)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(onClick = onShowAll),
                                )
                            }
                        }
                    }
                    formatHintRes(displayedPreset)?.let { hint ->
                        item("hint") {
                            Text(
                                stringResource(hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    if (displayedPreset == "pdf-image") {
                        item("container") {
                            AppCard {
                                DOCUMENT_FORMAT_CHIPS.forEachIndexed { index, option ->
                                    RadioOptionRow(
                                        title = chipTitle(option),
                                        subtitle = chipHint(option),
                                        selected = (session.container ?: "jpg") == option.id,
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
                        AppCard {
                            options.forEachIndexed { index, option ->
                                RadioOptionRow(
                                    title = chipTitle(option),
                                    subtitle = chipHint(option),
                                    selected = session.quality == option.id,
                                    showDivider = index < options.lastIndex,
                                    onClick = { appViewModel.setQuality(option.id, mode) },
                                )
                            }
                        }
                    }
                }
                ConvertPage.Size -> {
                    item("size") {
                        AppCard {
                            SIZE_CHIPS.forEachIndexed { index, option ->
                                RadioOptionRow(
                                    title = chipTitle(option),
                                    subtitle = chipHint(option),
                                    selected = session.size == option.id,
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
                        AppCard {
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
                                RadioOptionRow(
                                    title = stringResource(card.titleRes),
                                    subtitle = hint,
                                    selected = selected,
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
        if (page == ConvertPage.Home) {
            Button(
                onClick = onStart,
                enabled = startEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.wizard_start_convert))
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (session.sources.isNotEmpty()) {
            AppCard {
                session.sources.forEachIndexed { index, source ->
                    val canSelect = documentMode || itemHasDuration(source.media)
                    val failed = !source.media.importable && !source.probing
                    val selected = source.media.sourceUri == selectedUri
                    ListItem(
                        headlineContent = { Text(source.media.displayName) },
                        supportingContent = {
                            if (source.probing) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.wizard_reading_format))
                                }
                            } else {
                                Text(sourceFormatLine(LocalContext.current.resources, source.media, source.probing))
                            }
                        },
                        trailingContent = if (source.media.sourceUri !in runningUris) {
                            {
                                IconButton(
                                    onClick = { onRemove(source.media.sourceUri) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.action_remove),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = when {
                                failed -> MaterialTheme.colorScheme.errorContainer
                                selected -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = canSelect) {
                                onSelectUri(source.media.sourceUri)
                            },
                    )
                    if (index < session.sources.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        } else {
            Text(
                stringResource(
                    when {
                        audioMode -> R.string.wizard_add_audio_hint
                        documentMode -> R.string.wizard_add_document_hint
                        else -> R.string.wizard_add_video_hint
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onGallery,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.wizard_source_gallery))
            }
            FilledTonalButton(
                onClick = onFiles,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.wizard_source_files))
            }
            if (audioMode && onMusic != null) {
                FilledTonalButton(
                    onClick = onMusic,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.wizard_source_music))
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
    )
    if (showDivider) {
        HorizontalDivider()
    }
}

@Composable
private fun RadioOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
    )
    if (showDivider) {
        HorizontalDivider()
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
