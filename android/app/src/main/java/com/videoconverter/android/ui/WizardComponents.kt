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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
    OutputTarget.Kind.Downloads -> "下载/轻转码"
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
fun WizardHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                "轻转码",
                color = Color(LightTokens.Ink),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.8).sp,
            )
            Text(
                "从一种格式转到另一种。文件只留在这台手机上。",
                color = Color(LightTokens.Muted),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            "不上传 · 不联网",
            color = Color(LightTokens.Muted),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(LightTokens.Chip))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun StepTabs(
    step: WizardStep,
    importableCount: Int,
    onSelect: (WizardStep) -> Unit,
) {
    val tabs = listOf(
        WizardStep.Sources to "1 添加",
        WizardStep.Format to "2 格式",
        WizardStep.Output to "3 存放",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { (target, label) ->
            val active = step == target
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Color(LightTokens.Ink) else Color(LightTokens.Chip))
                    .clickable {
                        if (canEnterStep(target, importableCount)) onSelect(target)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) Color(LightTokens.OnDark) else Color(LightTokens.Ink),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
fun Dropzone(onGallery: () -> Unit, onFiles: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dashedBorder(14.dp)
            .background(Color(LightTokens.Card), RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "添加要转码的视频",
            color = Color(LightTokens.Ink),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "点这里选择，一次能选好几个",
            color = Color(LightTokens.Muted),
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("相册", onGallery)
            InkButton("文件", onFiles)
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
fun OutputBar(label: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(LightTokens.Card))
            .border(1.dp, Color(LightTokens.Border), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("输出到", color = Color(LightTokens.Ink), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = Color(LightTokens.Muted), modifier = Modifier.padding(top = 4.dp))
        }
        InkButton("换个位置", onChange)
    }
}

@Composable
fun JobRow(
    job: Job,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val active = job.status == JobStatus.Queued || job.status == JobStatus.Running
    val progress = when (job.status) {
        JobStatus.Completed -> 1f
        else -> (job.progress / 100.0).toFloat().coerceIn(0f, 1f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(LightTokens.Card))
            .border(1.dp, Color(LightTokens.Border), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${job.displayName}  →  ${outputFileName(job.outputPath)}",
            color = Color(LightTokens.Ink),
            fontWeight = FontWeight.SemiBold,
        )
        val detail = buildString {
            append(statusLabel(job.status))
            if (job.status == JobStatus.Running) append(" ${kotlin.math.round(job.progress).toInt()}%")
            if (job.status == JobStatus.Failed && !job.error.isNullOrBlank()) append(" · ${job.error}")
        }
        Text(detail, color = Color(LightTokens.Muted), fontSize = 13.sp)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (active) {
                AccentText("取消", onCancel)
            }
            if (job.status == JobStatus.Failed || job.status == JobStatus.Cancelled) {
                AccentText("再试一次", onRetry)
            }
            if (job.status == JobStatus.Completed) {
                AccentText("打开", onOpen)
                AccentText("分享", onShare)
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(LightTokens.Ink))
            .padding(start = 20.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step != WizardStep.Sources) {
            Text(
                "上一步",
                color = Color(LightTokens.OnDark),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }
        Text(
            summary,
            color = Color(LightTokens.OnDarkMuted),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .widthIn(min = 96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(LightTokens.Accent).copy(alpha = if (actionEnabled) 1f else 0.45f))
                .clickable(enabled = actionEnabled, onClick = onAction)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                card.title,
                color = if (selected) Color(LightTokens.OnDark) else Color(LightTokens.Ink),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            card.badge?.let { badge ->
                Text(
                    badge,
                    color = if (selected) Color(LightTokens.Ink) else Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Color(0xFFE7B56A) else Color(LightTokens.Accent))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            card.hint,
            color = if (selected) Color(LightTokens.OnDarkMuted) else Color(LightTokens.Muted),
            fontSize = 13.sp,
        )
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
    Text(
        label,
        color = Color(LightTokens.OnDark),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(LightTokens.Ink))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
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
