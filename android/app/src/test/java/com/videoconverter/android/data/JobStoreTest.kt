package com.videoconverter.android.data

import java.io.File
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JobStoreTest {
    @Test
    fun replaceFileOverwritesExistingFile() {
        val directory = Files.createTempDirectory("job-store-test").toFile()
        try {
            val live = File(directory, "jobs.json").apply { writeText("old") }
            val temporary = File(directory, "jobs.json.tmp").apply { writeText("new") }

            replaceFile(temporary, live)

            assertEquals("new", live.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedReplacePreservesExistingFile() {
        val directory = Files.createTempDirectory("job-store-test").toFile()
        try {
            val live = File(directory, "jobs.json").apply { writeText("old") }
            val missing = File(directory, "missing.tmp")

            assertThrows(NoSuchFileException::class.java) {
                replaceFile(missing, live)
            }
            assertEquals("old", live.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
