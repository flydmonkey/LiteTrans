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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.sourceStem
import com.videoconverter.android.ui.theme.ShapeTokens

@Composable
fun HistoryScreen(
    segment: HistorySegment,
    onSegment: (HistorySegment) -> Unit,
    jobs: List<Job>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (Job) -> Unit,
    onShare: (Job) -> Unit,
    onRename: (Job, String) -> Unit,
    onDelete: (String) -> Unit,
    onClearFinished: (HistorySegment) -> Unit,
    onConvertAgain: (HistorySegment) -> Unit,
) {
    var renaming by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }
    val selectedJobs = historyJobs(jobs, segment)
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.tab_history),
            actions = {
                if (hasFinishedJobs(selectedJobs)) {
                    TextButton(onClick = { onClearFinished(segment) }) {
                        Text(stringResource(R.string.action_clear_finished))
                    }
                }
            },
        )
        val pagerState = rememberSyncedPagerState(
            selectedIndex = historySegmentIndex(segment),
            pageCount = HistorySegment.entries.size,
            onIndexChange = { onSegment(historySegmentAt(it)) },
        )
        HistorySegmentTabs(
            selected = historySegmentAt(pagerState.currentPage),
            onSelect = onSegment,
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
        ) { index ->
            val pageSegment = historySegmentAt(index)
            HistorySegmentPane(
                segment = pageSegment,
                jobs = historyJobs(jobs, pageSegment),
                onCancel = onCancel,
                onRetry = onRetry,
                onOpen = onOpen,
                onShare = onShare,
                onRename = { renaming = it },
                onDelete = { deleting = it },
                onConvertAgain = { onConvertAgain(pageSegment) },
            )
        }
    }
    renaming?.let { job ->
        val untitled = stringResource(R.string.untitled)
        var value by remember(job.id) { mutableStateOf(sourceStem(historyTitle(job, untitled))) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.history_rename_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.history_rename_hint))
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename(job, value); renaming = null }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    deleting?.let { job ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(job.id); deleting = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun HistorySegmentPane(
    segment: HistorySegment,
    jobs: List<Job>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (Job) -> Unit,
    onShare: (Job) -> Unit,
    onRename: (Job) -> Unit,
    onDelete: (Job) -> Unit,
    onConvertAgain: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (hasActiveJobs(jobs)) {
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(onClick = onConvertAgain),
            ) {
                Text(
                    stringResource(R.string.history_running_banner, historyActiveCount(jobs)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
                    AppGlyphIcon(
                        kind = historyEmptyGlyph(segment),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(stringResource(historyEmptyLabelRes(segment)), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.history_empty_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onConvertAgain) {
                        Text(stringResource(R.string.action_convert_again))
                    }
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
                        onRename = { onRename(job) },
                        onDelete = { onDelete(job) },
                    )
                }
            }
        }
    }
}

@Composable
fun MineScreen(
    page: MinePage,
    versionName: String,
    onOpen: (MinePage) -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    if (page == MinePage.Root) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.tab_mine))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                mineItemGroups().forEach { group ->
                    OutlinedAppCard {
                        group.forEachIndexed { index, item ->
                            MineNavRow(
                                item = item,
                                showDivider = index < group.lastIndex,
                                onClick = {
                                    val url = legalUrl(item.page)
                                    if (url != null) {
                                        runCatching { uriHandler.openUri(url) }
                                    } else {
                                        onOpen(item.page)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(minePageTitleRes(page)), onBack = onBack)
            Text(
                if (page == MinePage.About) {
                    stringResource(aboutBodyRes(), versionName)
                } else {
                    val bodyRes = minePageBodyRes(page)
                    if (bodyRes != 0) stringResource(bodyRes) else ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun MineNavRow(
    item: MineItem,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MineRowLeading(icon = mineRowIcon(item.page))
        Text(
            stringResource(item.titleRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
    }
}

@Composable
fun MineRowLeading(icon: MineRowIcon) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(ShapeTokens.Panel))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == MineRowIcon.Language) {
            Text(
                "A",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Icon(
                imageVector = when (icon) {
                    MineRowIcon.Share -> Icons.Filled.Share
                    MineRowIcon.Lock -> Icons.Filled.Lock
                    MineRowIcon.List -> Icons.Filled.List
                    MineRowIcon.Info, MineRowIcon.Language -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
