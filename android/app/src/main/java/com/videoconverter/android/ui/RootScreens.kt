package com.videoconverter.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.sourceStem
import com.videoconverter.android.ui.theme.LightTokens

@Composable
fun RootTabBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(LightTokens.Card))
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(LightTokens.Border)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            RootTab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    TabGlyph(tab, on)
                    Text(
                        rootTabLabel(tab),
                        color = if (on) Color(LightTokens.Accent) else Color(LightTokens.Muted),
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySegmentTabs(
    segment: HistorySegment,
    onSegment: (HistorySegment) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(HistorySegment.Video to "视频", HistorySegment.Audio to "音频").forEach { (target, label) ->
            val on = segment == target
            Column(
                modifier = Modifier.clickable { onSegment(target) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = if (on) Color(LightTokens.Ink) else Color(LightTokens.Muted),
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .background(if (on) Color(LightTokens.Accent) else Color.Transparent),
                )
            }
        }
    }
}

@Composable
fun HistoryScreen(
    segment: HistorySegment,
    onSegment: (HistorySegment) -> Unit,
    jobs: List<Job>,
    emptyLabel: String,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (Job) -> Unit,
    onShare: (Job) -> Unit,
    onRename: (Job, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf<Job?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(
            title = "历史记录",
            subtitle = "打开、分享、重命名或删除转好的文件",
        )
        HistorySegmentTabs(segment, onSegment)
        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(LightTokens.Card)),
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
                    Text(emptyLabel, color = Color(LightTokens.Ink), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("转好的文件会出现在这里", color = Color(LightTokens.Muted), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
            ) {
                items(jobs.asReversed(), key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        onCancel = { onCancel(job.id) },
                        onRetry = { onRetry(job.id) },
                        onOpen = { onOpen(job) },
                        onShare = { onShare(job) },
                        onRename = { renaming = job },
                        onDelete = { onDelete(job.id) },
                    )
                }
            }
        }
    }
    renaming?.let { job ->
        RenameDialog(
            initial = sourceStem(historyTitle(job)),
            onConfirm = { name ->
                onRename(job, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
fun MineScreen(
    page: MinePage,
    versionName: String,
    onOpen: (MinePage) -> Unit,
    onBack: () -> Unit,
) {
    if (page == MinePage.Root) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(title = "我的")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(LightTokens.Card)),
            ) {
                mineItems().forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(LightTokens.Border)),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item.page) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.title, color = Color(LightTokens.Ink), fontSize = 16.sp)
                        Text("›", color = Color(LightTokens.Muted), fontSize = 20.sp)
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(
                title = minePageTitle(page),
                leading = {
                    Text(
                        "返回",
                        color = Color(LightTokens.Accent),
                        modifier = Modifier.clickable(onClick = onBack),
                    )
                },
            )
            Text(
                if (page == MinePage.About) aboutBody(versionName) else minePageBody(page),
                color = Color(LightTokens.Muted),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(LightTokens.Card))
                .clickable(enabled = false, onClick = {})
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("重命名", color = Color(LightTokens.Ink), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("只改文件名，扩展名会保持原样。", color = Color(LightTokens.Muted), fontSize = 13.sp)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "取消",
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    "保存",
                    color = Color(LightTokens.Accent),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClick = { onConfirm(value) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TabGlyph(tab: RootTab, selected: Boolean) {
    val color = if (selected) Color(LightTokens.Accent) else Color(LightTokens.Muted)
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 1.7.dp.toPx())
        when (tab) {
            RootTab.Transcode -> {
                val left = 2.dp.toPx()
                val top = 4.5.dp.toPx()
                val width = 18.dp.toPx()
                val height = 13.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = stroke,
                )
                val play = Path().apply {
                    val cx = left + width * 0.40f
                    val cy = top + height / 2f
                    val h = height * 0.32f
                    moveTo(cx - h * 0.3f, cy - h)
                    lineTo(cx - h * 0.3f, cy + h)
                    lineTo(cx + h, cy)
                    close()
                }
                drawPath(play, color, style = Fill)
            }
            RootTab.Audio -> {
                val head = Offset(center.x - 4.dp.toPx(), center.y + 5.dp.toPx())
                val stemX = head.x + 3.2.dp.toPx()
                val stemTop = Offset(stemX, center.y - 6.dp.toPx())
                drawCircle(
                    color = color,
                    radius = 3.2.dp.toPx(),
                    center = head,
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(stemX, head.y),
                    end = stemTop,
                    strokeWidth = 1.7.dp.toPx(),
                )
                val flag = Path().apply {
                    moveTo(stemTop.x, stemTop.y)
                    quadraticTo(
                        stemTop.x + 8.dp.toPx(),
                        stemTop.y + 2.dp.toPx(),
                        stemTop.x + 5.dp.toPx(),
                        stemTop.y + 7.dp.toPx(),
                    )
                    quadraticTo(
                        stemTop.x + 3.dp.toPx(),
                        stemTop.y + 4.dp.toPx(),
                        stemTop.x,
                        stemTop.y + 3.dp.toPx(),
                    )
                    close()
                }
                drawPath(flag, color, style = Fill)
            }
            RootTab.History -> {
                drawCircle(color = color, radius = 8.dp.toPx(), center = center, style = stroke)
                drawLine(
                    color = color,
                    start = center,
                    end = Offset(center.x, center.y - 5.dp.toPx()),
                    strokeWidth = 1.7.dp.toPx(),
                )
                drawLine(
                    color = color,
                    start = center,
                    end = Offset(center.x + 5.dp.toPx(), center.y + 2.dp.toPx()),
                    strokeWidth = 1.7.dp.toPx(),
                )
            }
            RootTab.Mine -> {
                drawCircle(
                    color = color,
                    radius = 3.5.dp.toPx(),
                    center = Offset(center.x, 7.dp.toPx()),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(4.dp.toPx(), 10.dp.toPx()),
                    size = Size(14.dp.toPx(), 12.dp.toPx()),
                    style = stroke,
                )
            }
        }
    }
}
