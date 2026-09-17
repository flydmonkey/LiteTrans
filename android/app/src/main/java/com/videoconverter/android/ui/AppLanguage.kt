package com.videoconverter.android.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.videoconverter.android.R

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

fun languageOptions(): List<AppLanguage> = AppLanguage.entries

fun languageLabelRes(language: AppLanguage): Int = when (language) {
    AppLanguage.System -> R.string.language_follow_system
    AppLanguage.ZhCn -> R.string.language_zh_cn
    AppLanguage.ZhTw -> R.string.language_zh_tw
    AppLanguage.En -> R.string.language_en
    AppLanguage.Ja -> R.string.language_ja
    AppLanguage.Ko -> R.string.language_ko
}

fun appLanguageFromLocaleTags(tags: List<String>): AppLanguage {
    val tag = tags.firstOrNull().orEmpty()
    if (tag.isEmpty()) return AppLanguage.System
    return AppLanguage.fromTag(tag)
}

fun applyAppLanguage(language: AppLanguage) {
    val locales = language.languageTag()
        ?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()
    AppCompatDelegate.setApplicationLocales(locales)
}

fun currentAppLanguage(): AppLanguage {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return AppLanguage.System
    return appLanguageFromLocaleTags(
        (0 until locales.size()).mapNotNull { locales[it]?.toLanguageTag() },
    )
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
