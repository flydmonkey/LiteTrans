use std::fs::File;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::convert::{document_source_kind, DocumentSourceKind};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeKind {
    DocumentImage,
    DocumentPdf,
    DocumentWord,
    DocumentExcel,
    Ffprobe,
}

pub fn probe_kind_for_path(path: &str) -> ProbeKind {
    match document_source_kind(path) {
        Some(DocumentSourceKind::Image) => ProbeKind::DocumentImage,
        Some(DocumentSourceKind::Pdf) => ProbeKind::DocumentPdf,
        Some(DocumentSourceKind::Word) => ProbeKind::DocumentWord,
        Some(DocumentSourceKind::Excel) => ProbeKind::DocumentExcel,
        None => ProbeKind::Ffprobe,
    }
}

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

fn path_extension(path: &str) -> Option<String> {
    Path::new(path)
        .extension()
        .and_then(|s| s.to_str())
        .map(|s| s.to_ascii_lowercase())
}

fn document_media(
    path: &str,
    container: Option<String>,
    importable: bool,
    error: Option<String>,
    page_count: Option<u32>,
) -> MediaInfo {
    MediaInfo {
        path: path.to_string(),
        duration_secs: None,
        container,
        video_codec: None,
        width: None,
        height: None,
        frame_rate: None,
        audio_codec: None,
        channels: None,
        importable,
        error,
        trim_start_secs: None,
        trim_end_secs: None,
        page_count,
        page_start: None,
        page_end: None,
    }
}

fn openable_document(path: &str) -> MediaInfo {
    if File::open(path).is_err() {
        return unreadable(path, "无法读取该文件");
    }
    document_media(path, path_extension(path), true, None, None)
}

fn pdf_page_count(path: &str) -> Result<u32, String> {
    let doc = lopdf::Document::load(path).map_err(|err| err.to_string())?;
    let count = u32::try_from(doc.get_pages().len()).unwrap_or(0);
    if count == 0 {
        Err("无法读取该文件".into())
    } else {
        Ok(count)
    }
}

fn probe_pdf(path: &str) -> MediaInfo {
    match pdf_page_count(path) {
        Ok(pages) => document_media(path, Some("pdf".into()), true, None, Some(pages)),
        Err(err) => unreadable(path, err),
    }
}

fn unsupported_document(path: &str) -> MediaInfo {
    document_media(
        path,
        path_extension(path),
        false,
        Some("error_unsupported_document".into()),
        None,
    )
}

pub fn probe_document(path: &str) -> Option<MediaInfo> {
    match probe_kind_for_path(path) {
        ProbeKind::Ffprobe => None,
        ProbeKind::DocumentImage | ProbeKind::DocumentWord => Some(openable_document(path)),
        ProbeKind::DocumentPdf => Some(probe_pdf(path)),
        ProbeKind::DocumentExcel => Some(unsupported_document(path)),
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

    #[test]
    fn probe_kind_routes_document_extensions() {
        assert_eq!(probe_kind_for_path("a.png"), ProbeKind::DocumentImage);
        assert_eq!(probe_kind_for_path("photo.JPG"), ProbeKind::DocumentImage);
        assert_eq!(probe_kind_for_path("scan.PDF"), ProbeKind::DocumentPdf);
        assert_eq!(probe_kind_for_path("notes.docx"), ProbeKind::DocumentWord);
        assert_eq!(probe_kind_for_path("sheet.xlsx"), ProbeKind::DocumentExcel);
        assert_eq!(probe_kind_for_path("legacy.xls"), ProbeKind::DocumentExcel);
        assert_eq!(probe_kind_for_path("old.doc"), ProbeKind::DocumentExcel);
        assert_eq!(probe_kind_for_path("clip.mp4"), ProbeKind::Ffprobe);
    }

    fn temp_file(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "video-converter-probe-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        std::fs::write(&path, bytes).unwrap();
        path
    }

    #[test]
    fn excel_and_doc_are_unsupported() {
        for name in ["sheet.xlsx", "legacy.xls", "old.doc"] {
            let info = crate::engine::probe_media(name);
            assert!(!info.importable, "{name}: {info:?}");
            assert_eq!(info.error.as_deref(), Some("error_unsupported_document"));
            assert_eq!(info.container.as_deref(), Some(name.rsplit('.').next().unwrap()));
        }
    }

    #[test]
    fn image_is_importable_without_decode() {
        let path = temp_file("photo.png", b"not a real png");
        let info = crate::engine::probe_media(path.to_str().unwrap());
        assert!(info.importable, "{info:?}");
        assert_eq!(info.container.as_deref(), Some("png"));
        assert!(info.error.is_none());
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn unreadable_image_is_not_importable() {
        let info = crate::engine::probe_media("/tmp/video-converter-missing-probe-image.png");
        assert!(!info.importable, "{info:?}");
        assert!(info.error.is_some());
    }

    #[test]
    fn word_docx_is_importable() {
        let path = temp_file("notes.docx", b"pk-placeholder");
        let info = crate::engine::probe_media(path.to_str().unwrap());
        assert!(info.importable, "{info:?}");
        assert_eq!(info.container.as_deref(), Some("docx"));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    fn write_two_page_pdf(path: &std::path::Path) {
        use lopdf::{dictionary, content::Content, Document, Object, Stream};

        let mut doc = Document::with_version("1.5");
        let pages_id = doc.new_object_id();
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Courier",
        });
        let resources_id = doc.add_object(dictionary! {
            "Font" => dictionary! { "F1" => font_id },
        });
        let content_id = doc.add_object(Stream::new(
            dictionary! {},
            Content { operations: vec![] }.encode().unwrap(),
        ));
        let page_ids: Vec<Object> = (0..2)
            .map(|_| {
                doc.add_object(dictionary! {
                    "Type" => "Page",
                    "Parent" => pages_id,
                    "Contents" => content_id,
                })
                .into()
            })
            .collect();
        doc.objects.insert(
            pages_id,
            dictionary! {
                "Type" => "Pages",
                "Kids" => page_ids,
                "Count" => 2,
                "Resources" => resources_id,
                "MediaBox" => vec![0.into(), 0.into(), 595.into(), 842.into()],
            }
            .into(),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);
        doc.save(path).unwrap();
    }

    #[test]
    fn pdf_page_count_from_lopdf() {
        let path = temp_file("scan.pdf", b"");
        write_two_page_pdf(&path);
        let info = crate::engine::probe_media(path.to_str().unwrap());
        assert!(info.importable, "{info:?}");
        assert_eq!(info.container.as_deref(), Some("pdf"));
        assert_eq!(info.page_count, Some(2));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }
}
