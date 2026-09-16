use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionSettings {
    pub output_dir: Option<String>,
    pub preset: Option<String>,
    pub quality: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
}

pub fn settings_file(config_dir: &Path) -> PathBuf {
    config_dir.join("settings.json")
}

pub fn load_from_path(path: &Path) -> SessionSettings {
    let Ok(text) = std::fs::read_to_string(path) else {
        return SessionSettings::default();
    };
    serde_json::from_str(&text).unwrap_or_default()
}

pub fn save_to_path(path: &Path, settings: &SessionSettings) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|err| format!("无法保存设置：{err}"))?;
    }
    let text = serde_json::to_string_pretty(settings).map_err(|err| format!("无法保存设置：{err}"))?;
    std::fs::write(path, text).map_err(|err| format!("无法保存设置：{err}"))
}

pub fn usable_output_dir(settings: &SessionSettings) -> Option<String> {
    let dir = settings.output_dir.as_deref()?.trim();
    if dir.is_empty() {
        return None;
    }
    let path = Path::new(dir);
    if path.is_dir() {
        Some(dir.to_string())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_settings() {
        let dir = std::env::temp_dir().join(format!("video-converter-settings-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = settings_file(&dir);
        let settings = SessionSettings {
            output_dir: Some("/tmp/out".into()),
            preset: Some("mp4-h265".into()),
            quality: Some("small".into()),
            max_width: Some(1280),
            max_height: Some(720),
        };
        save_to_path(&path, &settings).unwrap();
        assert_eq!(load_from_path(&path), settings);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn missing_file_is_default() {
        let path = std::env::temp_dir().join("video-converter-missing-settings.json");
        let _ = std::fs::remove_file(&path);
        assert_eq!(load_from_path(&path), SessionSettings::default());
    }

    #[test]
    fn missing_dir_is_not_usable() {
        let settings = SessionSettings {
            output_dir: Some("/definitely-not-a-real-dir-轻转码".into()),
            ..Default::default()
        };
        assert_eq!(usable_output_dir(&settings), None);
    }
}
