package com.videoconverter.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.data.LanShareStore
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.markInterrupted
import com.videoconverter.android.service.LanShareService
import com.videoconverter.android.service.TranscodeService
import com.videoconverter.android.ui.AppScreen
import com.videoconverter.android.ui.theme.LightTranscodeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openLanShareState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (LanShareStore(this).load().enabled) {
            LanShareService.start(this)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                JobStore(this@MainActivity).update { jobs ->
                    recoverInterruptedOnAppStart(
                        jobs,
                        TranscodeService.isAlive,
                        getString(R.string.error_interrupted),
                    )
                }
            }
        }
        applyOpenLanShare(intent)
        setContent {
            LightTranscodeTheme {
                AppScreen(
                    openLanShare = openLanShareState.value,
                    onOpenLanShareConsumed = ::clearOpenLanShare,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenLanShare(intent)
    }

    private fun applyOpenLanShare(intent: Intent?) {
        if (intent?.getBooleanExtra(LanShareService.EXTRA_OPEN_LAN_SHARE, false) == true) {
            openLanShareState.value = true
        }
    }

    private fun clearOpenLanShare() {
        openLanShareState.value = false
        intent?.removeExtra(LanShareService.EXTRA_OPEN_LAN_SHARE)
    }
}

internal fun recoverInterruptedOnAppStart(
    jobs: List<Job>,
    serviceAlive: Boolean,
    interruptedError: String = "Conversion was interrupted",
): List<Job> = if (serviceAlive) jobs else markInterrupted(jobs, interruptedError)
