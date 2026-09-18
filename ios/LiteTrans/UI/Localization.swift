import Foundation

func localizationLocale(for language: AppLanguage) -> Locale {
    if let identifier = resolvedLocaleIdentifier(language) {
        return Locale(identifier: identifier)
    }
    return .autoupdatingCurrent
}

func localizedText(_ key: String.LocalizationValue, locale: Locale) -> String {
    String(localized: LocalizedStringResource(key, locale: locale))
}

func localizedText(_ key: String, locale: Locale) -> String {
    localizedText(String.LocalizationValue(stringLiteral: key), locale: locale)
}

func localizedText(_ key: String.LocalizationValue, language: AppLanguage) -> String {
    localizedText(key, locale: localizationLocale(for: language))
}

func lanHistoryCopy(language: AppLanguage) -> LanHistoryCopy {
    LanHistoryCopy(
        warning: localizedText("lan_open_warning", language: language),
        video: localizedText("lan_segment_video", language: language),
        audio: localizedText("lan_segment_audio", language: language),
        document: localizedText("lan_segment_document", language: language),
        image: localizedText("lan_segment_image", language: language),
        emptyVideo: localizedText("history_empty_video", language: language),
        emptyAudio: localizedText("history_empty_audio", language: language),
        emptyDocument: localizedText("history_empty_document", language: language),
        emptyImage: localizedText("history_empty_image", language: language),
        download: localizedText("lan_download", language: language),
        downloadNamed: localizedText("lan_download_named", language: language),
        downloadIndex: localizedText("lan_download_index", language: language),
        statusQueued: localizedText("status_queued", language: language),
        statusRunning: localizedText("status_running", language: language),
        statusCompleted: localizedText("status_completed", language: language),
        statusFailed: localizedText("status_failed", language: language),
        statusCancelled: localizedText("status_cancelled", language: language),
        needToken: localizedText("lan_need_token", language: language),
        previewFailed: localizedText("lan_preview_failed", language: language),
        downloadToOpen: localizedText("lan_download_to_open", language: language)
    )
}
