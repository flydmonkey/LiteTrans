package com.videoconverter.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoconverter.android.R
import com.videoconverter.android.data.LanShareStore
import com.videoconverter.android.lan.lanPublicUrl
import com.videoconverter.android.lan.normalizeLanToken
import com.videoconverter.android.service.LanShareService
import kotlinx.coroutines.delay

@Composable
fun LanShareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val store = remember { LanShareStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var tokenDraft by remember { mutableStateOf(settings.token) }
    var tokenFocused by remember { mutableStateOf(false) }
    var boundIpv4 by remember { mutableStateOf(LanShareService.boundIpv4) }
    var boundPort by remember { mutableStateOf(LanShareService.boundPort) }
    var boundError by remember { mutableStateOf(LanShareService.boundError) }
    var copied by remember { mutableStateOf(false) }

    fun persistToken() {
        store.save(store.load().copy(token = normalizeLanToken(tokenDraft)))
        val loaded = store.load()
        settings = loaded
        tokenDraft = loaded.token
    }

    fun persistEnabled(enabled: Boolean) {
        store.save(store.load().copy(enabled = enabled, token = normalizeLanToken(tokenDraft)))
        val loaded = store.load()
        settings = loaded
        tokenDraft = loaded.token
    }

    LaunchedEffect(Unit) {
        while (true) {
            val loaded = store.load()
            settings = if (tokenFocused) settings.copy(enabled = loaded.enabled) else loaded
            if (!tokenFocused) tokenDraft = loaded.token
            boundIpv4 = LanShareService.boundIpv4
            boundPort = LanShareService.boundPort
            boundError = LanShareService.boundError
            delay(1_000)
        }
    }

    LaunchedEffect(settings.enabled) {
        copied = false
        if (!settings.enabled) {
            boundIpv4 = null
            boundPort = null
            boundError = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(minePageTitleRes(MinePage.LanShare)),
            onBack = {
                persistToken()
                onBack()
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedAppCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MineRowLeading(icon = MineRowIcon.Share)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.mine_lan),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.lan_open_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { on ->
                            persistEnabled(on)
                            if (on) LanShareService.start(context) else LanShareService.stop(context)
                        },
                    )
                }
            }
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (tokenFocused && !focus.isFocused) persistToken()
                        tokenFocused = focus.isFocused
                    },
                label = { Text(stringResource(R.string.lan_token_label)) },
                placeholder = { Text(stringResource(R.string.lan_token_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        persistToken()
                        keyboard?.hide()
                    },
                ),
            )
            if (settings.token.isEmpty()) {
                Text(
                    stringResource(R.string.lan_token_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (settings.enabled) {
                val ipv4 = boundIpv4
                val port = boundPort
                OutlinedAppCard {
                    if (ipv4 != null && port != null) {
                        val url = lanPublicUrl(ipv4, port, settings.token)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                url,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("url", url))
                                    copied = true
                                },
                            ) {
                                Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy))
                            }
                        }
                    } else {
                        Text(
                            boundError ?: stringResource(R.string.lan_need_wifi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
