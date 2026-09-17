package com.videoconverter.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.sourceStem
import com.videoconverter.android.ui.theme.LightTokens
import com.videoconverter.android.ui.theme.ShapeTokens

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
    onClearFinished: () -> Unit,
    onConvertAgain: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        IosLargeTitle(
            title = stringResource(R.string.tab_history),
            trailing = if (hasFinishedJobs(jobs)) {
                {
                    Text(
                        stringResource(R.string.action_clear_finished),
                        color = Color(LightTokens.Accent),
                        fontSize = 17.sp,
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clickable(onClick = onClearFinished)
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
            } else {
                null
            },
            below = {
                IosSegmented(
                    options = listOf(
                        HistorySegment.Video,
                        HistorySegment.Audio,
                        HistorySegment.Document,
                    ).map { target ->
                        stringResource(
                            when (target) {
                                HistorySegment.Video -> R.string.lan_segment_video
                                HistorySegment.Audio -> R.string.lan_segment_audio
                                HistorySegment.Document -> R.string.lan_segment_document
                            },
                        ) to (segment == target)
                    },
                    onSelect = { index ->
                        onSegment(
                            listOf(
                                HistorySegment.Video,
                                HistorySegment.Audio,
                                HistorySegment.Document,
                            )[index],
                        )
                    },
                )
            },
        )
        if (hasActiveJobs(jobs)) {
            Text(
                stringResource(R.string.history_running_banner, historyActiveCount(jobs)),
                color = Color(LightTokens.Muted),
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
                    .clickable(onClick = onConvertAgain),
            )
        }
        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(emptyLabel, color = Color(LightTokens.Ink), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.history_empty_hint), color = Color(LightTokens.Muted), fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            ) {
                items(jobs.asReversed(), key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        onCancel = { onCancel(job.id) },
                        onRetry = { onRetry(job.id) },
                        onOpen = { onOpen(job) },
                        onShare = { onShare(job) },
                        onRename = { renaming = job },
                        onDelete = { deleting = job },
                    )
                }
            }
        }
    }
    renaming?.let { job ->
        RenameDialog(
            initial = sourceStem(historyTitle(job, stringResource(R.string.untitled))),
            onConfirm = { name ->
                onRename(job, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { job ->
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            body = stringResource(R.string.history_delete_body),
            confirm = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(job.id)
                deleting = null
            },
            onDismiss = { deleting = null },
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
            IosLargeTitle(title = stringResource(R.string.tab_mine))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                mineItemGroups().forEach { group ->
                    IosSection {
                        group.forEachIndexed { index, item ->
                            IosRow(
                                title = stringResource(item.titleRes),
                                showDivider = index < group.lastIndex,
                                onClick = { onOpen(item.page) },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.mine_version, versionName),
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.mine_local_promise),
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            IosNavBar(
                title = stringResource(minePageTitleRes(page)),
                backLabel = stringResource(R.string.tab_mine),
                onBack = onBack,
            )
            Text(
                if (page == MinePage.About) {
                    stringResource(aboutBodyRes(), versionName)
                } else {
                    val bodyRes = minePageBodyRes(page)
                    if (bodyRes != 0) stringResource(bodyRes) else ""
                },
                color = Color(LightTokens.Ink),
                fontSize = 17.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
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
                .clip(RoundedCornerShape(ShapeTokens.Dialog))
                .background(Color(LightTokens.Card))
                .clickable(enabled = false, onClick = {})
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.history_rename_title), color = Color(LightTokens.Ink), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.history_rename_hint), color = Color(LightTokens.Muted), fontSize = 13.sp)
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
                    stringResource(R.string.action_cancel),
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Text(
                    stringResource(R.string.action_save),
                    color = Color(LightTokens.Accent),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = { onConfirm(value) })
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                .clip(RoundedCornerShape(ShapeTokens.Dialog))
                .background(Color(LightTokens.Card))
                .clickable(enabled = false, onClick = {})
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = Color(LightTokens.Ink), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Color(LightTokens.Muted), fontSize = 13.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    stringResource(R.string.action_cancel),
                    color = Color(LightTokens.Muted),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Text(
                    confirm,
                    color = Color(LightTokens.Accent),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}
