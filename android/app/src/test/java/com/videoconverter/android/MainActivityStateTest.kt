package com.videoconverter.android

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityStateTest {
    @Test
    fun activeServiceKeepsRunningJobRunningDuringActivityRecreation() {
        val running = job()

        val updated = recoverInterruptedOnAppStart(listOf(running), serviceAlive = true)

        assertEquals(JobStatus.Running, updated.single().status)
        assertEquals(null, updated.single().error)
    }

    @Test
    fun inactiveServiceMarksRunningJobInterruptedOnProcessStart() {
        val updated = recoverInterruptedOnAppStart(listOf(job()), serviceAlive = false)

        assertEquals(JobStatus.Failed, updated.single().status)
        assertEquals("Conversion was interrupted", updated.single().error)
    }

    private fun job() = Job(
        id = "job-1",
        sourceUri = "content://video",
        displayName = "video.mp4",
        outputPath = "/tmp/video.mp4",
        status = JobStatus.Running,
        progress = 42.0,
        error = null,
        config = OutputConfig(),
        media = MediaInfo(
            sourceUri = "content://video",
            displayName = "video.mp4",
        ),
    )
}
