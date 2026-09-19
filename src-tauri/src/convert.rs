use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::naming::source_stem;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ConvertMode {
    Video,
    Audio,
    Document,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DocumentSourceKind {
    Image,
    Pdf,
    Word,
    Excel,
}

pub const VIDEO_CONCAT_PRESET: &str = "video-concat";
pub const VIDEO_CONCAT_MAX_SOURCES: usize = 20;

pub fn can_start(
    importable: usize,
    probing: bool,
    transcoding: bool,
    output_ready: bool,
    preset: &str,
    source_count: usize,
) -> bool {
    let concat = is_video_concat_preset(preset);
    let minimum = if concat { 2 } else { 1 };
    let maximum = if concat {
        VIDEO_CONCAT_MAX_SOURCES
    } else {
        usize::MAX
    };
    let no_rejects = !concat || importable == source_count;
    importable >= minimum
        && importable <= maximum
        && no_rejects
        && !probing
        && !transcoding
        && output_ready
}

pub fn is_video_concat_preset(preset: &str) -> bool {
    preset == VIDEO_CONCAT_PRESET
}

pub fn allows_trim(preset: &str) -> bool {
    preset != "mp4-copy" && !is_video_concat_preset(preset)
}

pub fn document_source_kind(file_name: &str) -> Option<DocumentSourceKind> {
    let ext = Path::new(file_name)
        .extension()
        .and_then(|s| s.to_str())
        .map(|s| s.to_ascii_lowercase())?;
    match ext.as_str() {
        "jpg" | "jpeg" | "png" | "webp" | "bmp" | "gif" => Some(DocumentSourceKind::Image),
        "pdf" => Some(DocumentSourceKind::Pdf),
        "docx" => Some(DocumentSourceKind::Word),
        "xlsx" | "xls" | "doc" => Some(DocumentSourceKind::Excel),
        _ => None,
    }
}

pub fn same_document_kind(existing: &[&str], incoming: &str) -> bool {
    if existing.is_empty() {
        return true;
    }
    let Some(incoming_kind) = document_source_kind(incoming) else {
        return false;
    };
    existing
        .iter()
        .all(|name| document_source_kind(name) == Some(incoming_kind))
}

pub fn clamp_page_range(start: u32, end: u32, page_count: u32) -> (u32, u32) {
    let pages = page_count.max(1);
    let lo = start.clamp(1, pages);
    let hi = end.clamp(lo, pages);
    (lo, hi)
}

pub fn default_document_preset(kind: DocumentSourceKind) -> &'static str {
    match kind {
        DocumentSourceKind::Image => "image-jpg",
        DocumentSourceKind::Pdf => "pdf-image",
        DocumentSourceKind::Word => "office-pdf",
        DocumentSourceKind::Excel => unreachable!("Excel sources have no default preset"),
    }
}

fn static_image_extension(image_format: Option<&str>) -> Option<&'static str> {
    match image_format.filter(|s| !s.is_empty())? {
        "jpg" | "jpeg" => Some("jpg"),
        "png" => Some("png"),
        "webp" => Some("webp"),
        "bmp" => Some("bmp"),
        "gif" => Some("gif"),
        _ => None,
    }
}

pub fn source_image_format(path: &str) -> Option<&'static str> {
    let ext = Path::new(path)
        .extension()
        .and_then(|s| s.to_str())
        .map(|s| s.to_ascii_lowercase())?;
    static_image_extension(Some(&ext))
}

pub fn document_extension(preset: &str, image_format: Option<&str>) -> &'static str {
    match preset {
        "image-jpg" => "jpg",
        "image-png" => "png",
        "image-webp" => "webp",
        "image-bmp" => "bmp",
        "image-gif" => "gif",
        "image-compress" | "pdf-image" => static_image_extension(image_format).unwrap_or("jpg"),
        "pdf-txt" => "txt",
        "pdf-compress" | "pdf-split" | "office-pdf" => "pdf",
        _ => static_image_extension(image_format).unwrap_or("bin"),
    }
}

pub fn document_output_file_name(stem: &str, index: usize, total: usize, ext: &str) -> String {
    if total <= 1 {
        format!("{stem}.{ext}")
    } else {
        format!("{stem}-{:03}.{ext}", index)
    }
}

