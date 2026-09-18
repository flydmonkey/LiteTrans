package com.videoconverter.android

import android.app.Activity.OVERRIDE_TRANSITION_CLOSE
import android.app.Activity.OVERRIDE_TRANSITION_OPEN
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.data.LanShareStore
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.markInterrupted
import com.videoconverter.android.service.LanShareService
import com.videoconverter.android.service.TranscodeService
import com.videoconverter.android.ui.AppScreen
import com.videoconverter.android.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val openLanShareState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        skipSplashOnRecreate(savedInstanceState)
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
            AppTheme {
                AppScreen(
                    openLanShare = openLanShareState.value,
                    onOpenLanShareConsumed = ::clearOpenLanShare,
                )
            }
        }
    }

    override fun recreate() {
        suppressRecreateTransition()
        super.recreate()
        suppressRecreateTransition()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenLanShare(intent)
    }

    private fun skipSplashOnRecreate(savedInstanceState: Bundle?) {
        if (!shouldSkipSplashOnRecreate(savedInstanceState != null, Build.VERSION.SDK_INT)) return
        splashScreen.setOnExitAnimationListener { splash -> splash.remove() }
    }

    private fun suppressRecreateTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
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

internal fun shouldSkipSplashOnRecreate(savedInstanceStatePresent: Boolean, sdkInt: Int): Boolean =
    savedInstanceStatePresent && sdkInt >= Build.VERSION_CODES.S

internal fun recoverInterruptedOnAppStart(
    jobs: List<Job>,
    serviceAlive: Boolean,
    interruptedError: String = "Conversion was interrupted",
): List<Job> = if (serviceAlive) jobs else markInterrupted(jobs, interruptedError)
