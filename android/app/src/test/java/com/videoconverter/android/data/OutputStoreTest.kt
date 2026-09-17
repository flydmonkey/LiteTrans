package com.videoconverter.android.data

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
}
