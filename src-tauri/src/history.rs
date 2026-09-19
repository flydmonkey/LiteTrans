use serde::{Deserialize, Serialize};

use crate::queue::{Job, JobStatus};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum HistorySegment {
    Video,
    Audio,
    Document,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JobRowAction {
    Cancel,
    Retry,
    Open,
    Reveal,
    Rename,
    Delete,
}

const AUDIO_PRESETS: &[&str] = &[
    "audio-mp3",
    "audio-aac",
    "audio-wav",
    "audio-flac",
    "audio-ogg",
    "audio-amr",
];

pub const DOCUMENT_PRESETS: &[&str] = &[
    "image-jpg",
    "image-png",
    "image-webp",
    "image-bmp",
    "image-gif",
    "image-compress",
    "pdf-image",
    "pdf-txt",
    "pdf-compress",
    "pdf-split",
    "office-pdf",
];

pub fn is_audio_preset(preset: &str) -> bool {
    AUDIO_PRESETS.contains(&preset)
}

pub fn is_document_preset(preset: &str) -> bool {
    DOCUMENT_PRESETS.contains(&preset)
}

pub fn history_segment_for(job: &Job) -> HistorySegment {
    let preset = job.config.preset.as_str();
    if is_audio_preset(preset) {
        HistorySegment::Audio
    } else if is_document_preset(preset) {
        HistorySegment::Document
    } else {
        HistorySegment::Video
    }
}

pub fn history_jobs<'a>(jobs: &'a [Job], segment: HistorySegment) -> Vec<&'a Job> {
    jobs.iter()
        .filter(|job| history_segment_for(job) == segment)
        .collect()
}

pub fn remaining_jobs_after_clear_finished(
    jobs: &[Job],
    segment: HistorySegment,
) -> Vec<Job> {
    jobs.iter()
        .filter(|job| {
            if history_segment_for(job) != segment {
                return true;
            }
            matches!(job.status, JobStatus::Queued | JobStatus::Running)
        })
        .cloned()
        .collect()
}

pub fn history_active_count(jobs: &[Job]) -> usize {
    jobs.iter()
        .filter(|job| matches!(job.status, JobStatus::Queued | JobStatus::Running))
        .count()
}

pub fn mark_interrupted(jobs: &mut [Job]) {
    for job in jobs.iter_mut() {
        if matches!(job.status, JobStatus::Queued | JobStatus::Running) {
            job.status = JobStatus::Failed;
            job.error = Some("error_interrupted".into());
        }
    }
}

pub fn job_row_actions(status: &JobStatus) -> Vec<JobRowAction> {
    match status {
        JobStatus::Queued | JobStatus::Running => vec![JobRowAction::Cancel],
        JobStatus::Failed | JobStatus::Cancelled => {
            vec![JobRowAction::Retry, JobRowAction::Delete]
        }
        JobStatus::Completed => vec![
            JobRowAction::Open,
            JobRowAction::Reveal,
            JobRowAction::Rename,
            JobRowAction::Delete,
        ],
    }
}

pub fn parse_history_segment(raw: &str) -> HistorySegment {
    match raw {
        "audio" => HistorySegment::Audio,
        "document" => HistorySegment::Document,
        _ => HistorySegment::Video,
    }
}

pub fn history_segment_after_enqueue(preset: &str) -> HistorySegment {
    if is_audio_preset(preset) {
        HistorySegment::Audio
    } else if is_document_preset(preset) {
        HistorySegment::Document
    } else {
        HistorySegment::Video
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::{Job, JobStatus};

    fn job(id: &str, preset: &str, status: JobStatus) -> Job {
        Job {
            id: id.into(),
            source_path: "/a.mp4".into(),
            output_path: Some("/out/a.mp4".into()),
            status,
            progress: 0.0,
            error: None,
            config: OutputConfig {
                preset: preset.into(),
                ..Default::default()
            },
            media: MediaInfo {
                path: "/a.mp4".into(),
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
            },
            display_name: "a.mp4".into(),
            output_paths: vec!["/out/a.mp4".into()],
            created_at_epoch_ms: Some(1),
            concat_source_paths: vec![],
        }
    }

    #[test]
    fn segments_audio_and_document_presets() {
        assert_eq!(
            history_segment_for(&job("1", "audio-mp3", JobStatus::Completed)),
            HistorySegment::Audio
        );
        assert_eq!(
            history_segment_for(&job("2", "pdf-split", JobStatus::Completed)),
            HistorySegment::Document
        );
        assert_eq!(
            history_segment_for(&job("3", "mp4-h264", JobStatus::Completed)),
            HistorySegment::Video
        );
    }

    #[test]
    fn clear_finished_only_touches_current_segment() {
        let jobs = vec![
            job("v", "mp4-h264", JobStatus::Completed),
            job("a", "audio-mp3", JobStatus::Completed),
            job("r", "mp4-h264", JobStatus::Running),
        ];
        let next = remaining_jobs_after_clear_finished(&jobs, HistorySegment::Video);
        let ids: Vec<_> = next.iter().map(|j| j.id.as_str()).collect();
        assert_eq!(ids, vec!["a", "r"]);
    }

    #[test]
    fn mark_interrupted_fails_active_jobs() {
        let mut jobs = vec![
            job("q", "mp4-h264", JobStatus::Queued),
            job("c", "mp4-h264", JobStatus::Completed),
        ];
        mark_interrupted(&mut jobs);
        assert_eq!(jobs[0].status, JobStatus::Failed);
        assert_eq!(jobs[0].error.as_deref(), Some("error_interrupted"));
        assert_eq!(jobs[1].status, JobStatus::Completed);
    }

    #[test]
    fn actions_match_status() {
        assert_eq!(
            job_row_actions(&JobStatus::Running),
            vec![JobRowAction::Cancel]
        );
        assert_eq!(
            job_row_actions(&JobStatus::Failed),
            vec![JobRowAction::Retry, JobRowAction::Delete]
        );
        assert_eq!(
            job_row_actions(&JobStatus::Completed),
            vec![
                JobRowAction::Open,
                JobRowAction::Reveal,
                JobRowAction::Rename,
                JobRowAction::Delete
            ]
        );
    }

    #[test]
    fn enqueue_segment_follows_preset() {
        assert_eq!(
            history_segment_after_enqueue("audio-mp3"),
            HistorySegment::Audio
        );
        assert_eq!(
            history_segment_after_enqueue("mp4-h264"),
            HistorySegment::Video
        );
    }

    #[test]
    fn parse_history_segment_audio() {
        assert_eq!(parse_history_segment("audio"), HistorySegment::Audio);
    }

    #[test]
    fn parse_history_segment_known_and_unknown() {
        assert_eq!(parse_history_segment("video"), HistorySegment::Video);
        assert_eq!(parse_history_segment("document"), HistorySegment::Document);
        assert_eq!(parse_history_segment("nope"), HistorySegment::Video);
    }
}
