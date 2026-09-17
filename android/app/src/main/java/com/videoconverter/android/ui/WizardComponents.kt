package com.videoconverter.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.videoconverter.android.R
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.ui.theme.ShapeTokens
import java.util.Locale

data class ChipOption(
    val id: String,
    val titleRes: Int = 0,
    val hintRes: Int = 0,
    val title: String = "",
)

fun outputFolderLabel(context: Context, output: OutputTarget): String = when (output.kind) {
    OutputTarget.Kind.Gallery -> context.getString(R.string.output_gallery)
    OutputTarget.Kind.Movies -> context.getString(R.string.output_movies)
    OutputTarget.Kind.Downloads -> context.getString(R.string.output_downloads)
    OutputTarget.Kind.Music -> context.getString(R.string.output_music)
    OutputTarget.Kind.Documents -> context.getString(R.string.output_documents)
    OutputTarget.Kind.SafTree -> output.treeUri
        ?.let(Uri::parse)
        ?.let { DocumentFile.fromTreeUri(context, it)?.name }
        ?: context.getString(
            if (output.treeUri.isNullOrBlank()) R.string.output_custom_pick else R.string.output_selected_folder,
        )
    OutputTarget.Kind.AppExternal -> context.getString(R.string.output_app_dir)
}

@Composable
fun chipTitle(option: ChipOption): String =
    if (option.titleRes != 0) stringResource(option.titleRes) else option.title

@Composable
fun chipHint(option: ChipOption): String =
    if (option.hintRes != 0) stringResource(option.hintRes) else ""

@Composable
fun presetCardTitle(card: WizardPresetCard): String =
    if (card.titleRes != 0) stringResource(card.titleRes) else card.title

@Composable
fun presetCardHint(card: WizardPresetCard): String = stringResource(card.hintRes)

internal fun formatClock(seconds: Double): String {
    val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
    val hours = total / 3600
    val mins = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
}

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = if (trailing != null) 12.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
                trailing?.invoke()
            }
            below?.invoke()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
fun AppTopBar(step: WizardStep, subtitle: String) {
    PageHeader(title = stringResource(wizardScreenTitleRes(step)), subtitle = subtitle)
}

