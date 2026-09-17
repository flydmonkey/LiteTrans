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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.sourceStem

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
        AppTopBar(
            title = stringResource(R.string.tab_history),
            actions = {
                if (hasFinishedJobs(jobs)) {
                    TextButton(onClick = onClearFinished) {
                        Text(stringResource(R.string.action_clear_finished))
                    }
                }
            },
        )
        HistorySegmentTabs(selected = segment, onSelect = onSegment)
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
                    Icon(Icons.Filled.List, contentDescription = null)
                    Text(emptyLabel, style = MaterialTheme.typography.titleLarge)
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
                        onRename = { renaming = job },
                        onDelete = { deleting = job },
                    )
                }
            }
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
fun MineScreen(
    page: MinePage,
    versionName: String,
    onOpen: (MinePage) -> Unit,
    onBack: () -> Unit,
) {
    if (page == MinePage.Root) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(title = stringResource(R.string.tab_mine))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                mineItemGroups().forEach { group ->
                    AppCard {
                        group.forEachIndexed { index, item ->
                            ListItem(
                                headlineContent = { Text(stringResource(item.titleRes)) },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                },
                                modifier = Modifier.clickable { onOpen(item.page) },
                            )
                            if (index < group.lastIndex) HorizontalDivider()
                        }
                    }
                }
                Text(
                    stringResource(R.string.mine_version, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    stringResource(R.string.mine_local_promise),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
