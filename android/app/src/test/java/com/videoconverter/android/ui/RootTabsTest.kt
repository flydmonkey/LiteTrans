package com.videoconverter.android.ui

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTabsTest {
    @Test
    fun tabLabelsMatchProductCopy() {
        assertEquals(
            listOf("视频转码", "音频转换", "历史记录", "我的"),
            RootTab.entries.map(::rootTabLabel),
        )
    }

    @Test
    fun mineEntriesArePrivacyTermsAndAbout() {
        assertEquals(
            listOf(MinePage.Privacy, MinePage.Terms, MinePage.About),
            mineItems().map { it.page },
        )
        assertEquals("隐私协议", minePageTitle(MinePage.Privacy))
        assertEquals("使用条款", minePageTitle(MinePage.Terms))
        assertEquals("关于", minePageTitle(MinePage.About))
        assertEquals("我的", minePageTitle(MinePage.Root))
    }

    @Test
    fun legalCopyStaysLocalAndOffline() {
        assertTrue(minePageBody(MinePage.Privacy).contains("不会上传"))
        assertTrue(minePageBody(MinePage.Privacy).contains("不要求联网"))
        assertTrue(minePageBody(MinePage.Terms).contains("历史记录"))
        assertTrue(aboutBody("0.1.0").contains("0.1.0"))
        assertTrue(aboutBody("0.1.0").contains("不上传"))
    }

    @Test
    fun historySplitsVideoAndAudioJobs() {
        val video = job("v", OutputConfig(preset = "mp4-h264"))
        val fromVideoExtract = job("a1", OutputConfig(preset = "audio-mp3"))
        val wav = job("a2", OutputConfig(preset = "audio-wav"))
        val flac = job("a3", OutputConfig(preset = "audio-flac"))
        val pdf = job("d", OutputConfig(preset = "pdf-split"))
        val jobs = listOf(video, fromVideoExtract, wav, flac, pdf)
        assertFalse(isAudioHistoryJob(video))
        assertTrue(isAudioHistoryJob(fromVideoExtract))
        assertTrue(isAudioHistoryJob(wav))
        assertTrue(isAudioHistoryJob(flac))
        assertEquals(HistorySegment.Document, historySegmentFor(pdf))
        assertEquals(listOf(pdf), historyJobs(jobs, HistorySegment.Document))
        assertEquals(listOf(video), historyJobs(jobs, HistorySegment.Video))
        assertEquals(listOf(fromVideoExtract, wav, flac), historyJobs(jobs, HistorySegment.Audio))
        assertEquals("还没有视频记录", historyEmptyLabel(HistorySegment.Video))
        assertEquals("还没有音频记录", historyEmptyLabel(HistorySegment.Audio))
        assertEquals("还没有文档记录", historyEmptyLabel(HistorySegment.Document))
    }

    @Test
    fun clearFinishedOnlyDropsCurrentSegment() {
        val doneVideo = job("v", OutputConfig(preset = "mp4-h264"), JobStatus.Completed)
        val doneAudio = job("a", OutputConfig(preset = "audio-mp3"), JobStatus.Completed)
        val runningAudio = job("r", OutputConfig(preset = "audio-ogg"), JobStatus.Running)
        val kept = remainingJobsAfterClearFinished(
            listOf(doneVideo, doneAudio, runningAudio),
            HistorySegment.Audio,
        )
        assertEquals(setOf("v", "r"), kept.map { it.id }.toSet())

        val doneDoc = job("d", OutputConfig(preset = "pdf-split"), JobStatus.Completed)
        val keptDocs = remainingJobsAfterClearFinished(
            listOf(doneVideo, doneDoc),
            HistorySegment.Document,
        )
        assertEquals(setOf("v"), keptDocs.map { it.id }.toSet())
    }

    @Test
    fun backConsumesMineDetailAndWizardStepsOnly() {
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Mine, MinePage.Privacy, WizardStep.Sources),
        )
        assertEquals(
            RootBack(RootTab.Transcode, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Format),
        )
        assertEquals(
            RootBack(RootTab.Transcode, MinePage.Root, WizardStep.Format),
            consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Output),
        )
        assertNull(consumeRootBack(RootTab.History, MinePage.Root, WizardStep.Sources))
        assertNull(consumeRootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources))
        assertNull(consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Sources))
    }

    @Test
    fun backConsumesAudioWizardSteps() {
        assertEquals(
            RootBack(RootTab.Audio, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Audio, MinePage.Root, WizardStep.Format),
        )
        assertNull(consumeRootBack(RootTab.Audio, MinePage.Root, WizardStep.Sources))
        assertEquals(
            HistorySegment.Audio,
            historySegmentAfterEnqueue(ConvertMode.Audio, "audio-mp3"),
        )
        assertEquals(
            HistorySegment.Video,
            historySegmentAfterEnqueue(ConvertMode.Video, "mp4-h264"),
        )
        assertEquals(
            HistorySegment.Audio,
            historySegmentAfterEnqueue(ConvertMode.Video, "audio-mp3"),
        )
    }

    @Test
    fun customContainerMp3CountsAsAudioHistory() {
        val customMp3 = job("c", OutputConfig(preset = "custom", container = "mp3"))
        assertEquals("mp3", com.videoconverter.android.domain.resolveConfig(customMp3.config).getOrThrow().container)
        assertTrue(isAudioHistoryJob(customMp3))
    }

    @Test
    fun leavingMineResetsDetail() {
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.Privacy))
        assertEquals(MinePage.Privacy, minePageAfterLeavingTab(RootTab.Mine, MinePage.Privacy))
    }

    private fun job(id: String, config: OutputConfig, status: JobStatus = JobStatus.Completed) = Job(
        id = id,
        sourceUri = "content://$id",
        displayName = "$id.mp4",
        outputPath = null,
        status = status,
        progress = 1.0,
        error = null,
        config = config,
        media = MediaInfo(sourceUri = "content://$id", displayName = "$id.mp4", importable = true),
    )
}