/// How many output files a document preset will write for an inclusive 1-based page range.
pub fn planned_output_count(preset: &str, page_start: u32, page_end: u32) -> usize {
    match preset {
        "pdf-image" | "pdf-split" => {
            let (lo, hi) = if page_end >= page_start {
                (page_start, page_end)
            } else {
                (page_end, page_start)
            };
            (hi.saturating_sub(lo) + 1) as usize
        }
        _ => 1,
    }
}

pub fn document_output_names(
    stem: &str,
    preset: &str,
    image_format: Option<&str>,
    page_start: u32,
    page_end: u32,
) -> Vec<String> {
    let ext = document_extension(preset, image_format);
    let total = planned_output_count(preset, page_start, page_end).max(1);
    (1..=total)
        .map(|i| document_output_file_name(stem, i, total, ext))
        .collect()
}

pub fn allocate_document_output_paths(
    output_dir: &Path,
    stem: &str,
    preset: &str,
    image_format: Option<&str>,
    page_start: u32,
    page_end: u32,
    exists: impl Fn(&Path) -> bool,
) -> Vec<PathBuf> {
    let mut n = 0u32;
    loop {
        let candidate = if n == 0 {
            stem.to_string()
        } else {
            format!("{stem}-{n}")
        };
        let paths: Vec<PathBuf> = document_output_names(
            &candidate,
            preset,
            image_format,
            page_start,
            page_end,
        )
        .into_iter()
        .map(|name| output_dir.join(name))
        .collect();
        if paths.iter().all(|path| !exists(path)) {
            return paths;
        }
        n += 1;
    }
}

pub fn even_dimension(value: u32) -> u32 {
    value.max(2) - (value % 2)
}

pub fn concat_output_stem(display_name: &str) -> String {
    format!("{}-merged", source_stem(display_name))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn can_start_concat_needs_two_clean_clips() {
        assert!(!can_start(1, false, false, true, "video-concat", 1));
        assert!(can_start(2, false, false, true, "video-concat", 2));
        assert!(!can_start(2, false, false, true, "video-concat", 3)); // 有标红
        assert!(!can_start(21, false, false, true, "video-concat", 21));
        assert!(can_start(1, false, false, true, "mp4-h264", 1));
    }

    #[test]
    fn document_kind_and_mix() {
        assert_eq!(document_source_kind("a.JPG"), Some(DocumentSourceKind::Image));
        assert_eq!(document_source_kind("a.docx"), Some(DocumentSourceKind::Word));
        assert_eq!(document_source_kind("a.doc"), Some(DocumentSourceKind::Excel)); // 旧 doc 走 Excel 桶：无预设、标红
        assert_eq!(document_source_kind("a.xlsx"), Some(DocumentSourceKind::Excel));
        assert!(same_document_kind(&["a.png"], "b.jpg"));
        assert!(!same_document_kind(&["a.png"], "b.pdf"));
        assert_eq!(clamp_page_range(0, 99, 5), (1, 5));
        assert_eq!(default_document_preset(DocumentSourceKind::Pdf), "pdf-image");
        assert_eq!(document_extension("pdf-split", None), "pdf");
        assert_eq!(document_extension("image-compress", Some("png")), "png");
        assert_eq!(document_extension("image-compress", Some("webp")), "webp");
        assert_eq!(source_image_format("photo.PNG"), Some("png"));
        assert_eq!(source_image_format("shot.jpeg"), Some("jpg"));
        assert_eq!(document_output_file_name("scan", 2, 4, "jpg"), "scan-002.jpg");
        assert_eq!(even_dimension(1921), 1920);
        assert_eq!(concat_output_stem("clip.mp4"), "clip-merged");
        assert!(!allows_trim("video-concat"));
        assert!(!allows_trim("mp4-copy"));
        assert!(allows_trim("audio-mp3"));
    }

    #[test]
    fn planned_output_count_pdf_split_pages() {
        assert_eq!(planned_output_count("pdf-split", 1, 4), 4);
        assert_eq!(planned_output_count("pdf-image", 1, 4), 4);
        assert_eq!(planned_output_count("pdf-txt", 1, 4), 1);
        assert_eq!(planned_output_count("image-jpg", 1, 4), 1);
        assert_eq!(
            document_output_names("scan", "pdf-split", None, 1, 4),
            vec![
                "scan-001.pdf".to_string(),
                "scan-002.pdf".to_string(),
                "scan-003.pdf".to_string(),
                "scan-004.pdf".to_string(),
            ]
        );
    }
}
