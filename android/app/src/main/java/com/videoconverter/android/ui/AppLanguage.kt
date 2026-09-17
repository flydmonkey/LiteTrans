package com.videoconverter.android.ui

enum class AppLanguage {
    System,
    ZhCn,
    ZhTw,
    En,
    Ja,
    Ko,
    ;

    fun languageTag(): String? = when (this) {
        System -> null
        ZhCn -> "zh-CN"
        ZhTw -> "zh-TW"
        En -> "en"
        Ja -> "ja"
        Ko -> "ko"
    }

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrEmpty()) return System
            return when (tag) {
                "zh-CN" -> ZhCn
                "zh-TW" -> ZhTw
                "en" -> En
                "ja" -> Ja
                "ko" -> Ko
                else -> throw IllegalArgumentException("Unknown language tag: $tag")
            }
        }
    }
}

fun fallbackLanguageForSystemTag(tag: String): AppLanguage {
    val normalized = tag.replace('_', '-').lowercase()
    return when {
        normalized == "zh-cn" || normalized == "zh-sg" -> AppLanguage.ZhCn
        normalized == "zh-tw" || normalized == "zh-hk" || normalized == "zh-mo" -> AppLanguage.ZhTw
        normalized.startsWith("en") -> AppLanguage.En
        normalized.startsWith("ja") -> AppLanguage.Ja
        normalized.startsWith("ko") -> AppLanguage.Ko
        else -> AppLanguage.En
    }
}
