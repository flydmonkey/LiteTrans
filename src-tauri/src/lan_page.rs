use std::path::Path;

use crate::history::{history_segment_for, HistorySegment};
use crate::lan::{job_output_paths, lan_query_encode};
use crate::presets::resolve_config;
use crate::queue::{Job, JobStatus};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanHistoryCopy {
    pub warning: String,
    pub video: String,
    pub audio: String,
    pub document: String,
    pub image: String,
    pub empty_video: String,
    pub empty_audio: String,
    pub empty_document: String,
    pub empty_image: String,
    pub download: String,
    pub download_named: String,
    pub download_index: String,
    pub status_queued: String,
    pub status_running: String,
    pub status_completed: String,
    pub status_failed: String,
    pub status_cancelled: String,
    pub need_token: String,
    pub preview_failed: String,
    pub download_to_open: String,
}

impl LanHistoryCopy {
    pub fn english() -> Self {
        Self {
            warning: "Anyone on this network who has the address can view history and download finished files."
                .into(),
            video: "Video".into(),
            audio: "Audio".into(),
            document: "Documents".into(),
            image: "Images".into(),
            empty_video: "No video history yet".into(),
            empty_audio: "No audio history yet".into(),
            empty_document: "No document history yet".into(),
            empty_image: "No image history yet".into(),
            download: "Download".into(),
            download_named: "Download %1$s".into(),
            download_index: "Download #%1$d".into(),
            status_queued: "Queued".into(),
            status_running: "Converting".into(),
            status_completed: "Done".into(),
            status_failed: "Failed".into(),
            status_cancelled: "Cancelled".into(),
            need_token: "Password required".into(),
            preview_failed: "Can't preview. Download the file instead.".into(),
            download_to_open: "Download and open it on your computer.".into(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LanLibraryTab {
    Video,
    Audio,
    Image,
    Document,
}

impl LanLibraryTab {
    fn all() -> [Self; 4] {
        [Self::Video, Self::Audio, Self::Image, Self::Document]
    }

    fn wire_name(self) -> &'static str {
        match self {
            Self::Video => "video",
            Self::Audio => "audio",
            Self::Image => "image",
            Self::Document => "document",
        }
    }

    fn label(self, copy: &LanHistoryCopy) -> &str {
        match self {
            Self::Video => &copy.video,
            Self::Audio => &copy.audio,
            Self::Image => &copy.image,
            Self::Document => &copy.document,
        }
    }

    fn empty_label(self, copy: &LanHistoryCopy) -> &str {
        match self {
            Self::Video => &copy.empty_video,
            Self::Audio => &copy.empty_audio,
            Self::Image => &copy.empty_image,
            Self::Document => &copy.empty_document,
        }
    }
}

const IMAGE_EXTS: &[&str] = &["jpg", "jpeg", "png", "webp", "gif", "bmp"];

pub fn lan_library_tab(job: &Job, path: &str) -> LanLibraryTab {
    match history_segment_for(job) {
        HistorySegment::Video => LanLibraryTab::Video,
        HistorySegment::Audio => LanLibraryTab::Audio,
        HistorySegment::Document => {
            if is_image_ext(path) {
                LanLibraryTab::Image
            } else {
                LanLibraryTab::Document
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LanPreviewKind {
    Video,
    Audio,
    Pdf,
    Image,
    File,
}

impl LanPreviewKind {
    fn wire_name(self) -> &'static str {
        match self {
            Self::Video => "video",
            Self::Audio => "audio",
            Self::Pdf => "pdf",
            Self::Image => "image",
            Self::File => "file",
        }
    }
}

struct LanLibraryItem {
    job_id: String,
    index: usize,
    kind: LanPreviewKind,
    tab: LanLibraryTab,
    label: String,
    detail: String,
    needs_index: bool,
}

fn file_ext(path: &str) -> String {
    Path::new(path)
        .extension()
        .and_then(|ext| ext.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
}

fn is_image_ext(path: &str) -> bool {
    IMAGE_EXTS.contains(&file_ext(path).as_str())
}

fn file_name(path: &str) -> String {
    Path::new(path)
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or(path)
        .to_string()
}

fn lan_preview_kind(file_name: &str) -> LanPreviewKind {
    match file_ext(file_name).as_str() {
        "mp4" | "mov" | "mkv" | "webm" | "avi" => LanPreviewKind::Video,
        "mp3" | "m4a" | "wav" | "ogg" | "flac" | "amr" => LanPreviewKind::Audio,
        "pdf" => LanPreviewKind::Pdf,
        ext if IMAGE_EXTS.contains(&ext) => LanPreviewKind::Image,
        _ => LanPreviewKind::File,
    }
}

fn item_format(job: &Job, label: &str) -> String {
    resolve_config(&job.config)
        .ok()
        .map(|resolved| resolved.container)
        .filter(|container| !container.is_empty())
        .unwrap_or_else(|| {
            let ext = file_ext(label);
            if ext.is_empty() {
                job.config.preset.clone()
            } else {
                ext
            }
        })
}

fn history_date_label(epoch_ms: Option<i64>) -> Option<String> {
    let ms = epoch_ms.filter(|&value| value > 0)?;
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let tod = secs.rem_euclid(86_400) as u32;
    let hour = tod / 3600;
    let minute = (tod % 3600) / 60;
    let (year, month, day) = civil_from_days(days);
    Some(format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}"))
}

fn civil_from_days(z: i64) -> (i32, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u32;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if month <= 2 { year + 1 } else { year };
    (year as i32, month, day)
}

fn item_detail(format: &str, created_at_epoch_ms: Option<i64>) -> String {
    let mut bits = Vec::new();
    if !format.is_empty() {
        bits.push(format.to_string());
    }
    if let Some(date) = history_date_label(created_at_epoch_ms) {
        bits.push(date);
    }
    bits.join(" · ")
}

fn lan_library_items(jobs: &[Job], file_exists: impl Fn(&str) -> bool) -> Vec<LanLibraryItem> {
    let mut out = Vec::new();
    for job in jobs.iter().rev() {
        if job.status != JobStatus::Completed {
            continue;
        }
        let paths = job_output_paths(job);
        for (index, path) in paths.iter().enumerate() {
            if path.trim().is_empty() || !file_exists(path) {
                continue;
            }
            let label = file_name(path);
            let format = item_format(job, &label);
            out.push(LanLibraryItem {
                job_id: job.id.clone(),
                index,
                kind: lan_preview_kind(&label),
                tab: lan_library_tab(job, path),
                label,
                detail: item_detail(&format, job.created_at_epoch_ms),
                needs_index: paths.len() > 1 || index > 0,
            });
        }
    }
    out
}

fn lan_library_items_for(items: &[LanLibraryItem], tab: LanLibraryTab) -> Vec<&LanLibraryItem> {
    items.iter().filter(|item| item.tab == tab).collect()
}

fn lan_default_library_tab(items: &[LanLibraryItem]) -> LanLibraryTab {
    LanLibraryTab::all()
        .into_iter()
        .find(|tab| items.iter().any(|item| item.tab == *tab))
        .unwrap_or(LanLibraryTab::Video)
}

fn escape_html(raw: &str) -> String {
    raw.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

fn js_string(raw: &str) -> String {
    let mut out = String::from("\"");
    for ch in raw.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '<' => out.push_str("\\u003c"),
            _ => out.push(ch),
        }
    }
    out.push('"');
    out
}

fn lan_history_download_href(
    job_id: &str,
    index: usize,
    needs_index: bool,
    token: &str,
    kind: &str,
) -> String {
    let path = if index > 0 || needs_index {
        format!("/{kind}/{job_id}/{index}")
    } else {
        format!("/{kind}/{job_id}")
    };
    if token.is_empty() {
        path
    } else {
        format!("{path}?k={}", lan_query_encode(token))
    }
}

fn append_library_item(html: &mut String, item: &LanLibraryItem, token: &str, download_label: &str, selected: bool) {
    let media = lan_history_download_href(&item.job_id, item.index, item.needs_index, "", "m");
    let download = lan_history_download_href(&item.job_id, item.index, item.needs_index, token, "d");
    html.push_str("<div class=\"item");
    if item.tab == LanLibraryTab::Image {
        html.push_str(" thumb");
    }
    if selected {
        html.push_str(" selected");
    }
    html.push_str("\" data-media=\"");
    html.push_str(&escape_html(&media));
    html.push_str("\" data-download=\"");
    html.push_str(&escape_html(&download));
    html.push_str("\" data-kind=\"");
    html.push_str(item.kind.wire_name());
    html.push_str("\" data-id=\"");
    html.push_str(&escape_html(&item.job_id));
    html.push_str("\" data-index=\"");
    html.push_str(&item.index.to_string());
    html.push_str("\" data-tab=\"");
    html.push_str(item.tab.wire_name());
    html.push_str("\" data-label=\"");
    html.push_str(&escape_html(&item.label));
    html.push_str("\" data-info=\"");
    html.push_str(&escape_html(&item.detail));
    html.push_str("\">");
    if item.tab == LanLibraryTab::Image {
        let thumb = lan_history_download_href(&item.job_id, item.index, item.needs_index, token, "m");
        html.push_str("<img class=\"thumb-src\" alt=\"\" src=\"");
        html.push_str(&escape_html(&thumb));
        html.push_str("\">");
    }
    html.push_str("<span class=\"name\">");
    html.push_str(&escape_html(&item.label));
    html.push_str("</span>");
    if !item.detail.is_empty() {
        html.push_str("<span class=\"info\">");
        html.push_str(&escape_html(&item.detail));
        html.push_str("</span>");
    }
    html.push_str("<a class=\"row-dl\" href=\"");
    html.push_str(&escape_html(&download));
    html.push_str("\">");
    html.push_str(&escape_html(download_label));
    html.push_str("</a></div>");
}

pub fn render_lan_history_html(
    jobs: &[Job],
    token: &str,
    copy: &LanHistoryCopy,
    file_exists: impl Fn(&str) -> bool,
) -> String {
    let items = lan_library_items(jobs, file_exists);
    let default_tab = lan_default_library_tab(&items);
    let mut html = String::new();
    html.push_str("<!DOCTYPE html><html><head>");
    html.push_str("<meta charset=\"utf-8\">");
    html.push_str("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
    html.push_str("<title>LiteTrans</title>");
    html.push_str("<link rel=\"icon\" type=\"image/png\" href=\"/favicon.png\">");
    html.push_str("<style>");
    html.push_str(include_str!("lan_history.css"));
    html.push_str("</style></head><body>");
    html.push_str("<header><strong>LiteTrans</strong>");
    if token.is_empty() {
        html.push_str("<p class=\"warn\">");
        html.push_str(&escape_html(&copy.warning));
        html.push_str("</p>");
    }
    html.push_str("</header>");
    html.push_str("<nav class=\"tabs\" role=\"tablist\">");
    for tab in LanLibraryTab::all() {
        let count = lan_library_items_for(&items, tab).len();
        let on = tab == default_tab;
        html.push_str("<button type=\"button\" data-tab-btn=\"");
        html.push_str(tab.wire_name());
        html.push('"');
        if on {
            html.push_str(" class=\"on\"");
        }
        html.push_str(" aria-selected=\"");
        html.push_str(if on { "true" } else { "false" });
        html.push_str("\" role=\"tab\">");
        html.push_str(&escape_html(tab.label(copy)));
        html.push(' ');
        html.push_str(&count.to_string());
        html.push_str("</button>");
    }
    html.push_str("</nav>");
    html.push_str("<main>");
    html.push_str("<div class=\"rail\">");
    for tab in LanLibraryTab::all() {
        let tab_items = lan_library_items_for(&items, tab);
        html.push_str("<div class=\"pane\" data-pane=\"");
        html.push_str(tab.wire_name());
        html.push('"');
        if tab != default_tab {
            html.push_str(" hidden");
        }
        html.push('>');
        if tab_items.is_empty() {
            html.push_str("<p class=\"empty\">");
            html.push_str(&escape_html(tab.empty_label(copy)));
            html.push_str("</p>");
        } else {
            if tab == LanLibraryTab::Image {
                html.push_str("<div class=\"thumbs\">");
            }
            for (index, item) in tab_items.iter().enumerate() {
                append_library_item(
                    &mut html,
                    item,
                    token,
                    &copy.download,
                    tab == default_tab && index == 0,
                );
            }
            if tab == LanLibraryTab::Image {
                html.push_str("</div>");
            }
        }
        html.push_str("</div>");
    }
    html.push_str("</div>");
    html.push_str("<div class=\"stage\">");
    html.push_str("<div class=\"player\">");
    html.push_str("<video controls></video>");
    html.push_str("<audio controls></audio>");
    html.push_str("<img alt=\"\">");
    html.push_str("<iframe title=\"preview\"></iframe>");
    html.push_str("</div>");
    html.push_str("<div class=\"meta\"><p id=\"hint\"></p></div></div></main>");
    html.push_str("<script>");
    html.push_str("var previewFailed=");
    html.push_str(&js_string(&copy.preview_failed));
    html.push(';');
    html.push_str("var downloadToOpen=");
    html.push_str(&js_string(&copy.download_to_open));
    html.push(';');
    html.push_str("var token=");
    html.push_str(&js_string(token));
    html.push(';');
    html.push_str(include_str!("lan_history.js"));
    html.push_str("</script></body></html>");
    html
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::JobStatus;

    fn sample_media() -> MediaInfo {
        MediaInfo {
            path: "/tmp/a.mp4".into(),
            duration_secs: Some(1.0),
            container: Some("mp4".into()),
            video_codec: Some("h264".into()),
            width: Some(64),
            height: Some(64),
            frame_rate: Some(25.0),
            audio_codec: Some("aac".into()),
            channels: Some(2),
            importable: true,
            error: None,
            trim_start_secs: None,
            trim_end_secs: None,
            page_count: None,
            page_start: None,
            page_end: None,
        }
    }

    fn job(id: &str, preset: &str, status: JobStatus, output: &str) -> Job {
        Job {
            id: id.into(),
            source_path: "/tmp/src".into(),
            output_path: Some(output.into()),
            status,
            progress: 100.0,
            error: None,
            config: OutputConfig {
                preset: preset.into(),
                ..Default::default()
            },
            media: sample_media(),
            display_name: output.rsplit('/').next().unwrap_or(output).into(),
            output_paths: vec![output.into()],
            created_at_epoch_ms: Some(1),
            concat_source_paths: vec![],
        }
    }

    #[test]
    fn html_contains_litetrans_and_four_tab_buttons() {
        let html = render_lan_history_html(&[], "", &LanHistoryCopy::english(), |_| true);
        assert!(html.contains("LiteTrans"));
        assert!(html.contains("data-tab-btn=\"video\""));
        assert!(html.contains("data-tab-btn=\"audio\""));
        assert!(html.contains("data-tab-btn=\"image\""));
        assert!(html.contains("data-tab-btn=\"document\""));
    }

    #[test]
    fn empty_token_shows_warning() {
        let copy = LanHistoryCopy::english();
        let html = render_lan_history_html(&[], "", &copy, |_| true);
        assert!(html.contains(&copy.warning));
    }

    #[test]
    fn token_adds_k_query_to_links() {
        let jobs = [job("v", "mp4-h264", JobStatus::Completed, "/tmp/v.mp4")];
        let html = render_lan_history_html(&jobs, "pw", &LanHistoryCopy::english(), |_| true);
        assert!(html.contains("k="));
        assert!(html.contains("/d/v?k=") || html.contains("/m/v?k="));
    }

    #[test]
    fn document_images_go_to_image_tab() {
        let jobs = [
            job("p", "image-png", JobStatus::Completed, "/tmp/p.png"),
            job("d", "office-pdf", JobStatus::Completed, "/tmp/a.pdf"),
        ];
        let html = render_lan_history_html(&jobs, "", &LanHistoryCopy::english(), |_| true);
        let image_pane = html
            .split("data-pane=\"image\"")
            .nth(1)
            .unwrap()
            .split("data-pane=\"document\"")
            .next()
            .unwrap();
        let doc_pane = html.split("data-pane=\"document\"").nth(1).unwrap();
        assert!(image_pane.contains("data-id=\"p\""));
        assert!(!doc_pane.contains("data-id=\"p\""));
        assert!(doc_pane.contains("data-id=\"d\""));
        assert_eq!(
            lan_library_tab(&jobs[0], "/tmp/p.png"),
            LanLibraryTab::Image
        );
        assert_eq!(
            lan_library_tab(&jobs[1], "/tmp/a.pdf"),
            LanLibraryTab::Document
        );
    }
}
