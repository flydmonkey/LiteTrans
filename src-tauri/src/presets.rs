use serde::{Deserialize, Serialize};

use crate::convert::VIDEO_CONCAT_PRESET;

pub const DEFAULT_PRESET: &str = "mp4-h264";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PresetInfo {
    pub id: String,
    pub label: String,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct OutputConfig {
    #[serde(default = "default_preset_id")]
    pub preset: String,
    pub container: Option<String>,
    pub video_encoder: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
    pub video_bitrate_kbps: Option<u32>,
    pub frame_rate: Option<f64>,
    pub audio_encoder: Option<String>,
    pub audio_bitrate_kbps: Option<u32>,
    pub keep_audio: Option<bool>,
    pub quality: Option<String>,
    pub trim_start_secs: Option<f64>,
    pub trim_end_secs: Option<f64>,
}

fn default_preset_id() -> String {
    DEFAULT_PRESET.to_string()
}

#[derive(Debug, Clone, PartialEq)]
pub struct ResolvedConfig {
    pub preset: String,
    pub container: String,
    pub extension: String,
    pub video_encoder: Option<String>,
    pub audio_encoder: Option<String>,
    pub max_width: Option<u32>,
    pub max_height: Option<u32>,
    pub video_bitrate_kbps: Option<u32>,
    pub frame_rate: Option<f64>,
    pub audio_bitrate_kbps: Option<u32>,
    pub keep_audio: bool,
    pub quality: String,
    pub trim_start_secs: Option<f64>,
    pub trim_end_secs: Option<f64>,
}

pub fn list_presets() -> Vec<PresetInfo> {
    vec![
        PresetInfo {
            id: "mp4-h264".into(),
            label: "MP4 / H.264".into(),
            description: "兼容性最好，适合分享和上传".into(),
        },
        PresetInfo {
            id: "mp4-h265".into(),
            label: "MP4 / H.265".into(),
            description: "同样是 MP4，编码更新".into(),
        },
        PresetInfo {
            id: "mp4-copy".into(),
            label: "MP4 / 不重编码".into(),
            description: "只换外壳，不重新压缩，速度最快".into(),
        },
        PresetInfo {
            id: "webm-vp9".into(),
            label: "WebM / VP9".into(),
            description: "适合网页播放".into(),
        },
        PresetInfo {
            id: "mkv-copy-friendly".into(),
            label: "MKV / H.264".into(),
            description: "通用容器，重编码以保证可播放".into(),
        },
        PresetInfo {
            id: "mov-h264".into(),
            label: "MOV / H.264".into(),
            description: "苹果设备和剪辑软件常用".into(),
        },
        PresetInfo {
            id: "mkv-h265".into(),
            label: "MKV / H.265".into(),
            description: "适合封装保存".into(),
        },
        PresetInfo {
            id: "avi-mpeg4".into(),
            label: "AVI / MPEG-4".into(),
            description: "旧电脑和投影常用".into(),
        },
        PresetInfo {
            id: "gif".into(),
            label: "GIF".into(),
            description: "短视频转成动图".into(),
        },
        PresetInfo {
            id: "audio-mp3".into(),
            label: "仅音频 / MP3".into(),
            description: "提取音频为 MP3".into(),
        },
        PresetInfo {
            id: "audio-aac".into(),
            label: "仅音频 / M4A".into(),
            description: "提取音频为 AAC".into(),
        },
        PresetInfo {
            id: "audio-wav".into(),
            label: "Audio / WAV".into(),
            description: "Extract audio as lossless WAV".into(),
        },
        PresetInfo {
            id: "audio-flac".into(),
            label: "Audio / FLAC".into(),
            description: "Extract audio as lossless FLAC".into(),
        },
        PresetInfo {
            id: "audio-ogg".into(),
            label: "Audio / OGG".into(),
            description: "Extract audio as OGG Vorbis".into(),
        },
        PresetInfo {
            id: "audio-amr".into(),
            label: "Audio / AMR".into(),
            description: "Extract audio as AMR-NB".into(),
        },
        PresetInfo {
            id: VIDEO_CONCAT_PRESET.into(),
            label: "Video Concat / MP4".into(),
            description: "Concatenate multiple videos into MP4".into(),
        },
    ]
}

pub fn resolve_config(config: &OutputConfig) -> Result<ResolvedConfig, String> {
    let preset = if config.preset.trim().is_empty() {
        DEFAULT_PRESET.to_string()
    } else {
        config.preset.clone()
    };

    let (container, video, audio, keep_audio) = match preset.as_str() {
        "mp4-h264" => ("mp4", Some("h264"), Some("aac"), true),
        "mp4-h265" => ("mp4", Some("h265"), Some("aac"), true),
        "mp4-copy" => ("mp4", Some("copy"), Some("copy"), true),
        "mov-h264" => ("mov", Some("h264"), Some("aac"), true),
        "webm-vp9" => ("webm", Some("vp9"), Some("opus"), true),
        "mkv-copy-friendly" => ("mkv", Some("h264"), Some("aac"), true),
        "mkv-h265" => ("mkv", Some("h265"), Some("aac"), true),
        "avi-mpeg4" => ("avi", Some("mpeg4"), Some("mp3"), true),
        "gif" => ("gif", Some("gif"), None, false),
        "audio-mp3" => ("mp3", None, Some("mp3"), true),
        "audio-aac" => ("m4a", None, Some("aac"), true),
        "audio-wav" => ("wav", None, Some("pcm_s16le"), true),
        "audio-flac" => ("flac", None, Some("flac"), true),
        "audio-ogg" => ("ogg", None, Some("libvorbis"), true),
        "audio-amr" => ("amr", None, Some("amr_nb"), true),
        VIDEO_CONCAT_PRESET => ("mp4", Some("h264"), Some("aac"), true),
        "custom" => ("mp4", Some("h264"), Some("aac"), true),
        other => return Err(format!("未知预设：{other}")),
    };

    let container = config
        .container
        .clone()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| container.to_string());
    let video_encoder = config
        .video_encoder
        .clone()
        .or_else(|| video.map(|v| v.to_string()));
    let audio_encoder = config
        .audio_encoder
        .clone()
        .or_else(|| audio.map(|v| v.to_string()));

    let quality = normalize_quality(config.quality.as_deref());
    let audio_bitrate_kbps = config
        .audio_bitrate_kbps
        .or(Some(audio_bitrate_for_quality(&quality)));

    if preset.starts_with("audio-") {
        let (audio_only, encoder) = match preset.as_str() {
            "audio-aac" => ("m4a", "aac"),
            "audio-mp3" => ("mp3", "mp3"),
            "audio-wav" => ("wav", "pcm_s16le"),
            "audio-flac" => ("flac", "flac"),
            "audio-ogg" => ("ogg", "libvorbis"),
            "audio-amr" => ("amr", "amr_nb"),
            other => return Err(format!("未知预设：{other}")),
        };
        let audio_bitrate_kbps = if matches!(preset.as_str(), "audio-wav" | "audio-flac") {
            config.audio_bitrate_kbps
        } else {
            audio_bitrate_kbps
        };
        return Ok(ResolvedConfig {
            extension: extension_for(audio_only)?,
            preset,
            container: audio_only.into(),
            video_encoder: None,
            audio_encoder: Some(encoder.into()),
            max_width: None,
            max_height: None,
            video_bitrate_kbps: None,
            frame_rate: None,
            audio_bitrate_kbps,
            keep_audio: true,
            quality,
            trim_start_secs: config.trim_start_secs,
            trim_end_secs: config.trim_end_secs,
        });
    }

    let copy_video = video_encoder.as_deref() == Some("copy");
    Ok(ResolvedConfig {
        extension: extension_for(&container)?,
        preset,
        container,
        video_encoder,
        audio_encoder,
        max_width: if copy_video { None } else { config.max_width },
        max_height: if copy_video { None } else { config.max_height },
        video_bitrate_kbps: config.video_bitrate_kbps,
        frame_rate: if copy_video { None } else { config.frame_rate },
        audio_bitrate_kbps,
        keep_audio: config.keep_audio.unwrap_or(keep_audio),
        quality,
        trim_start_secs: config.trim_start_secs,
        trim_end_secs: config.trim_end_secs,
    })
}

