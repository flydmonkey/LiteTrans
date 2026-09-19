use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::lan::{normalize_lan_token, LanShareSettings};

#[derive(Debug, Default, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModeSettings {
    pub output_dir: Option<String>,
    pub preset: Option<String>,
    pub quality: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionSettings {
    pub output_dir: Option<String>,
    pub preset: Option<String>,
    pub quality: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
    #[serde(default)]
    pub language: Option<String>,
    #[serde(default)]
    pub video: ModeSettings,
    #[serde(default)]
    pub audio: ModeSettings,
    #[serde(default)]
    pub document: ModeSettings,
    #[serde(default)]
    pub lan_share: LanShareSettings,
}

fn mode_is_populated(mode: &ModeSettings) -> bool {
    mode.output_dir.is_some()
        || mode.preset.is_some()
        || mode.quality.is_some()
        || mode.max_width.is_some()
        || mode.max_height.is_some()
}

fn copy_top_level_into_video(settings: &mut SessionSettings) {
    settings.video.output_dir = settings.output_dir.clone();
    settings.video.preset = settings.preset.clone();
    settings.video.quality = settings.quality.clone();
    settings.video.max_width = settings.max_width;
    settings.video.max_height = settings.max_height;
}

fn apply_legacy_video_fill(settings: &mut SessionSettings) {
    if settings.video.preset.is_none() && settings.preset.is_some() {
        copy_top_level_into_video(settings);
    }
}

fn mirror_video_to_top_level(settings: &mut SessionSettings) {
    if settings.video.output_dir.is_some() {
        settings.output_dir = settings.video.output_dir.clone();
    }
    settings.preset = settings.video.preset.clone();
    settings.quality = settings.video.quality.clone();
    settings.max_width = settings.video.max_width;
    settings.max_height = settings.video.max_height;
}

fn apply_legacy_top_level(current: &mut SessionSettings, incoming: &SessionSettings) -> bool {
    let has_top_level = incoming.output_dir.is_some()
        || incoming.preset.is_some()
        || incoming.quality.is_some()
        || incoming.max_width.is_some()
        || incoming.max_height.is_some();
    if !has_top_level {
        return false;
    }
    if incoming.output_dir.is_some() {
        current.output_dir = incoming.output_dir.clone();
    }
    current.preset = incoming.preset.clone();
    current.quality = incoming.quality.clone();
    current.max_width = incoming.max_width;
    current.max_height = incoming.max_height;
    true
}

pub fn merge_session_settings(current: &mut SessionSettings, incoming: SessionSettings) {
    if incoming_carries_lan_share(&incoming) {
        current.lan_share = LanShareSettings {
            enabled: incoming.lan_share.enabled,
            token: normalize_lan_token(&incoming.lan_share.token),
        };
    }
    if mode_is_populated(&incoming.video) {
        current.video = incoming.video;
        mirror_video_to_top_level(current);
    } else if apply_legacy_top_level(current, &incoming) {
        copy_top_level_into_video(current);
    }
    if mode_is_populated(&incoming.audio) {
        current.audio = incoming.audio;
    }
    if mode_is_populated(&incoming.document) {
        current.document = incoming.document;
    }
    if incoming.language.is_some() {
        current.language = incoming.language;
    }
}

fn incoming_carries_lan_share(incoming: &SessionSettings) -> bool {
    incoming.lan_share.enabled || !incoming.lan_share.token.is_empty()
}

pub fn settings_file(config_dir: &Path) -> PathBuf {
    config_dir.join("settings.json")
}

