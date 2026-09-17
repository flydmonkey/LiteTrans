package com.videoconverter.android.data

import android.content.Context
import com.videoconverter.android.R
import com.videoconverter.android.lan.LanShareSettings
import com.videoconverter.android.lan.normalizeLanToken
import java.io.File
import java.io.IOException
import org.json.JSONObject

class LanShareStore(
    private val file: File,
    private val saveError: String = "Could not save LAN access settings",
) {
    constructor(context: Context) : this(
        File(context.filesDir, "lan-share.json"),
        context.getString(R.string.error_cannot_save_lan),
    )

    private val temporary = File(file.parentFile, "${file.name}.tmp")

    fun load(): LanShareSettings = synchronized(STORE_LOCK) {
        loadLanShareOrDefault(file)
    }

    fun save(settings: LanShareSettings) = synchronized(STORE_LOCK) {
        saveLanShare(file, temporary, settings, saveError)
    }

    private companion object {
        val STORE_LOCK = Any()
    }
}

internal fun lanShareFromJson(json: String): LanShareSettings {
    val obj = JSONObject(json)
    return LanShareSettings(
        enabled = obj.optBoolean("enabled", false),
        token = obj.optString("token", ""),
    )
}

internal fun lanShareToJson(settings: LanShareSettings): String =
    JSONObject()
        .put("enabled", settings.enabled)
        .put("token", normalizeLanToken(settings.token))
        .toString()

internal fun loadLanShareOrDefault(file: File): LanShareSettings {
    if (!file.isFile) return LanShareSettings()
    return try {
        lanShareFromJson(file.readText())
    } catch (_: Exception) {
        LanShareSettings()
    }
}

internal fun saveLanShare(
    file: File,
    temporary: File,
    settings: LanShareSettings,
    saveError: String = "Could not save LAN access settings",
) {
    try {
        temporary.writeText(lanShareToJson(settings))
        replaceFile(temporary, file)
    } catch (error: Exception) {
        temporary.delete()
        if (error is IOException) throw error
        throw IOException(saveError, error)
    }
}
