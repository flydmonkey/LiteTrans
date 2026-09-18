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

fun canonicalizeAppLanguageTag(tag: String): String? {
    if (tag.isEmpty()) return null
    val normalized = tag.replace('_', '-').lowercase()
    return when {
        normalized == "zh-hans" ||
            normalized.startsWith("zh-hans-") ||
            normalized == "zh-cn" ||
            normalized == "zh-sg" -> "zh-CN"
        normalized == "zh-hant" ||
            normalized.startsWith("zh-hant-") ||
            normalized == "zh-tw" ||
            normalized == "zh-hk" ||
            normalized == "zh-mo" -> "zh-TW"
        normalized == "en" || normalized.startsWith("en-") -> "en"
        normalized == "ja" || normalized.startsWith("ja-") -> "ja"
        normalized == "ko" || normalized.startsWith("ko-") -> "ko"
        else -> "en"
    }
}

fun appLanguageFromLocaleTags(tags: List<String>): AppLanguage {
    return AppLanguage.fromTag(canonicalizeAppLanguageTag(tags.firstOrNull().orEmpty()))
}

data class LanguageSelectionEffect(
    val apply: AppLanguage?,
    val extraRecreate: Boolean,
)

fun languageSelectionEffect(current: AppLanguage, selected: AppLanguage): LanguageSelectionEffect {
    if (current == selected) return LanguageSelectionEffect(apply = null, extraRecreate = false)
    return LanguageSelectionEffect(apply = selected, extraRecreate = false)
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