pub fn load_from_path(path: &Path) -> SessionSettings {
    let Ok(text) = std::fs::read_to_string(path) else {
        return SessionSettings::default();
    };
    let mut settings = serde_json::from_str(&text).unwrap_or_default();
    apply_legacy_video_fill(&mut settings);
    settings
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
            language: Some("zh-Hans".into()),
            video: ModeSettings {
                output_dir: Some("/tmp/out".into()),
                preset: Some("mp4-h265".into()),
                quality: Some("small".into()),
                max_width: Some(1280),
                max_height: Some(720),
            },
            ..Default::default()
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

    #[test]
    fn legacy_preset_fills_video_mode() {
        let dir = std::env::temp_dir().join(format!(
            "video-converter-legacy-preset-{}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = settings_file(&dir);
        std::fs::write(
            &path,
            r#"{
                "outputDir": "/tmp/out",
                "preset": "mp4-h265",
                "quality": "small",
                "maxWidth": 1280,
                "maxHeight": 720,
                "language": "zh-Hans"
            }"#,
        )
        .unwrap();

        let loaded = load_from_path(&path);
        assert_eq!(loaded.preset.as_deref(), Some("mp4-h265"));
        assert_eq!(loaded.video.preset.as_deref(), Some("mp4-h265"));
        assert_eq!(loaded.video.quality.as_deref(), Some("small"));
        assert_eq!(loaded.video.output_dir.as_deref(), Some("/tmp/out"));
        assert_eq!(loaded.video.max_width, Some(1280));
        assert_eq!(loaded.video.max_height, Some(720));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn saving_audio_does_not_clear_video() {
        let mut current = SessionSettings {
            output_dir: Some("/tmp/video".into()),
            preset: Some("mp4-h264".into()),
            quality: Some("standard".into()),
            max_width: Some(1920),
            max_height: Some(1080),
            language: Some("zh-Hans".into()),
            video: ModeSettings {
                output_dir: Some("/tmp/video".into()),
                preset: Some("mp4-h264".into()),
                quality: Some("standard".into()),
                max_width: Some(1920),
                max_height: Some(1080),
            },
            ..Default::default()
        };
        let incoming = SessionSettings {
            audio: ModeSettings {
                output_dir: Some("/tmp/audio".into()),
                preset: Some("mp3".into()),
                quality: Some("small".into()),
                ..Default::default()
            },
            ..Default::default()
        };

        merge_session_settings(&mut current, incoming);

        assert_eq!(current.video.preset.as_deref(), Some("mp4-h264"));
        assert_eq!(current.video.quality.as_deref(), Some("standard"));
        assert_eq!(current.video.output_dir.as_deref(), Some("/tmp/video"));
        assert_eq!(current.audio.preset.as_deref(), Some("mp3"));
        assert_eq!(current.audio.quality.as_deref(), Some("small"));
        assert_eq!(current.audio.output_dir.as_deref(), Some("/tmp/audio"));
        assert_eq!(current.language.as_deref(), Some("zh-Hans"));
        assert_eq!(current.document, ModeSettings::default());
    }

    #[test]
    fn saving_lan_does_not_clear_video() {
        let mut current = SessionSettings {
            output_dir: Some("/tmp/video".into()),
            preset: Some("mp4-h264".into()),
            quality: Some("standard".into()),
            max_width: Some(1920),
            max_height: Some(1080),
            language: Some("zh-Hans".into()),
            video: ModeSettings {
                output_dir: Some("/tmp/video".into()),
                preset: Some("mp4-h264".into()),
                quality: Some("standard".into()),
                max_width: Some(1920),
                max_height: Some(1080),
            },
            audio: ModeSettings {
                output_dir: Some("/tmp/audio".into()),
                preset: Some("mp3".into()),
                quality: Some("small".into()),
                ..Default::default()
            },
            document: ModeSettings {
                output_dir: Some("/tmp/doc".into()),
                preset: Some("pdf".into()),
                ..Default::default()
            },
            ..Default::default()
        };
        let incoming = SessionSettings {
            lan_share: LanShareSettings {
                enabled: true,
                token: "  secret  ".into(),
            },
            ..Default::default()
        };

        merge_session_settings(&mut current, incoming);

        assert_eq!(current.video.preset.as_deref(), Some("mp4-h264"));
        assert_eq!(current.video.quality.as_deref(), Some("standard"));
        assert_eq!(current.video.output_dir.as_deref(), Some("/tmp/video"));
        assert_eq!(current.audio.preset.as_deref(), Some("mp3"));
        assert_eq!(current.audio.output_dir.as_deref(), Some("/tmp/audio"));
        assert_eq!(current.document.preset.as_deref(), Some("pdf"));
        assert_eq!(current.document.output_dir.as_deref(), Some("/tmp/doc"));
        assert_eq!(current.language.as_deref(), Some("zh-Hans"));
        assert!(current.lan_share.enabled);
        assert_eq!(current.lan_share.token, "secret");
    }

    #[test]
    fn legacy_json_without_lan_share_defaults_off() {
        let dir = std::env::temp_dir().join(format!(
            "video-converter-legacy-lan-{}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = settings_file(&dir);
        std::fs::write(
            &path,
            r#"{
                "outputDir": "/tmp/out",
                "preset": "mp4-h265",
                "quality": "small",
                "maxWidth": 1280,
                "maxHeight": 720,
                "language": "zh-Hans"
            }"#,
        )
        .unwrap();

        let loaded = load_from_path(&path);
        assert!(!loaded.lan_share.enabled);
        assert_eq!(loaded.lan_share.token, "");
        let _ = std::fs::remove_dir_all(dir);
    }
}
