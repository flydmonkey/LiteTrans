use serde::{Deserialize, Serialize};

use crate::convert::source_image_format;
use crate::presets::OutputConfig;
use crate::probe::MediaInfo;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum JobStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Job {
    pub id: String,
    pub source_path: String,
    pub output_path: Option<String>,
    pub status: JobStatus,
    pub progress: f64,
    pub error: Option<String>,
    pub config: OutputConfig,
    pub media: MediaInfo,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub output_paths: Vec<String>,
    #[serde(default)]
    pub created_at_epoch_ms: Option<i64>,
    #[serde(default)]
    pub concat_source_paths: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnqueueReport {
    pub jobs: Vec<Job>,
    pub skipped: Vec<SkippedSource>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkippedSource {
    pub path: String,
    pub reason: String,
}

pub fn split_importable(sources: Vec<MediaInfo>) -> (Vec<MediaInfo>, Vec<SkippedSource>) {
    let mut accepted = Vec::new();
    let mut skipped = Vec::new();
    for source in sources {
        if source.importable {
            accepted.push(source);
        } else {
            skipped.push(SkippedSource {
                path: source.path,
                reason: source
                    .error
                    .unwrap_or_else(|| "无法转码该文件".into()),
            });
        }
    }
    (accepted, skipped)
}

pub fn config_for_source(config: &OutputConfig, media: &MediaInfo) -> OutputConfig {
    let mut next = config.clone();
    if media.trim_start_secs.is_some() || media.trim_end_secs.is_some() {
        next.trim_start_secs = media.trim_start_secs;
        next.trim_end_secs = media.trim_end_secs;
    }
    if next.preset == "image-compress" {
        if let Some(format) = source_image_format(&media.path) {
            next.container = Some(format.to_string());
        }
    }
    next
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::probe::MediaInfo;

    fn media(path: &str, importable: bool, error: Option<&str>) -> MediaInfo {
        MediaInfo {
            path: path.into(),
            duration_secs: Some(1.0),
            container: Some("mp4".into()),
            video_codec: Some("h264".into()),
            width: Some(64),
            height: Some(64),
            frame_rate: Some(25.0),
            audio_codec: Some("aac".into()),
            channels: Some(2),
            importable,
            error: error.map(|s| s.into()),
            trim_start_secs: None,
            trim_end_secs: None,
            page_count: None,
            page_start: None,
            page_end: None,
        }
    }

    #[test]
    fn skips_non_importable_sources() {
        let (accepted, skipped) = split_importable(vec![
            media("/a.mp4", true, None),
            media("/b.bin", false, Some("无法读取")),
        ]);
        assert_eq!(accepted.len(), 1);
        assert_eq!(skipped.len(), 1);
        assert_eq!(skipped[0].path, "/b.bin");
    }

    #[test]
    fn config_for_source_uses_file_trim() {
        let config = OutputConfig {
            preset: "mp4-h264".into(),
            trim_start_secs: Some(0.0),
            trim_end_secs: Some(9.0),
            ..Default::default()
        };
        let mut item = media("/a.mp4", true, None);
        item.trim_start_secs = Some(2.0);
        item.trim_end_secs = Some(4.0);
        let next = config_for_source(&config, &item);
        assert_eq!(next.trim_start_secs, Some(2.0));
        assert_eq!(next.trim_end_secs, Some(4.0));
    }

    #[test]
    fn image_compress_keeps_source_format() {
        let config = OutputConfig {
            preset: "image-compress".into(),
            ..Default::default()
        };
        let png = config_for_source(&config, &media("/tmp/photo.PNG", true, None));
        assert_eq!(png.container.as_deref(), Some("png"));
        let webp = config_for_source(&config, &media("/tmp/shot.webp", true, None));
        assert_eq!(webp.container.as_deref(), Some("webp"));
    }
}
