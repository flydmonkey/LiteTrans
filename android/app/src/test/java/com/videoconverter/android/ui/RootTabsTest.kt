package com.videoconverter.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import com.videoconverter.android.R
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
        assertEquals(R.string.tab_history, rootTabLabelRes(RootTab.History))
        assertEquals(
            listOf(
                R.string.tab_convert,
                R.string.tab_history,
                R.string.tab_mine,
            ),
            RootTab.entries.map(::rootTabLabelRes),
        )
        assertEquals(Icons.Filled.Sync, rootTabIcon(RootTab.Convert))
        assertEquals(Icons.Filled.Schedule, rootTabIcon(RootTab.History))
        assertEquals(Icons.Filled.AccountCircle, rootTabIcon(RootTab.Mine))
        assertEquals(R.string.lan_segment_video, convertModeLabelRes(ConvertMode.Video))
        assertEquals(R.string.lan_segment_audio, convertModeLabelRes(ConvertMode.Audio))
        assertEquals(R.string.lan_segment_document, convertModeLabelRes(ConvertMode.Document))
        assertEquals(
            listOf(ConvertMode.Video, ConvertMode.Audio, ConvertMode.Document),
            ConvertMode.entries.mapIndexed { index, _ -> convertModeAt(index) },
        )
        assertEquals(0, convertModeIndex(ConvertMode.Video))
        assertEquals(1, convertModeIndex(ConvertMode.Audio))
        assertEquals(2, convertModeIndex(ConvertMode.Document))
        assertEquals(ConvertMode.Video, convertModeAt(-1))
        assertEquals(ConvertMode.Document, convertModeAt(99))
        assertEquals(
            listOf(HistorySegment.Video, HistorySegment.Audio, HistorySegment.Document),
            HistorySegment.entries.mapIndexed { index, _ -> historySegmentAt(index) },
        )
        assertEquals(0, historySegmentIndex(HistorySegment.Video))
        assertEquals(1, historySegmentIndex(HistorySegment.Audio))
        assertEquals(2, historySegmentIndex(HistorySegment.Document))
        assertEquals(HistorySegment.Video, historySegmentAt(-1))
        assertEquals(HistorySegment.Document, historySegmentAt(99))
    }

    @Test
    fun mineEntriesArePrivacyTermsAndAbout() {
        assertEquals(
            listOf(MinePage.LanShare, MinePage.Language, MinePage.Privacy, MinePage.Terms, MinePage.About),
            mineItems().map { it.page },
        )
        assertEquals(R.string.mine_lan, minePageTitleRes(MinePage.LanShare))
        assertEquals(R.string.mine_language, minePageTitleRes(MinePage.Language))
        assertEquals(R.string.language_follow_system, languageLabelRes(AppLanguage.System))
        assertEquals(R.string.language_zh_cn, languageLabelRes(AppLanguage.ZhCn))
        assertEquals(R.string.language_zh_tw, languageLabelRes(AppLanguage.ZhTw))
        assertEquals(R.string.language_en, languageLabelRes(AppLanguage.En))
        assertEquals(R.string.language_ja, languageLabelRes(AppLanguage.Ja))
        assertEquals(R.string.language_ko, languageLabelRes(AppLanguage.Ko))
        assertEquals(
            listOf(
                AppLanguage.System,
                AppLanguage.ZhCn,
                AppLanguage.ZhTw,
                AppLanguage.En,
                AppLanguage.Ja,
                AppLanguage.Ko,
            ),
            languageOptions(),
        )
        assertEquals(R.string.mine_privacy, minePageTitleRes(MinePage.Privacy))
        assertEquals(R.string.mine_terms, minePageTitleRes(MinePage.Terms))
        assertEquals(R.string.mine_about, minePageTitleRes(MinePage.About))
        assertEquals(R.string.tab_mine, minePageTitleRes(MinePage.Root))
        assertEquals(
            listOf(
                listOf(MinePage.LanShare, MinePage.Language),
                listOf(MinePage.Privacy, MinePage.Terms),
                listOf(MinePage.About),
            ),
            mineItemGroups().map { group -> group.map { it.page } },
        )
        assertEquals(MineRowIcon.Share, mineRowIcon(MinePage.LanShare))
        assertEquals(MineRowIcon.Language, mineRowIcon(MinePage.Language))
        assertEquals(MineRowIcon.Lock, mineRowIcon(MinePage.Privacy))
        assertEquals(MineRowIcon.List, mineRowIcon(MinePage.Terms))
        assertEquals(MineRowIcon.Info, mineRowIcon(MinePage.About))
    }

    @Test
    fun privacyAndTermsUsePublicHttpsPages() {
        assertEquals(
            "https://flydmonkey.github.io/LiteTrans/docs/privacy.html",
            legalUrl(MinePage.Privacy),
        )
        assertEquals(
            "https://flydmonkey.github.io/LiteTrans/docs/terms.html",
            legalUrl(MinePage.Terms),
        )
        assertTrue(legalUrl(MinePage.Privacy) != legalUrl(MinePage.Terms))
        assertNull(legalUrl(MinePage.About))
        assertNull(legalUrl(MinePage.LanShare))
        assertNull(legalUrl(MinePage.Language))
        assertNull(legalUrl(MinePage.Root))
    }

    @Test
    fun legalCopyUsesStringResources() {
        assertEquals(R.string.privacy_body, minePageBodyRes(MinePage.Privacy))
        assertEquals(R.string.terms_body, minePageBodyRes(MinePage.Terms))
        assertEquals(R.string.about_body, aboutBodyRes())
        assertEquals(0, minePageBodyRes(MinePage.Root))
        assertEquals(0, minePageBodyRes(MinePage.LanShare))
        assertEquals(0, minePageBodyRes(MinePage.Language))
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
        assertEquals(R.string.history_empty_video, historyEmptyLabelRes(HistorySegment.Video))
        assertEquals(R.string.history_empty_audio, historyEmptyLabelRes(HistorySegment.Audio))
        assertEquals(R.string.history_empty_document, historyEmptyLabelRes(HistorySegment.Document))
        assertEquals(AppGlyph.Video, historyEmptyGlyph(HistorySegment.Video))
        assertEquals(AppGlyph.Audio, historyEmptyGlyph(HistorySegment.Audio))
        assertEquals(AppGlyph.Document, historyEmptyGlyph(HistorySegment.Document))
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
    fun jobRowActionsHideDeleteWhileRunningAndLeadWithOpen() {
        assertEquals(listOf(JobRowAction.Cancel), jobRowActions(JobStatus.Queued))
        assertEquals(listOf(JobRowAction.Cancel), jobRowActions(JobStatus.Running))
        assertEquals(
            listOf(JobRowAction.Retry, JobRowAction.Delete),
            jobRowActions(JobStatus.Failed),
        )
        assertEquals(
            listOf(JobRowAction.Retry, JobRowAction.Delete),
            jobRowActions(JobStatus.Cancelled),
        )
        assertEquals(
            listOf(JobRowAction.Open, JobRowAction.Share, JobRowAction.Rename, JobRowAction.Delete),
            jobRowActions(JobStatus.Completed),
        )
        assertEquals(JobRowAction.Cancel, jobRowPrimaryAction(JobStatus.Queued))
        assertEquals(JobRowAction.Cancel, jobRowPrimaryAction(JobStatus.Running))
        assertEquals(JobRowAction.Retry, jobRowPrimaryAction(JobStatus.Failed))
        assertEquals(JobRowAction.Retry, jobRowPrimaryAction(JobStatus.Cancelled))
        assertEquals(JobRowAction.Open, jobRowPrimaryAction(JobStatus.Completed))
        assertEquals(emptyList<JobRowAction>(), jobRowOverflowActions(JobStatus.Running))
        assertEquals(listOf(JobRowAction.Delete), jobRowOverflowActions(JobStatus.Failed))
        assertEquals(
            listOf(JobRowAction.Share, JobRowAction.Rename, JobRowAction.Delete),
            jobRowOverflowActions(JobStatus.Completed),
        )
        val done = job("v", OutputConfig(preset = "mp4-h264"), JobStatus.Completed)
        val running = job("r", OutputConfig(preset = "mp4-h264"), JobStatus.Running)
        assertTrue(hasFinishedJobs(listOf(done, running)))
        assertTrue(hasActiveJobs(listOf(done, running)))
        assertFalse(hasFinishedJobs(listOf(running)))
        assertFalse(hasActiveJobs(listOf(done)))
        assertEquals(1, historyActiveCount(listOf(done, running)))
    }

    @Test
    fun backConsumesMineDetailAndConvertPages() {
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, ConvertPage.Home),
            consumeRootBack(RootTab.Mine, MinePage.Privacy, ConvertPage.Home),
        )
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, ConvertPage.Home),
            consumeRootBack(RootTab.Mine, MinePage.LanShare, ConvertPage.Home),
        )
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, ConvertPage.Home),
            consumeRootBack(RootTab.Mine, MinePage.Language, ConvertPage.Home),
        )
        assertEquals(
            RootBack(RootTab.Convert, MinePage.Root, ConvertPage.Home),
            consumeRootBack(RootTab.Convert, MinePage.Root, ConvertPage.Format),
        )
        assertEquals(
            RootBack(RootTab.Convert, MinePage.Root, ConvertPage.Home),
            consumeRootBack(RootTab.Convert, MinePage.Root, ConvertPage.Output),
        )
        assertEquals(
            RootBack(RootTab.Convert, MinePage.Root, ConvertPage.Home),
            consumeRootBack(RootTab.Convert, MinePage.Root, ConvertPage.Quality),
        )
        assertNull(consumeRootBack(RootTab.History, MinePage.Root, ConvertPage.Home))
        assertNull(consumeRootBack(RootTab.Mine, MinePage.Root, ConvertPage.Home))
        assertNull(consumeRootBack(RootTab.Convert, MinePage.Root, ConvertPage.Home))
    }

    @Test
    fun convertHomeShowsSettingsRows() {
        assertEquals(
            listOf(ConvertSetting.Format, ConvertSetting.Quality, ConvertSetting.Size, ConvertSetting.Output),
            convertSettingsFor("mp4-h264"),
        )
        assertEquals(
            listOf(ConvertSetting.Format, ConvertSetting.Output),
            convertSettingsFor("mp4-copy"),
        )
        assertEquals(
            listOf(ConvertSetting.Format, ConvertSetting.Quality, ConvertSetting.Output),
            convertSettingsFor("audio-mp3"),
        )
        assertEquals(
            listOf(ConvertSetting.Format, ConvertSetting.Output),
            convertSettingsFor("audio-wav"),
        )
        assertEquals(R.string.tab_convert, convertPageTitleRes(ConvertPage.Home))
        assertEquals(R.string.wizard_title_format, convertPageTitleRes(ConvertPage.Format))
        assertEquals(R.string.quality_video_title, convertPageTitleRes(ConvertPage.Quality))
        assertEquals(R.string.resolution_title, convertPageTitleRes(ConvertPage.Size))
        assertEquals(R.string.wizard_title_output, convertPageTitleRes(ConvertPage.Output))
        assertEquals(R.string.wizard_title_format, convertSettingTitleRes(ConvertSetting.Format))
        assertEquals(R.string.quality_video_title, convertSettingTitleRes(ConvertSetting.Quality))
        assertEquals(R.string.quality_audio_title, convertSettingTitleRes(ConvertSetting.Quality, "audio-mp3"))
        assertEquals(R.string.format_compress_title, convertSettingTitleRes(ConvertSetting.Quality, "image-compress"))
        assertEquals(R.string.resolution_title, convertSettingTitleRes(ConvertSetting.Size))
        assertEquals(R.string.wizard_title_output, convertSettingTitleRes(ConvertSetting.Output))
        assertEquals(ConvertPage.Format, convertPageFor(ConvertSetting.Format))
        assertEquals(ConvertPage.Quality, convertPageFor(ConvertSetting.Quality))
        assertEquals(ConvertPage.Size, convertPageFor(ConvertSetting.Size))
        assertEquals(ConvertPage.Output, convertPageFor(ConvertSetting.Output))
    }

    @Test
    fun historySegmentAfterEnqueueFollowsMode() {
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
        assertEquals(
            HistorySegment.Document,
            historySegmentAfterEnqueue(ConvertMode.Document, "image-jpg"),
        )
        assertEquals(ConvertMode.Video, convertModeForHistorySegment(HistorySegment.Video))
        assertEquals(ConvertMode.Audio, convertModeForHistorySegment(HistorySegment.Audio))
        assertEquals(ConvertMode.Document, convertModeForHistorySegment(HistorySegment.Document))
    }

    @Test
    fun customContainerMp3CountsAsAudioHistory() {
        val customMp3 = job("c", OutputConfig(preset = "custom", container = "mp3"))
        assertEquals("mp3", com.videoconverter.android.domain.resolveConfig(customMp3.config).getOrThrow().container)
        assertTrue(isAudioHistoryJob(customMp3))
    }

    @Test
    fun historyThumbKindFollowsSegmentAndOutputType() {
        assertEquals(
            HistoryThumbKind.Video,
            historyThumbKind(job("v", OutputConfig(preset = "mp4-h264"), outputPath = "/tmp/clip.mp4")),
        )
        assertEquals(
            HistoryThumbKind.Audio,
            historyThumbKind(job("a", OutputConfig(preset = "audio-mp3"), outputPath = "/tmp/song.mp3")),
        )
        assertEquals(
            HistoryThumbKind.Document,
            historyThumbKind(job("d", OutputConfig(preset = "pdf-split"), outputPath = "/tmp/scan.pdf")),
        )
        assertEquals(
            HistoryThumbKind.Image,
            historyThumbKind(job("p", OutputConfig(preset = "pdf-image"), outputPath = "/tmp/page.png")),
        )
        assertEquals(
            HistoryThumbKind.Image,
            historyThumbKind(job("i", OutputConfig(preset = "image-compress"), outputPath = "/tmp/shot.jpg")),
        )
    }

    @Test
    fun leavingMineResetsDetail() {
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.Privacy))
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.LanShare))
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.Language))
        assertEquals(MinePage.Privacy, minePageAfterLeavingTab(RootTab.Mine, MinePage.Privacy))
    }

    private fun job(
        id: String,
        config: OutputConfig,
        status: JobStatus = JobStatus.Completed,
        outputPath: String? = null,
    ) = Job(
        id = id,
        sourceUri = "content://$id",
        displayName = "$id.mp4",
        outputPath = outputPath,
        status = status,
        progress = 1.0,
        error = null,
        config = config,
        media = MediaInfo(sourceUri = "content://$id", displayName = "$id.mp4", importable = true),
    )
}
