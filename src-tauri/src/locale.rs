use std::collections::HashMap;
use std::sync::OnceLock;

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

impl AppLanguage {
    pub fn from_setting(value: Option<&str>) -> Self {
        match value {
            Some("zh-Hans") => Self::ZhHans,
            Some("zh-Hant") => Self::ZhHant,
            Some("en") => Self::En,
            Some("ja") => Self::Ja,
            Some("ko") => Self::Ko,
            _ => Self::System,
        }
    }
}

fn env_locale_tag() -> Option<String> {
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .into_iter()
        .find_map(|name| std::env::var(name).ok())
        .and_then(|raw| {
            let tag = raw.split('.').next().unwrap_or(&raw).trim();
            if tag.is_empty() || tag.eq_ignore_ascii_case("c") || tag.eq_ignore_ascii_case("posix")
            {
                None
            } else {
                Some(tag.replace('_', "-"))
            }
        })
}

fn apple_locale_tag() -> Option<String> {
    let output = std::process::Command::new("defaults")
        .args(["read", "-g", "AppleLocale"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let tag = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if tag.is_empty() {
        None
    } else {
        Some(tag.replace('_', "-"))
    }
}

pub fn system_locale_tag() -> String {
    static TAG: OnceLock<String> = OnceLock::new();
    TAG.get_or_init(|| {
        env_locale_tag()
            .or_else(apple_locale_tag)
            .unwrap_or_else(|| "en".into())
    })
    .clone()
}

fn locale_tables() -> &'static HashMap<&'static str, HashMap<String, String>> {
    static TABLES: OnceLock<HashMap<&'static str, HashMap<String, String>>> = OnceLock::new();
    TABLES.get_or_init(|| {
        [
            ("en", include_str!("../../src/locales/en.json")),
            ("zh-Hans", include_str!("../../src/locales/zh-Hans.json")),
            ("zh-Hant", include_str!("../../src/locales/zh-Hant.json")),
            ("ja", include_str!("../../src/locales/ja.json")),
            ("ko", include_str!("../../src/locales/ko.json")),
        ]
        .into_iter()
        .map(|(tag, raw)| (tag, serde_json::from_str(raw).unwrap_or_default()))
        .collect()
    })
}

pub fn message(tag: &str, key: &str) -> String {
    let tables = locale_tables();
    tables
        .get(tag)
        .and_then(|table| table.get(key))
        .or_else(|| tables.get("en").and_then(|table| table.get(key)))
        .cloned()
        .unwrap_or_else(|| key.to_string())
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

    #[test]
    fn zh_hans_lan_copy_uses_json() {
        assert_eq!(message("zh-Hans", "mine_lan"), "局域网访问");
        assert_eq!(
            message("zh-Hans", "lan_need_address"),
            "没有可用的局域网地址"
        );
        assert_eq!(message("zh-Hans", "lan_status_on"), "已开启");
    }
}
