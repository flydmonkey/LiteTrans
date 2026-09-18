use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum AppLanguage {
    #[default]
    System,
    #[serde(rename = "zh-Hans")]
    ZhHans,
    #[serde(rename = "zh-Hant")]
    ZhHant,
    En,
    Ja,
    Ko,
}

fn resolve_system_tag(system_tag: &str) -> &'static str {
    let normalized = system_tag.replace('_', "-").to_lowercase();
    if normalized.is_empty() {
        return "en";
    }
    if normalized == "zh-cn"
        || normalized == "zh-sg"
        || normalized == "zh-hans"
        || normalized.starts_with("zh-hans-")
    {
        return "zh-Hans";
    }
    if normalized == "zh-tw"
        || normalized == "zh-hk"
        || normalized == "zh-mo"
        || normalized == "zh-hant"
        || normalized.starts_with("zh-hant-")
    {
        return "zh-Hant";
    }
    if normalized == "en" || normalized.starts_with("en-") {
        return "en";
    }
    if normalized == "ja" || normalized.starts_with("ja-") {
        return "ja";
    }
    if normalized == "ko" || normalized.starts_with("ko-") {
        return "ko";
    }
    "en"
}

pub fn resolve_locale_tag(language: &AppLanguage, system_tag: &str) -> &'static str {
    match language {
        AppLanguage::System => resolve_system_tag(system_tag),
        AppLanguage::ZhHans => "zh-Hans",
        AppLanguage::ZhHant => "zh-Hant",
        AppLanguage::En => "en",
        AppLanguage::Ja => "ja",
        AppLanguage::Ko => "ko",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn system_zh_cn_is_simplified() {
        assert_eq!(resolve_locale_tag(&AppLanguage::System, "zh-CN"), "zh-Hans");
    }

    #[test]
    fn system_fr_falls_back_to_en() {
        assert_eq!(resolve_locale_tag(&AppLanguage::System, "fr-FR"), "en");
    }

    #[test]
    fn explicit_ja_ignores_system() {
        assert_eq!(resolve_locale_tag(&AppLanguage::Ja, "en-US"), "ja");
    }
}
