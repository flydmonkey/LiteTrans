use crate::queue::Job;

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

pub fn render_lan_history_html(
    _jobs: &[Job],
    _token: &str,
    _copy: &LanHistoryCopy,
    _file_exists: impl Fn(&str) -> bool,
) -> String {
    "<!DOCTYPE html><html><head><title>LiteTrans</title></head><body></body></html>".into()
}
