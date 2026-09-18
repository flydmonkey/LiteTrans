package com.videoconverter.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.OutputConfig
import com.videoconverter.android.domain.enqueueJobs
import com.videoconverter.android.engine.FfmpegProcess
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnqueueFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var source: File
    private lateinit var outputRoot: File
    private lateinit var plannedOutputDir: File

    @Before
    fun setUp() {
        source = File(context.cacheDir, "tiny.mp4")
        instrumentation.context.assets.open("tiny.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        outputRoot = requireNotNull(context.getExternalFilesDir(null))
        plannedOutputDir = File(context.filesDir, "planned-instrumentation")
        plannedOutputDir.deleteRecursively()
        File(outputRoot, "LiteTrans").deleteRecursively()
        File(context.filesDir, "jobs").deleteRecursively()
    }

    @After
    fun tearDown() {
        source.delete()
        plannedOutputDir.deleteRecursively()
        File(outputRoot, "LiteTrans").deleteRecursively()
        File(context.filesDir, "jobs").deleteRecursively()
    }

    @Test
    fun probeEnqueueCancelRetryAndAllocateUniqueNames() = runBlocking {
        val ffmpeg = FfmpegProcess(context)
        val media = ffmpeg.probe(Uri.fromFile(source), source.name)
        assertTrue(media.importable)
        assertEquals("h264", media.videoCodec)
        assertEquals(320, media.width)
        assertEquals(240, media.height)

        var sequence = 0
        fun enqueue() = enqueueJobs(
            sources = listOf(media),
            config = OutputConfig(preset = "mp4-copy"),
            outputDir = plannedOutputDir.absolutePath,
            nextId = { "instrumented-${++sequence}" },
            exists = { File(it).exists() },
            clock = { "20260918_033012" },
        ).getOrThrow().jobs.single()

        val first = enqueue()
        File(requireNotNull(first.outputPath)).apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        val second = enqueue()
        assertEquals("tiny.mp4", File(requireNotNull(first.outputPath)).name)
        assertEquals("tiny_20260918_033012.mp4", File(requireNotNull(second.outputPath)).name)

        val ffmpegStarted = CompletableDeferred<Unit>()
        val cancelledResult = async {
            ffmpeg.transcode(
                first,
                OutputTarget(OutputTarget.Kind.AppExternal),
                onProgress = {
                    ffmpeg.cancel(first.id)
                    ffmpegStarted.complete(Unit)
                },
            )
        }
        withTimeout(10_000) { ffmpegStarted.await() }
        assertEquals(JobStatus.Cancelled, cancelledResult.await().status)
        assertNoPartialFiles()

        val failed = ffmpeg.transcode(
            second,
            OutputTarget(OutputTarget.Kind.SafTree, "content://invalid/output"),
        )
        assertEquals(JobStatus.Failed, failed.status)
        assertNoPartialFiles()

        val retried = ffmpeg.transcode(
            failed.copy(
                status = JobStatus.Queued,
                progress = 0.0,
                error = null,
            ),
            OutputTarget(OutputTarget.Kind.AppExternal),
        )
        assertEquals(JobStatus.Completed, retried.status)
        assertTrue(File(requireNotNull(retried.outputPath)).isFile)
        assertTrue(source.isFile)
        assertNoPartialFiles()
    }

    private fun assertNoPartialFiles() {
        val staged = File(context.filesDir, "jobs")
        assertFalse(
            staged.walkTopDown().any { it.isFile && ".partial." in it.name },
        )
    }
}
