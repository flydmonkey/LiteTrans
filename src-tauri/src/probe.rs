use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MediaInfo {
    pub path: String,
    pub duration_secs: Option<f64>,
    pub container: Option<String>,
    pub video_codec: Option<String>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub frame_rate: Option<f64>,
    pub audio_codec: Option<String>,
    pub channels: Option<u32>,
    pub importable: bool,
    pub error: Option<String>,
    #[serde(default)]
    pub trim_start_secs: Option<f64>,
    #[serde(default)]
    pub trim_end_secs: Option<f64>,
    #[serde(default)]
    pub page_count: Option<u32>,
    #[serde(default)]
    pub page_start: Option<u32>,
    #[serde(default)]
    pub page_end: Option<u32>,
}

#[derive(Debug, Deserialize)]
struct ProbeJson {
    format: Option<ProbeFormat>,
    streams: Option<Vec<ProbeStream>>,
}

#[derive(Debug, Deserialize)]
struct ProbeFormat {
    format_name: Option<String>,
    duration: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ProbeStream {
    codec_type: Option<String>,
    codec_name: Option<String>,
    width: Option<u32>,
    height: Option<u32>,
    r_frame_rate: Option<String>,
    channels: Option<u32>,
}

pub fn parse_ffprobe_json(path: &str, json: &str) -> MediaInfo {
    let parsed: Result<ProbeJson, _> = serde_json::from_str(json);
    match parsed {
        Ok(probe) => map_probe(path, probe),
        Err(_) => MediaInfo {
            path: path.to_string(),
            duration_secs: None,
            container: None,
            video_codec: None,
            width: None,
            height: None,
            frame_rate: None,
            audio_codec: None,
            channels: None,
            importable: false,
            error: Some("无法解析媒体信息".into()),
            trim_start_secs: None,
            trim_end_secs: None,
            page_count: None,
            page_start: None,
            page_end: None,
        },
    }
}

fn map_probe(path: &str, probe: ProbeJson) -> MediaInfo {
    let streams = probe.streams.unwrap_or_default();
    let video = streams.iter().find(|s| s.codec_type.as_deref() == Some("video"));
    let audio = streams.iter().find(|s| s.codec_type.as_deref() == Some("audio"));
    let has_av = video.is_some() || audio.is_some();

    MediaInfo {
        path: path.to_string(),
        duration_secs: probe
            .format
            .as_ref()
            .and_then(|f| f.duration.as_ref())
            .and_then(|d| d.parse().ok()),
        container: probe.format.and_then(|f| f.format_name),
        video_codec: video.and_then(|s| s.codec_name.clone()),
        width: video.and_then(|s| s.width),
        height: video.and_then(|s| s.height),
        frame_rate: video.and_then(|s| parse_frame_rate(s.r_frame_rate.as_deref())),
        audio_codec: audio.and_then(|s| s.codec_name.clone()),
        channels: audio.and_then(|s| s.channels),
        importable: has_av,
        error: if has_av {
            None
        } else {
            Some("没有可转码的视频或音频流".into())
        },
        trim_start_secs: None,
        trim_end_secs: None,
        page_count: None,
        page_start: None,
        page_end: None,
    }
}

fn parse_frame_rate(value: Option<&str>) -> Option<f64> {
    let value = value?;
    if let Some((num, den)) = value.split_once('/') {
        let num: f64 = num.parse().ok()?;
        let den: f64 = den.parse().ok()?;
        if den == 0.0 {
            return None;
        }
        return Some(num / den);
    }
    value.parse().ok()
}

pub fn unreadable(path: &str, reason: impl Into<String>) -> MediaInfo {
    MediaInfo {
        path: path.to_string(),
        duration_secs: None,
        container: None,
        video_codec: None,
        width: None,
        height: None,
        frame_rate: None,
        audio_codec: None,
        channels: None,
        importable: false,
        error: Some(reason.into()),
        trim_start_secs: None,
        trim_end_secs: None,
        page_count: None,
        page_start: None,
        page_end: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_video_and_audio() {
        let json = r#"{
            "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "12.5"},
            "streams": [
                {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080, "r_frame_rate": "30/1"},
                {"codec_type": "audio", "codec_name": "aac", "channels": 2}
            ]
        }"#;
        let info = parse_ffprobe_json("/tmp/a.mp4", json);
        assert!(info.importable);
        assert_eq!(info.video_codec.as_deref(), Some("h264"));
        assert_eq!(info.width, Some(1920));
        assert_eq!(info.audio_codec.as_deref(), Some("aac"));
        assert_eq!(info.duration_secs, Some(12.5));
        assert_eq!(info.frame_rate, Some(30.0));
    }

    #[test]
    fn rejects_missing_streams() {
        let json = r#"{"format": {"format_name": "data"}, "streams": []}"#;
        let info = parse_ffprobe_json("/tmp/a.bin", json);
        assert!(!info.importable);
        assert!(info.error.unwrap().contains("没有可转码"));
    }
}
