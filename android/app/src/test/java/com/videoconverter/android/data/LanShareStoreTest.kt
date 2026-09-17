package com.videoconverter.android.data

import com.videoconverter.android.lan.LanShareSettings
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class LanShareStoreTest {
    @Test
    fun missingFileLoadsDefaultOff() {
        val directory = Files.createTempDirectory("lan-share-store-test").toFile()
        try {
            val missing = File(directory, "lan-share.json")

            val loaded = loadLanShareOrDefault(missing)

            assertEquals(LanShareSettings(enabled = false, token = ""), loaded)
            assertEquals(false, missing.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun saveThenLoadRoundTripsEnabledAndToken() {
        val directory = Files.createTempDirectory("lan-share-store-test").toFile()
        try {
            val live = File(directory, "lan-share.json")
            val temporary = File(directory, "lan-share.json.tmp")
            val settings = LanShareSettings(enabled = true, token = "secret")

            saveLanShare(live, temporary, settings)

            assertEquals(settings, loadLanShareOrDefault(live))
            assertEquals("""{"enabled":true,"token":"secret"}""", live.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun savePersistsNormalizedToken() {
        val directory = Files.createTempDirectory("lan-share-store-test").toFile()
        try {
            val live = File(directory, "lan-share.json")
            val temporary = File(directory, "lan-share.json.tmp")

            saveLanShare(live, temporary, LanShareSettings(enabled = true, token = "  x  "))

            assertEquals(LanShareSettings(enabled = true, token = "x"), loadLanShareOrDefault(live))
            assertEquals("x", lanShareFromJson(live.readText()).token)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptJsonLoadsAsDefaultOff() {
        val directory = Files.createTempDirectory("lan-share-store-test").toFile()
        try {
            val live = File(directory, "lan-share.json").apply { writeText("{not json") }

            assertEquals(LanShareSettings(), loadLanShareOrDefault(live))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun defaultSettingsJsonShape() {
        assertEquals("""{"enabled":false,"token":""}""", lanShareToJson(LanShareSettings()))
    }
}
