package com.videoconverter.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoconverter.android.data.LanShareStore
import com.videoconverter.android.lan.lanPublicUrl
import com.videoconverter.android.lan.normalizeLanToken
import com.videoconverter.android.service.LanShareService
import com.videoconverter.android.ui.theme.LightTokens
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
        PageHeader(
            title = minePageTitle(MinePage.LanShare),
            leading = {
                Text(
                    "返回",
                    color = Color(LightTokens.Accent),
                    modifier = Modifier.clickable {
                        persistToken()
                        onBack()
                    },
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "局域网访问",
                    color = Color(LightTokens.Ink),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { on ->
                        persistEnabled(on)
                        if (on) LanShareService.start(context) else LanShareService.stop(context)
                    },
                )
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
                label = { Text("口令") },
                placeholder = { Text("可留空") },
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
                    "未设口令时，同一网络中知道地址即可访问。",
                    color = Color(LightTokens.Muted),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            if (settings.enabled) {
                val ipv4 = boundIpv4
                val port = boundPort
                if (ipv4 != null && port != null) {
                    val url = lanPublicUrl(ipv4, port, settings.token)
                    Text(
                        url,
                        color = Color(LightTokens.Ink),
                        fontSize = 15.sp,
                    )
                    Text(
                        if (copied) "已复制" else "复制",
                        color = Color(LightTokens.Accent),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("url", url))
                            copied = true
                        },
                    )
                } else {
                    Text(
                        boundError ?: "先连上 Wi‑Fi 或热点",
                        color = Color(LightTokens.Muted),
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