pub fn normalize_quality(value: Option<&str>) -> String {
    match value {
        Some("original") | Some("high") => "original".into(),
        Some("small") => "small".into(),
        _ => "standard".into(),
    }
}

fn audio_bitrate_for_quality(quality: &str) -> u32 {
    match quality {
        "original" | "high" => 320,
        "small" => 128,
        _ => 192,
    }
}

pub fn extension_for(container: &str) -> Result<String, String> {
    match container {
        "mp4" => Ok("mp4".into()),
        "webm" => Ok("webm".into()),
        "mkv" => Ok("mkv".into()),
        "mov" => Ok("mov".into()),
        "avi" => Ok("avi".into()),
        "gif" => Ok("gif".into()),
        "mp3" => Ok("mp3".into()),
        "m4a" => Ok("m4a".into()),
        "wav" => Ok("wav".into()),
        "flac" => Ok("flac".into()),
        "ogg" => Ok("ogg".into()),
        "amr" => Ok("amr".into()),
        other => Err(format!("不支持的容器：{other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_preset_is_mp4_h264() {
        let resolved = resolve_config(&OutputConfig::default()).unwrap();
        assert_eq!(resolved.preset, "mp4-h264");
        assert_eq!(resolved.container, "mp4");
        assert_eq!(resolved.video_encoder.as_deref(), Some("h264"));
        assert_eq!(resolved.audio_encoder.as_deref(), Some("aac"));
    }

    #[test]
    fn lists_required_presets() {
        let ids: Vec<_> = list_presets().into_iter().map(|p| p.id).collect();
        assert!(ids.contains(&"mp4-h264".into()));
        assert!(ids.contains(&"mp4-h265".into()));
        assert!(ids.contains(&"mp4-copy".into()));
        assert!(ids.contains(&"webm-vp9".into()));
        assert!(ids.contains(&"mkv-copy-friendly".into()));
        assert!(ids.contains(&"audio-mp3".into()));
        assert!(ids.contains(&"mov-h264".into()));
        assert!(ids.contains(&"avi-mpeg4".into()));
        assert!(ids.contains(&"gif".into()));
        assert!(ids.contains(&"audio-aac".into()));
    }

    #[test]
    fn quality_defaults_to_standard() {
        let resolved = resolve_config(&OutputConfig::default()).unwrap();
        assert_eq!(resolved.quality, "standard");
    }

    #[test]
    fn mp4_copy_preset_does_not_reencode() {
        let resolved = resolve_config(&OutputConfig {
            preset: "mp4-copy".into(),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(resolved.container, "mp4");
        assert_eq!(resolved.video_encoder.as_deref(), Some("copy"));
        assert_eq!(resolved.audio_encoder.as_deref(), Some("copy"));
    }

    #[test]
    fn resolves_new_audio_and_concat() {
        for (id, ext) in [
            ("audio-wav", "wav"),
            ("audio-flac", "flac"),
            ("audio-ogg", "ogg"),
            ("audio-amr", "amr"),
            ("video-concat", "mp4"),
        ] {
            let resolved =
                resolve_config(&OutputConfig { preset: id.into(), ..Default::default() }).unwrap();
            assert_eq!(resolved.extension, ext);
            assert!(resolved.video_encoder.is_none() || id == "video-concat");
        }
        assert!(resolve_config(&OutputConfig {
            preset: "nope".into(),
            ..Default::default()
        })
        .is_err());
    }
}