@Composable
fun StepTabs(
    step: WizardStep,
    importableCount: Int,
    onSelect: (WizardStep) -> Unit,
) {
    val tabs = listOf(
        WizardStep.Sources to stringResource(R.string.wizard_step_sources),
        WizardStep.Format to stringResource(R.string.wizard_step_format),
        WizardStep.Output to stringResource(R.string.wizard_step_output),
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .weight(2f)
                    .height(2.dp)
                    .background(
                        if (step.ordinal >= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
            Box(
                modifier = Modifier
                    .weight(2f)
                    .height(2.dp)
                    .background(
                        if (step.ordinal >= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
            Spacer(Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, (target, label) ->
                val status = when {
                    step == target -> StepStatus.Current
                    step.ordinal > target.ordinal -> StepStatus.Done
                    else -> StepStatus.Upcoming
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = canEnterStep(target, importableCount)) {
                            onSelect(target)
                        },
                ) {
                    StepDot(index + 1, status)
                    Text(
                        label,
                        color = if (status == StepStatus.Upcoming) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 12.sp,
                        fontWeight = if (status == StepStatus.Current) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Medium
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private enum class StepStatus { Done, Current, Upcoming }

@Composable
private fun StepDot(number: Int, status: StepStatus) {
    val background = when (status) {
        StepStatus.Done -> MaterialTheme.colorScheme.primary
        StepStatus.Current -> MaterialTheme.colorScheme.onSurface
        StepStatus.Upcoming -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (status) {
        StepStatus.Upcoming -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(ShapeTokens.Chip))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (status == StepStatus.Done) "✓" else number.toString(),
            color = foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun Dropzone(
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onMusic: (() -> Unit)? = null,
    centered: Boolean = false,
    audioMode: Boolean = false,
    documentMode: Boolean = false,
) {
    val cards: @Composable (Boolean) -> Unit = { compact ->
        val gap = if (audioMode || documentMode) 8.dp else 12.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (audioMode && onMusic != null) {
                SourceChoiceCard(
                    title = stringResource(R.string.wizard_source_music),
                    hint = stringResource(R.string.wizard_source_music_hint),
                    glyph = AppGlyph.Audio,
                    onClick = onMusic,
                    emphasized = true,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                )
            }
            SourceChoiceCard(
                title = stringResource(R.string.wizard_source_gallery),
                hint = stringResource(
                    if (documentMode) R.string.wizard_source_gallery_hint_image
                    else R.string.wizard_source_gallery_hint_video,
                ),
                glyph = if (documentMode) AppGlyph.Image else AppGlyph.Video,
                onClick = onGallery,
                emphasized = !audioMode,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            SourceChoiceCard(
                title = stringResource(R.string.wizard_source_files),
                hint = stringResource(R.string.wizard_source_files_hint),
                glyph = AppGlyph.File,
                onClick = onFiles,
                emphasized = false,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (centered) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when {
                audioMode -> MusicMark()
                documentMode -> DocumentMark()
                else -> VideoMark()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            audioMode -> R.string.wizard_add_audio
                            documentMode -> R.string.wizard_add_document
                            else -> R.string.wizard_add_video
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        when {
                            audioMode -> R.string.wizard_add_audio_hint
                            documentMode -> R.string.wizard_add_document_hint
                            else -> R.string.wizard_add_video_hint
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
            cards(false)
        }
    } else {
        cards(true)
    }
}

@Composable
private fun SourceChoiceCard(
    title: String,
    hint: String,
    glyph: AppGlyph,
    onClick: () -> Unit,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(ShapeTokens.Dialog))
            .background(if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(ShapeTokens.Dialog),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        GlyphBadge(kind = glyph, emphasized = emphasized, size = if (compact) 28.dp else 36.dp)
        Text(
            title,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = if (compact) 13.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact) {
            Text(
                hint,
                color = if (emphasized) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun MusicMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(ShapeTokens.Glyph))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(ShapeTokens.Glyph)),
        contentAlignment = Alignment.Center,
    ) {
        GlyphBadge(AppGlyph.Audio, emphasized = true)
    }
}

@Composable
private fun DocumentMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(ShapeTokens.Glyph))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(ShapeTokens.Glyph)),
        contentAlignment = Alignment.Center,
    ) {
        GlyphBadge(AppGlyph.Document, emphasized = true)
    }
}

@Composable
private fun VideoMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(ShapeTokens.Glyph))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(ShapeTokens.Glyph)),
        contentAlignment = Alignment.Center,
    ) {
        GlyphBadge(AppGlyph.Video, emphasized = true)
    }
}

@Composable
fun FileRow(
    name: String,
    line: String,
    selected: Boolean,
    importable: Boolean,
    canRemove: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val background = when {
        !importable -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surface
    }
    val border = when {
        !importable -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(ShapeTokens.Panel))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        if (canRemove) {
            Text(
                stringResource(R.string.action_remove),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
fun PresetGrid(
    cards: List<WizardPresetCard>,
    selected: String,
    showAll: Boolean,
    onSelect: (String) -> Unit,
    onToggleMore: () -> Unit,
    showMore: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val cells = if (showMore) cards + null else cards
        cells.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { card ->
                    if (card == null) {
                        MorePresetCard(showAll = showAll, onClick = onToggleMore, modifier = Modifier.weight(1f))
                    } else {
                        PresetCard(
                            card = card,
                            selected = selected == card.id,
                            onSelect = { onSelect(card.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionChips(
    title: String,
    description: String,
    options: List<ChipOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val on = selected == option.id
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(ShapeTokens.Chip))
                        .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(ShapeTokens.Chip),
                        )
                        .clickable { onSelect(option.id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        chipTitle(option),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        chipHint(option),
                        color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun OutputChoiceGrid(
    cards: List<OutputChoiceCard> = OUTPUT_CHOICE_CARDS,
    selectedId: String,
    customHint: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { card ->
                    val selected = selectedId == card.id
                    val hint = when {
                        card.id == OUTPUT_CHOICE_CUSTOM && selected && !customHint.isNullOrBlank() ->
                            customHint
                        card.id == OUTPUT_CHOICE_CUSTOM ->
                            stringResource(customOutputHintRes(selected, hasFolder = !customHint.isNullOrBlank()))
                        else -> stringResource(card.hintRes)
                    }
                    OutputChoiceCardView(
                        title = stringResource(card.titleRes),
                        hint = hint,
                        selected = selectedId == card.id,
                        onSelect = { onSelect(card.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OutputChoiceCardView(
    title: String,
    hint: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(ShapeTokens.Panel),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(
            hint,
            color = if (selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobRow(
    job: Job,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = job.status == JobStatus.Queued || job.status == JobStatus.Running
    val progress = when (job.status) {
        JobStatus.Completed -> 1f
        else -> (job.progress / 100.0).toFloat().coerceIn(0f, 1f)
    }
    val markColor = when (job.status) {
        JobStatus.Completed, JobStatus.Running -> MaterialTheme.colorScheme.primary
        JobStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val markForeground = when (job.status) {
        JobStatus.Completed, JobStatus.Running -> MaterialTheme.colorScheme.onPrimary
        JobStatus.Failed -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSurface
    }
    var sheet by remember { mutableStateOf(false) }
    val primary = jobRowPrimaryAction(job.status)
    val overflow = jobRowOverflowActions(job.status)
    val failed = job.status == JobStatus.Failed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (failed) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(markColor),
                    contentAlignment = Alignment.Center,
                ) {
                    when (job.status) {
                        JobStatus.Completed -> Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = markForeground,
                            modifier = Modifier.size(22.dp),
                        )
                        JobStatus.Running -> Text(
                            "${kotlin.math.round(job.progress).toInt()}",
                            color = markForeground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                        JobStatus.Failed -> Text(
                            "!",
                            color = markForeground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        JobStatus.Cancelled -> Text(
                            "–",
                            color = markForeground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        JobStatus.Queued -> Text(
                            "…",
                            color = markForeground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        historyTitle(job, stringResource(R.string.untitled)),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        historyDetail(
                            LocalContext.current.resources,
                            job,
                            stringResource(statusLabelRes(job.status)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (active) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primary?.let { action ->
                    Button(
                        onClick = jobRowActionClick(action, onCancel, onRetry, onOpen, onShare, onRename, onDelete),
                    ) {
                        Text(stringResource(jobRowActionLabelRes(action)))
                    }
                }
                if (overflow.isNotEmpty()) {
                    IconButton(onClick = { sheet = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                        )
                    }
                }
            }
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            overflow.forEach { action ->
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(jobRowActionLabelRes(action)),
                            color = if (action == JobRowAction.Delete) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        sheet = false
                        jobRowActionClick(action, onCancel, onRetry, onOpen, onShare, onRename, onDelete)()
                    },
                )
            }
        }
    }
}

private fun jobRowActionLabelRes(action: JobRowAction): Int = when (action) {
    JobRowAction.Cancel -> R.string.action_cancel
    JobRowAction.Retry -> R.string.action_retry
    JobRowAction.Open -> R.string.action_open
    JobRowAction.Share -> R.string.action_share
    JobRowAction.Rename -> R.string.action_rename
    JobRowAction.Delete -> R.string.action_delete
}

private fun jobRowActionClick(
    action: JobRowAction,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
): () -> Unit = when (action) {
    JobRowAction.Cancel -> onCancel
    JobRowAction.Retry -> onRetry
    JobRowAction.Open -> onOpen
    JobRowAction.Share -> onShare
    JobRowAction.Rename -> onRename
    JobRowAction.Delete -> onDelete
}

@Composable
private fun PresetCard(
    card: WizardPresetCard,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val hintColor = if (selected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(ShapeTokens.Panel),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                presetCardTitle(card),
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = if (card.badgeRes != null) 36.dp else 0.dp),
            )
            Text(
                presetCardHint(card),
                color = hintColor,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        card.badgeRes?.let { badgeRes ->
            Text(
                stringResource(badgeRes),
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(ShapeTokens.Stamp))
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MorePresetCard(showAll: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(92.dp)
            .dashedBorder(ShapeTokens.Panel, MaterialTheme.colorScheme.outline)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(if (showAll) R.string.action_collapse else R.string.action_more),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(if (showAll) R.string.wizard_less_formats else R.string.wizard_more_formats),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun Modifier.dashedBorder(corner: Dp, color: Color): Modifier = drawWithContent {
    drawContent()
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
    )
    drawRoundRect(
        color = color,
        style = stroke,
        cornerRadius = CornerRadius(corner.toPx()),
    )
}
