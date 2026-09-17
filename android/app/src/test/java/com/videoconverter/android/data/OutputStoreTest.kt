package com.videoconverter.android.data

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutputStoreTest {
    @Test
    fun collisionUsesNumericSuffix() {
        assertEquals(
            "clip-1.mp4",
            uniqueDisplayName("clip", "mp4", setOf("clip.mp4")),
        )
    }

    @Test
    fun mediaStoreRelativePathsMatchDestinationChoices() {
        assertEquals("DCIM/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Gallery))
        assertEquals("Movies/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Movies))
        assertEquals("Download/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Downloads))
        assertEquals("Download/轻转码", defaultRelativePath())
    }

    @Test
    fun musicRelativePathIsMusicFolder() {
        assertEquals("Music/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Music))
    }

    @Test
    fun zeroUpdatedRowsFailsMediaStorePublish() {
        val error = assertThrows(IOException::class.java) {
            requireMediaStorePublished(0)
        }

        assertEquals("无法发布输出文件", error.message)
    }

    @Test
    fun safSecurityExceptionUsesOutputDirectoryError() {
        val error = assertThrows(IOException::class.java) {
            mapSafExportErrors { throw SecurityException("permission denied") }
        }

        assertEquals("无法写入输出目录，请重新选择", error.message)
    }

    @Test
    fun safIllegalArgumentExceptionUsesOutputDirectoryError() {
        val error = assertThrows(IOException::class.java) {
            mapSafExportErrors { throw IllegalArgumentException("invalid URI") }
        }

        assertEquals("无法写入输出目录，请重新选择", error.message)
    }

    @Test
    fun outputTargetForJobUsesNamedKind() {
        val fallback = OutputTarget(OutputTarget.Kind.Downloads)
        assertEquals(
            OutputTarget(OutputTarget.Kind.Music),
            outputTargetForJob("Music", null, fallback),
        )
        assertEquals(
            OutputTarget(OutputTarget.Kind.SafTree, "content://tree"),
            outputTargetForJob("SafTree", "content://tree", fallback),
        )
    }

    @Test
    fun unknownOrNullKindFallsBackToSessionTarget() {
        val fallback = OutputTarget(OutputTarget.Kind.Downloads)
        assertEquals(fallback, outputTargetForJob(null, null, fallback))
        assertEquals(fallback, outputTargetForJob("Unknown", null, fallback))
        assertEquals(fallback, outputTargetForJob("", "content://tree", fallback))
    }

    @Test
    fun documentsRelativePath() {
        assertEquals("Documents/轻转码", mediaStoreRelativePath(OutputTarget.Kind.Documents))
    }

    @Test
    fun outputTargetForJobAcceptsDocuments() {
        val fallback = OutputTarget(OutputTarget.Kind.Downloads)
        assertEquals(
            OutputTarget(OutputTarget.Kind.Documents),
            outputTargetForJob("Documents", null, fallback),
        )
    }

    @Test
    fun exportedLocationsPrefersOutputPaths() {
        val job = Job(
            id = "1", sourceUri = "u", displayName = "a.pdf",
            outputPath = "first", status = JobStatus.Completed, progress = 100.0,
            error = null, config = OutputConfig(preset = "pdf-split"),
            media = MediaInfo("u", "a.pdf", importable = true),
            outputPaths = listOf("first", "second"),
        )
        assertEquals(listOf("first", "second"), exportedLocations(job))
        assertEquals(listOf("only"), exportedLocations(job.copy(outputPath = "only", outputPaths = emptyList())))
    }

    @Test
    fun stampedJobsKeepIndependentOutputKinds() {
        val downloads = OutputTarget(OutputTarget.Kind.Downloads)
        val music = OutputTarget(OutputTarget.Kind.Music)
        val video = stampJobOutputTarget(sampleJob("v"), downloads)
        val audio = stampJobOutputTarget(sampleJob("a"), music)
        assertEquals(
            downloads,
            outputTargetForJob(video.outputKind, video.outputTreeUri, music),
        )
        assertEquals(
            music,
            outputTargetForJob(audio.outputKind, audio.outputTreeUri, downloads),
        )
    }

    private fun sampleJob(id: String) = Job(
        id = id,
        sourceUri = "content://$id",
        displayName = "$id.mp4",
        outputPath = null,
        status = JobStatus.Queued,
        progress = 0.0,
        error = null,
        config = OutputConfig(),
        media = MediaInfo(
            sourceUri = "content://$id",
            displayName = "$id.mp4",
            importable = true,
        ),
    )
}
