package com.videoconverter.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
fun RootNavigationBar(
    selected: RootTab,
    activeHistoryCount: Int,
    onSelect: (RootTab) -> Unit,
) {
    NavigationBar {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    val icon = rootTabIcon(tab)
                    if (tab == RootTab.History && activeHistoryCount > 0) {
                        BadgedBox(badge = { Badge { Text("$activeHistoryCount") } }) {
                            Icon(icon, contentDescription = stringResource(rootTabLabelRes(tab)))
                        }
                    } else {
                        Icon(icon, contentDescription = stringResource(rootTabLabelRes(tab)))
                    }
                },
                label = {
                    Text(
                        stringResource(rootTabLabelRes(tab)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

fun rootTabIcon(tab: RootTab): ImageVector = when (tab) {
    RootTab.Convert -> Icons.Filled.PlayArrow
    RootTab.History -> Icons.Filled.List
    RootTab.Mine -> Icons.Filled.Person
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertModeTabs(
    selected: ConvertMode,
    onSelect: (ConvertMode) -> Unit,
) {
    val modes = ConvertMode.entries
    PrimaryTabRow(selectedTabIndex = modes.indexOf(selected)) {
        modes.forEach { mode ->
            Tab(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                text = { Text(stringResource(convertModeLabelRes(mode))) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySegmentTabs(
    selected: HistorySegment,
    onSelect: (HistorySegment) -> Unit,
) {
    val segments = listOf(HistorySegment.Video, HistorySegment.Audio, HistorySegment.Document)
    SecondaryTabRow(selectedTabIndex = segments.indexOf(selected)) {
        segments.forEach { segment ->
            Tab(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                text = {
                    Text(
                        stringResource(
                            when (segment) {
                                HistorySegment.Video -> R.string.lan_segment_video
                                HistorySegment.Audio -> R.string.lan_segment_audio
                                HistorySegment.Document -> R.string.lan_segment_document
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_got_it))
            }
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        content = { Column(modifier = Modifier.padding(vertical = 4.dp), content = { content() }) },
    )
}
