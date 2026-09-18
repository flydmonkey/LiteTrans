use std::path::{Path, PathBuf};

use crate::queue::Job;

pub fn jobs_file(config_dir: &Path) -> PathBuf {
    config_dir.join("jobs.json")
}

pub fn load_jobs(path: &Path) -> Vec<Job> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return vec![];
    };
    match serde_json::from_str::<Vec<Job>>(&text) {
        Ok(jobs) => jobs,
        Err(_) => {
            let bad_path = path.with_file_name("jobs.json.bad");
            let _ = std::fs::rename(path, &bad_path);
            vec![]
        }
    }
}

pub fn save_jobs(path: &Path, jobs: &[Job]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|err| format!("无法保存任务：{err}"))?;
    }
    let tmp_path = path.with_extension("json.tmp");
    let text =
        serde_json::to_string_pretty(jobs).map_err(|err| format!("无法保存任务：{err}"))?;
    std::fs::write(&tmp_path, text).map_err(|err| format!("无法保存任务：{err}"))?;
    #[cfg(windows)]
    if path.exists() {
        std::fs::remove_file(path).map_err(|err| format!("无法保存任务：{err}"))?;
    }
    std::fs::rename(&tmp_path, path).map_err(|err| format!("无法保存任务：{err}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::{Job, JobStatus};

    fn sample_job() -> Job {
        Job {
            id: "job-1".into(),
            source_path: "/a.mp4".into(),
            output_path: Some("/out/a.mp4".into()),
            status: JobStatus::Completed,
            progress: 100.0,
            error: None,
            config: OutputConfig {
                preset: "mp4-h264".into(),
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
    fn roundtrip_jobs() {
        let dir = std::env::temp_dir().join(format!("lt-jobs-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let path = jobs_file(&dir);
        let jobs = vec![sample_job()];
        save_jobs(&path, &jobs).unwrap();
        let loaded = load_jobs(&path);
        assert_eq!(loaded[0].id, "job-1");
        assert_eq!(loaded[0].display_name, "a.mp4");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_file_is_empty() {
        let path = std::env::temp_dir().join("lt-jobs-missing-nope.json");
        assert!(load_jobs(&path).is_empty());
    }

    #[test]
    fn corrupt_json_is_empty() {
        let dir = std::env::temp_dir().join(format!("lt-jobs-bad-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let path = jobs_file(&dir);
        std::fs::write(&path, "{nope").unwrap();
        assert!(load_jobs(&path).is_empty());
        assert!(dir.join("jobs.json.bad").exists() || !path.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
