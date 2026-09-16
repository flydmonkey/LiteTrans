package com.videoconverter.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.videoconverter.android.data.JobStore
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.markInterrupted
import com.videoconverter.android.service.TranscodeService
import com.videoconverter.android.ui.AppScreen
import com.videoconverter.android.ui.theme.LightTranscodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JobStore(this).update { jobs ->
            recoverInterruptedOnAppStart(jobs, TranscodeService.isAlive)
        }
        setContent {
            LightTranscodeTheme {
                AppScreen()
            }
        }
    }
}

internal fun recoverInterruptedOnAppStart(
    jobs: List<Job>,
    serviceAlive: Boolean,
): List<Job> = if (serviceAlive) jobs else markInterrupted(jobs)
