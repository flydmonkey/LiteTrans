package com.videoconverter.android.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BinaryLocatorTest {
    @Test
    fun locatesBundledFfmpeg() {
        val nativeLibraryDir = Files.createTempDirectory("native-libs").toFile()
        try {
            val bundled = nativeLibraryDir.resolve("libffmpeg.so").apply { createNewFile() }

            assertEquals(bundled, nativeBinary(nativeLibraryDir, "ffmpeg"))
        } finally {
            nativeLibraryDir.deleteRecursively()
        }
    }

    @Test
    fun missingBinaryExplainsHowToFetchIt() {
        val nativeLibraryDir = Files.createTempDirectory("native-libs").toFile()
        try {
            val error = try {
                nativeBinary(nativeLibraryDir, "ffmpeg")
                fail("Expected nativeBinary to reject a missing file")
                null
            } catch (error: IllegalStateException) {
                error
            }

            assertTrue(error?.message.orEmpty().contains("找不到打包的 ffmpeg"))
            assertTrue(error?.message.orEmpty().contains("node android/scripts/fetch-ffmpeg.mjs"))
        } finally {
            nativeLibraryDir.deleteRecursively()
        }
    }
}
