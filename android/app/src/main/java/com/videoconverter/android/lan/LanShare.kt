package com.videoconverter.android.lan

data class LanShareSettings(
    val enabled: Boolean = false,
    val token: String = "",
)

fun normalizeLanToken(raw: String): String = raw.trim()

fun lanTokenAllows(storedToken: String, queryK: String?): Boolean {
    if (storedToken.isEmpty()) return true
    return queryK == storedToken
}
