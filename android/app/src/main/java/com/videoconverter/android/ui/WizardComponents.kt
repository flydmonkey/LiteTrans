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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.ui.theme.LightTokens
import java.util.Locale

data class ChipOption(val id: String, val title: String, val hint: String)

fun outputFolderLabel(context: Context, output: OutputTarget): String = when (output.kind) {
    OutputTarget.Kind.Gallery -> "相册"
    OutputTarget.Kind.Movies -> "影库"
    OutputTarget.Kind.Downloads -> "下载"
    OutputTarget.Kind.Music -> "音乐"
    OutputTarget.Kind.SafTree -> output.treeUri
        ?.let(Uri::parse)
        ?.let { DocumentFile.fromTreeUri(context, it)?.name }
        ?: "所选文件夹"
    OutputTarget.Kind.AppExternal -> "应用输出目录"
}

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
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(LightTokens.Card)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Text(
                title,
                color = Color(LightTokens.Ink),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                )
            }
            below?.invoke()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(LightTokens.Border)),
        )
    }
}

@Composable
fun AppTopBar(step: WizardStep, subtitle: String) {
    PageHeader(title = wizardScreenTitle(step), subtitle = subtitle)
}

@Composable
fun StepTabs(
    step: WizardStep,
    importableCount: Int,
    onSelect: (WizardStep) -> Unit,
) {
    val tabs = listOf(
        WizardStep.Sources to "添加",
        WizardStep.Format to "格式",
        WizardStep.Output to "存放",
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
                        if (step.ordinal >= 1) Color(LightTokens.Accent) else Color(LightTokens.Chip),
                    ),
            )
            Box(
                modifier = Modifier
                    .weight(2f)
                    .height(2.dp)
                    .background(
                        if (step.ordinal >= 2) Color(LightTokens.Accent) else Color(LightTokens.Chip),
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
                            Color(LightTokens.Muted)
                        } else {
                            Color(LightTokens.Ink)
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
        StepStatus.Done -> Color(LightTokens.Accent)
        StepStatus.Current -> Color(LightTokens.Ink)
        StepStatus.Upcoming -> Color(LightTokens.Chip)
    }
    val foreground = when (status) {
        StepStatus.Upcoming -> Color(LightTokens.Ink)
        else -> Color(LightTokens.OnDark)
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(99.dp))
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
fun NoticeBar(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(LightTokens.Notice))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = Color(LightTokens.Ink),
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(
            "知道了",
            color = Color(LightTokens.Accent),
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

@Composable
fun Dropzone(onGallery: () -> Unit, onFiles: () -> Unit, centered: Boolean = false) {
    if (centered) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            VideoMark()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "添加要转码的视频",
                    color = Color(LightTokens.Ink),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "从相册选最近拍的，或从文件夹选原片。\n一次能选好几个，文件只留在这台手机上。",
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceChoiceCard(
                    title = "相册",
                    hint = "最近的视频",
                    onClick = onGallery,
                    emphasized = true,
                    modifier = Modifier.weight(1f),
                )
                SourceChoiceCard(
                    title = "文件",
                    hint = "本机文件夹",
                    onClick = onFiles,
                    emphasized = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceChoiceCard(
                title = "相册",
                hint = "最近的视频",
                onClick = onGallery,
                emphasized = true,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            SourceChoiceCard(
                title = "文件",
                hint = "本机文件夹",
                onClick = onFiles,
                emphasized = false,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SourceChoiceCard(
    title: String,
    hint: String,
    onClick: () -> Unit,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (emphasized) Color(LightTokens.Ink) else Color(LightTokens.Card))
            .border(
                1.dp,
                if (emphasized) Color(LightTokens.Ink) else Color(LightTokens.Border),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (compact) 14.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 28.dp else 36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (emphasized) Color(LightTokens.Accent) else Color(LightTokens.Chip),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (title == "相册") "▶" else "▤",
                color = if (emphasized) Color.White else Color(LightTokens.Ink),
                fontSize = if (compact) 12.sp else 14.sp,
            )
        }
        Text(
            title,
            color = if (emphasized) Color(LightTokens.OnDark) else Color(LightTokens.Ink),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            hint,
            color = if (emphasized) Color(LightTokens.OnDarkMuted) else Color(LightTokens.Muted),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun VideoMark() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(LightTokens.Card))
            .border(1.dp, Color(LightTokens.Border), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(LightTokens.Accent)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Color.White, fontSize = 16.sp)
        }
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
        !importable -> Color(LightTokens.Bad)
        selected -> Color.White
        else -> Color(LightTokens.Card)
    }
    val border = when {
        !importable -> Color(LightTokens.BadBorder)
        selected -> Color(LightTokens.Ink)
        else -> Color(LightTokens.Border)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, color = Color(LightTokens.Ink), fontWeight = FontWeight.SemiBold)
            Text(line, color = Color(LightTokens.Muted), fontSize = 13.sp)
        }
        if (canRemove) {
            Text(
                "移除",
                color = Color(LightTokens.Accent),
                modifier = Modifier.clickable(onClick = onRemove),
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val cells = cards + null
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
        Text(title, color = Color(LightTokens.Ink), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = Color(LightTokens.Muted), fontSize = 13.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val on = selected == option.id
                Column(
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
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        option.title,
                        color = if (on) Color(LightTokens.OnDark) else Color(LightTokens.Ink),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        option.hint,
                        color = if (on) Color(LightTokens.OnDarkMuted) else Color(LightTokens.Muted),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun OutputChoiceGrid(
    selectedId: String,
    customHint: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OUTPUT_CHOICE_CARDS.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { card ->
                    val hint = if (card.id == OUTPUT_CHOICE_CUSTOM && !customHint.isNullOrBlank()) {
                        customHint
                    } else {
                        card.hint
                    }
                    OutputChoiceCardView(
                        title = card.title,
                        hint = hint,
                        selected = selectedId == card.id,
                        onSelect = { onSelect(card.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
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
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(LightTokens.Ink) else Color(LightTokens.Card))
            .border(
                1.dp,
                if (selected) Color(LightTokens.Ink) else Color(LightTokens.Border),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            color = if (selected) Color(LightTokens.OnDark) else Color(LightTokens.Ink),
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(
            hint,
            color = if (selected) Color(LightTokens.OnDarkMuted) else Color(LightTokens.Muted),
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        JobStatus.Completed -> Color(LightTokens.Accent)
        JobStatus.Running -> Color(LightTokens.Ink)
        JobStatus.Failed -> Color(LightTokens.Accent)
        else -> Color(LightTokens.Chip)
    }
    val markForeground = when (job.status) {
        JobStatus.Queued, JobStatus.Cancelled -> Color(LightTokens.Ink)
        else -> Color.White
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(LightTokens.Card))
            .border(1.dp, Color(LightTokens.Border), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(markColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (job.status) {
                        JobStatus.Completed -> "▶"
                        JobStatus.Running -> "${kotlin.math.round(job.progress).toInt()}"
                        JobStatus.Failed -> "!"
                        JobStatus.Cancelled -> "–"
                        JobStatus.Queued -> "…"
                    },
                    color = markForeground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (job.status == JobStatus.Running) 12.sp else 14.sp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    historyTitle(job),
                    color = Color(LightTokens.Ink),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    historyDetail(job, statusLabel(job.status)),
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(LightTokens.Chip)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(Color(LightTokens.Accent)),
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) AccentText("取消", onCancel)
            if (job.status == JobStatus.Failed || job.status == JobStatus.Cancelled) {
                AccentText("再试一次", onRetry)
            }
            if (job.status == JobStatus.Completed) {
                AccentText("打开", onOpen)
                AccentText("分享", onShare)
                AccentText("重命名", onRename)
            }
            AccentText("删除", onDelete)
        }
    }
}

@Composable
fun WizardDock(
    step: WizardStep,
    summary: String,
    action: String,
    actionEnabled: Boolean,
    onBack: () -> Unit,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(LightTokens.Card)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(LightTokens.Border)),
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        Text(summary, color = Color(LightTokens.Muted), fontSize = 13.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step != WizardStep.Sources) {
                ActionButton("上一步", onBack, Modifier.weight(1f), filled = false)
                ActionButton(action, onAction, Modifier.weight(2f), filled = true, enabled = actionEnabled)
            } else {
                ActionButton(action, onAction, Modifier.fillMaxWidth(), filled = true, enabled = actionEnabled)
            }
        }
        }
    }
}

@Composable
private fun PresetCard(
    card: WizardPresetCard,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleColor = if (selected) Color(LightTokens.OnDark) else Color(LightTokens.Ink)
    val hintColor = if (selected) Color(LightTokens.OnDarkMuted) else Color(LightTokens.Muted)
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(LightTokens.Ink) else Color(LightTokens.Card))
            .border(
                1.dp,
                if (selected) Color(LightTokens.Ink) else Color(LightTokens.Border),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                card.title,
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = if (card.badge != null) 36.dp else 0.dp),
            )
            Text(
                card.hint,
                color = hintColor,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        card.badge?.let { badge ->
            Text(
                badge,
                color = if (selected) Color(LightTokens.Ink) else Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) Color(0xFFE7B56A) else Color(LightTokens.Accent))
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
            .dashedBorder(12.dp)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (showAll) "收起" else "更多",
            color = Color(LightTokens.Ink),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (showAll) "只看常用格式" else "GIF、音频和其他格式",
            color = Color(LightTokens.Muted),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun InkButton(label: String, onClick: () -> Unit) {
    ActionButton(label, onClick, filled = true)
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean,
    enabled: Boolean = true,
) {
    val background = when {
        !enabled -> Color(LightTokens.Accent).copy(alpha = 0.4f)
        filled -> Color(LightTokens.Accent)
        else -> Color(LightTokens.Chip)
    }
    val foreground = when {
        filled -> Color.White
        else -> Color(LightTokens.Ink)
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AccentText(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(LightTokens.Accent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun Modifier.dashedBorder(corner: Dp): Modifier = drawWithContent {
    drawContent()
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
    )
    drawRoundRect(
        color = Color(LightTokens.Border),
        style = stroke,
        cornerRadius = CornerRadius(corner.toPx()),
    )
}
