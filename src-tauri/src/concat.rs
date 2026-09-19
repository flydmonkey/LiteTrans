use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use tauri::{AppHandle, Emitter};

use crate::args::validate;
use crate::convert::{concat_output_stem, even_dimension, VIDEO_CONCAT_MAX_SOURCES};
use crate::engine::{probe_media, run_tracked_ffmpeg, ActiveTranscode, ProgressPayload};
use crate::naming::{allocate_output_path, ffmpeg_file_arg, partial_output_path};
use crate::presets::{resolve_config, OutputConfig};
use crate::probe::MediaInfo;
use crate::queue::{split_importable, EnqueueReport, Job, JobStatus};

pub struct ConcatTarget {
    pub width: u32,
    pub height: u32,
    pub frame_rate: f64,
}

pub fn concat_target(first: &MediaInfo) -> Result<ConcatTarget, String> {
    let width = first.width.filter(|w| *w > 0).ok_or("Each clip needs a video track")?;
    let height = first.height.filter(|h| *h > 0).ok_or("Each clip needs a video track")?;
    Ok(ConcatTarget {
        width: even_dimension(width),
        height: even_dimension(height),
        frame_rate: first.frame_rate.filter(|f| *f > 0.0).unwrap_or(30.0),
    })
}

pub fn concat_scale_pad_filter(target: &ConcatTarget) -> String {
    format!(
        "scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={fps}",
        w = target.width,
        h = target.height,
        fps = target.frame_rate
    )
}

pub fn concat_list_file_contents(paths: &[String]) -> String {
    paths
        .iter()
        .map(|path| {
            let escaped = path.replace('\'', "'\\''");
            format!("file '{escaped}'")
        })
        .collect::<Vec<_>>()
        .join("\n")
}

pub fn build_concat_normalize_args(
    input: &str,
    output_partial: &str,
    media: &MediaInfo,
    target: &ConcatTarget,
    quality: &str,
    prefer_hardware: bool,
) -> Vec<String> {
    let mut args = vec![
        "-hide_banner".into(),
        "-nostats".into(),
        "-progress".into(),
        "pipe:1".into(),
        "-y".into(),
        "-i".into(),
        ffmpeg_file_arg(input),
    ];
    let duration = media.duration_secs.unwrap_or(0.0).max(0.05);
    let missing_audio = media.audio_codec.is_none();
    if missing_audio {
        args.extend([
            "-f".into(),
            "lavfi".into(),
            "-t".into(),
            format!("{duration:.3}"),
            "-i".into(),
            "anullsrc=channel_layout=stereo:sample_rate=48000".into(),
        ]);
    }
    args.extend(["-c:v".into(), concat_h264_codec(prefer_hardware).into()]);
    args.extend(concat_h264_quality_args(quality, prefer_hardware));
    args.extend([
        "-pix_fmt".into(),
        "yuv420p".into(),
        "-vf".into(),
        concat_scale_pad_filter(target),
    ]);
    if missing_audio {
        args.extend([
            "-map".into(),
            "0:v:0".into(),
            "-map".into(),
            "1:a:0".into(),
            "-shortest".into(),
        ]);
    }
    args.extend([
        "-c:a".into(),
        "aac".into(),
        "-ar".into(),
        "48000".into(),
        "-ac".into(),
        "2".into(),
        "-b:a".into(),
        "192k".into(),
        "-movflags".into(),
        "+faststart".into(),
        "-f".into(),
        "mp4".into(),
        ffmpeg_file_arg(output_partial),
    ]);
    args
}

pub fn build_concat_join_args(list_path: &str, output_partial: &str) -> Vec<String> {
    vec![
        "-hide_banner".into(),
        "-nostats".into(),
        "-progress".into(),
        "pipe:1".into(),
        "-y".into(),
        "-f".into(),
        "concat".into(),
        "-safe".into(),
        "0".into(),
        "-i".into(),
        ffmpeg_file_arg(list_path),
        "-c".into(),
        "copy".into(),
        "-movflags".into(),
        "+faststart".into(),
        "-f".into(),
        "mp4".into(),
        ffmpeg_file_arg(output_partial),
    ]
}

fn concat_h264_codec(prefer_hardware: bool) -> &'static str {
    if prefer_hardware && cfg!(target_os = "macos") {
        "h264_videotoolbox"
    } else {
        "libx264"
    }
}

fn concat_h264_quality_args(quality: &str, prefer_hardware: bool) -> Vec<String> {
    if prefer_hardware && cfg!(target_os = "macos") {
        let q = match quality {
            "original" | "high" => "70",
            "small" => "40",
            _ => "55",
        };
        vec![
            "-allow_sw".into(),
            "1".into(),
            "-q:v".into(),
            q.into(),
            "-profile:v".into(),
            "high".into(),
        ]
    } else {
        vec![
            "-preset".into(),
            "medium".into(),
            "-crf".into(),
            match quality {
                "original" | "high" => "16",
                "small" => "28",
                _ => "23",
            }
            .into(),
        ]
    }
}

pub fn jobs_from_concat_sources(
    sources: &[MediaInfo],
    config: OutputConfig,
    output_dir: &Path,
    job_id: String,
    exists: impl Fn(&Path) -> bool,
    created_at_epoch_ms: i64,
) -> Result<Vec<Job>, String> {
    let first = sources
        .first()
        .ok_or_else(|| "至少添加两段视频".to_string())?;
    let resolved = resolve_config(&config)?;
    let stem = concat_output_stem(&first.path);
    let output_path = allocate_output_path(output_dir, &stem, &resolved.extension, exists);
    let output_path_str = output_path.to_string_lossy().to_string();
    let display_name = output_path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("output")
        .to_string();
    let concat_source_paths = sources.iter().map(|item| item.path.clone()).collect();
    Ok(vec![Job {
        id: job_id,
        source_path: first.path.clone(),
        output_path: Some(output_path_str.clone()),
        status: JobStatus::Queued,
        progress: 0.0,
        error: None,
        config,
        media: first.clone(),
        display_name,
        output_paths: vec![output_path_str],
        created_at_epoch_ms: Some(created_at_epoch_ms),
        concat_source_paths,
    }])
}

pub fn concat_enqueue_report(
    sources: Vec<MediaInfo>,
    config: OutputConfig,
    output_dir: &Path,
    job_id: String,
    exists: impl Fn(&Path) -> bool,
    created_at_epoch_ms: i64,
) -> Result<EnqueueReport, String> {
    let resolved = resolve_config(&config)?;
    let (accepted, skipped) = split_importable(sources);
    if !skipped.is_empty() {
        return Err(skipped
            .into_iter()
            .next()
            .map(|item| item.reason)
            .unwrap_or_else(|| "无法转码该文件".into()));
    }
    if accepted.len() < 2 {
        return Err("至少添加两段视频".into());
    }
    if accepted.len() > VIDEO_CONCAT_MAX_SOURCES {
        return Err("最多合并 20 段视频".into());
    }

    for media in &accepted {
        concat_target(media)?;
        validate(&resolved, media)?;
    }

    Ok(EnqueueReport {
        jobs: jobs_from_concat_sources(
            &accepted,
            config,
            output_dir,
            job_id,
            exists,
            created_at_epoch_ms,
        )?,
        skipped: Vec::new(),
    })
}

pub fn concat_aligned_clips(
    clips: &[MediaInfo],
    quality: &str,
    output_path: &Path,
    work_dir: &Path,
    mut run: impl FnMut(&[String], &Path, f64) -> Result<(), String>,
    mut on_phase: impl FnMut(f64),
) -> Result<(), String> {
    if clips.len() < 2 {
        return Err("至少添加两段视频".into());
    }
    let target = concat_target(&clips[0])?;
    let _ = std::fs::remove_dir_all(work_dir);
    std::fs::create_dir_all(work_dir).map_err(|err| format!("无法创建临时目录：{err}"))?;
    let output_partial = partial_output_path(output_path);
    let total = clips.len() as f64;

    let result = (|| {
        let mut aligned = Vec::new();
        for (index, media) in clips.iter().enumerate() {
            concat_target(media)?;
            on_phase(index as f64 / total * 90.0);
            let clip_partial = work_dir.join(format!("clip-{index:03}.partial.mp4"));
            let clip_final = work_dir.join(format!("clip-{index:03}.mp4"));
            let duration = media.duration_secs.unwrap_or(0.0);
            let hw = build_concat_normalize_args(
                &media.path,
                clip_partial.to_string_lossy().as_ref(),
                media,
                &target,
                quality,
                true,
            );
            if let Err(err) = run(&hw, &clip_partial, duration) {
                if err == "cancelled" {
                    return Err(err);
                }
                let _ = std::fs::remove_file(&clip_partial);
                let sw = build_concat_normalize_args(
                    &media.path,
                    clip_partial.to_string_lossy().as_ref(),
                    media,
                    &target,
                    quality,
                    false,
                );
                run(&sw, &clip_partial, duration)?;
            }
            std::fs::rename(&clip_partial, &clip_final)
                .map_err(|err| format!("无法写入对齐片段：{err}"))?;
            aligned.push(clip_final.to_string_lossy().to_string());
        }

        on_phase(90.0);
        let list_path = work_dir.join("list.txt");
        std::fs::write(&list_path, concat_list_file_contents(&aligned))
            .map_err(|err| format!("无法写入拼接列表：{err}"))?;
        let join_duration: f64 = clips.iter().map(|clip| clip.duration_secs.unwrap_or(0.0)).sum();
        let join_args = build_concat_join_args(
            list_path.to_string_lossy().as_ref(),
            output_partial.to_string_lossy().as_ref(),
        );
        run(&join_args, &output_partial, join_duration)?;
        if output_path.exists() {
            let _ = std::fs::remove_file(output_path);
        }
        std::fs::rename(&output_partial, output_path)
            .map_err(|err| format!("无法写入输出文件：{err}"))?;
        Ok(())
    })();

    let _ = std::fs::remove_dir_all(work_dir);
    if result.is_err() && output_partial.exists() {
        let _ = std::fs::remove_file(&output_partial);
    }
    result
}

fn concat_clip_medias(job: &Job) -> Result<Vec<MediaInfo>, String> {
    if job.concat_source_paths.len() < 2 {
        return Err("至少添加两段视频".into());
    }
    let mut clips = Vec::new();
    for path in &job.concat_source_paths {
        if path == &job.media.path && job.media.importable {
            concat_target(&job.media)?;
            clips.push(job.media.clone());
            continue;
        }
        let info = probe_media(path);
        if !info.importable {
            return Err(info
                .error
                .unwrap_or_else(|| "无法读取该文件".into()));
        }
        concat_target(&info)?;
        clips.push(info);
    }
    Ok(clips)
}

pub fn concat_job(
    app: &AppHandle,
    job: &Job,
    output_path: &Path,
    active: &Arc<Mutex<Option<ActiveTranscode>>>,
) -> Result<(), String> {
    let clips = concat_clip_medias(job)?;
    let quality = job.config.quality.as_deref().unwrap_or("standard");
    let work_dir = std::env::temp_dir().join(format!("litetrans-concat-{}", job.id));
    let phase = Arc::new(AtomicU64::new(0));
    let total = clips.len() as f64;
    concat_aligned_clips(
        &clips,
        quality,
        output_path,
        &work_dir,
        |args, partial, duration| {
            let phase = phase.clone();
            let span = if phase.load(Ordering::Relaxed) >= 9000 {
                10.0
            } else {
                90.0 / total
            };
            run_tracked_ffmpeg(app, &job.id, args, partial, duration, active, move |local| {
                let base = phase.load(Ordering::Relaxed) as f64 / 100.0;
                (base + local / 100.0 * span).clamp(0.0, 100.0)
            })
        },
        |percent| {
            phase.store((percent * 100.0) as u64, Ordering::Relaxed);
            let _ = app.emit(
                "job-progress",
                ProgressPayload {
                    id: job.id.clone(),
                    percent,
                },
            );
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use std::path::Path;

    fn media(path: &str, width: u32, height: u32, fps: Option<f64>) -> MediaInfo {
        MediaInfo {
            path: path.into(),
            duration_secs: Some(2.0),
            container: Some("mp4".into()),
            video_codec: Some("h264".into()),
            width: Some(width),
            height: Some(height),
            frame_rate: fps,
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

    #[test]
    fn concat_target_evens_odd_width() {
        let first = media("/clip.mp4", 1921, 1080, None);
        let target = concat_target(&first).expect("video track");
        assert_eq!(target.width, 1920);
        assert_eq!(target.height, 1080);
        assert_eq!(target.frame_rate, 30.0);
    }

    #[test]
    fn concat_scale_pad_filter_matches_android() {
        let target = ConcatTarget {
            width: 1920,
            height: 1080,
            frame_rate: 30.0,
        };
        let filter = concat_scale_pad_filter(&target);
        assert_eq!(
            filter,
            "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30"
        );
        assert!(filter.contains("force_original_aspect_ratio=decrease"));
        assert!(filter.contains("pad=1920:1080"));
        assert!(filter.contains("setsar=1"));
    }

    #[test]
    fn jobs_from_concat_sources_makes_one_job() {
        let sources = vec![
            media("/a.mp4", 1920, 1080, Some(30.0)),
            media("/b.mp4", 1280, 720, Some(24.0)),
            media("/c.mp4", 1920, 1080, Some(30.0)),
        ];
        let jobs = jobs_from_concat_sources(
            &sources,
            OutputConfig {
                preset: "video-concat".into(),
                ..Default::default()
            },
            Path::new("/out"),
            "job-1".into(),
            |_| false,
            1,
        )
        .expect("one concat job");
        assert_eq!(jobs.len(), 1);
        assert_eq!(jobs[0].source_path, "/a.mp4");
        assert_eq!(jobs[0].concat_source_paths.len(), 3);
        assert_eq!(
            jobs[0].concat_source_paths,
            vec!["/a.mp4", "/b.mp4", "/c.mp4"]
        );
        assert_eq!(jobs[0].output_path.as_deref(), Some("/out/a-merged.mp4"));
    }

    #[test]
    fn normalize_args_use_anullsrc_when_silent() {
        let mut silent = media("/in/b.mp4", 1080, 1920, Some(30.0));
        silent.audio_codec = None;
        let args = build_concat_normalize_args(
            "/in/b.mp4",
            "/tmp/clip-001.partial.mp4",
            &silent,
            &concat_target(&media("/a.mp4", 1920, 1080, Some(30.0))).unwrap(),
            "standard",
            false,
        );
        let filter = args[args.iter().position(|a| a == "-vf").unwrap() + 1].clone();
        assert!(filter.contains("1920:1080"));
        assert!(filter.contains("force_original_aspect_ratio=decrease"));
        assert!(filter.contains("pad=1920:1080"));
        assert!(args.contains(&"libx264".into()));
        assert!(args.contains(&"yuv420p".into()));
        assert!(args.contains(&"anullsrc=channel_layout=stereo:sample_rate=48000".into()));
        assert!(args.contains(&"0:v:0".into()));
        assert!(args.contains(&"1:a:0".into()));
        assert!(args.contains(&"aac".into()));
        assert!(args.contains(&"48000".into()));
    }

    #[test]
    fn normalize_args_keep_existing_audio() {
        let first = media("/in/a.mp4", 1920, 1080, Some(30.0));
        let args = build_concat_normalize_args(
            "/in/a.mp4",
            "/tmp/a.partial.mp4",
            &first,
            &concat_target(&first).unwrap(),
            "original",
            false,
        );
        assert!(!args.iter().any(|a| a.contains("anullsrc")));
        assert!(args.contains(&"aac".into()));
    }

    #[test]
    fn join_args_use_concat_demuxer_copy() {
        let args = build_concat_join_args("/tmp/list.txt", "/out/a-merged.partial.mp4");
        assert!(args.windows(2).any(|w| w == ["-f", "concat"]));
        assert!(args.contains(&"-safe".into()));
        assert!(args.contains(&"0".into()));
        assert!(args.windows(2).any(|w| w == ["-c", "copy"]));
    }

    #[test]
    fn list_file_escapes_quotes() {
        let text = concat_list_file_contents(&["/tmp/a.mp4".into(), "/tmp/o'reilly.mp4".into()]);
        assert!(text.contains("file '/tmp/a.mp4'"));
        assert!(text.contains("file '/tmp/o'\\''reilly.mp4'"));
    }

    #[test]
    fn concat_enqueue_rejects_one_clip() {
        let err = concat_enqueue_report(
            vec![media("/a.mp4", 1920, 1080, Some(30.0))],
            OutputConfig {
                preset: "video-concat".into(),
                ..Default::default()
            },
            Path::new("/out"),
            "job-1".into(),
            |_| false,
            1,
        )
        .expect_err("need two clips");
        assert_eq!(err, "至少添加两段视频");
    }

    #[test]
    fn concat_aligned_clips_joins_two_generated_files() {
        use crate::engine::{probe_media, resolve_binary, sidecar_command};
        use crate::naming::partial_output_path;

        let Ok(ffmpeg) = resolve_binary("ffmpeg") else {
            eprintln!("skip: run npm run fetch-ffmpeg first");
            return;
        };
        let dir = std::env::temp_dir().join(format!(
            "litetrans-concat-it-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let a = dir.join("a.mp4");
        let b = dir.join("b.mp4");
        for (path, size) in [(&a, "160x120"), (&b, "320x240")] {
            let status = sidecar_command(&ffmpeg)
                .args([
                    "-hide_banner",
                    "-y",
                    "-f",
                    "lavfi",
                    "-i",
                    &format!("testsrc=duration=0.4:size={size}:rate=10"),
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:duration=0.4",
                    "-shortest",
                    "-c:v",
                    "libx264",
                    "-pix_fmt",
                    "yuv420p",
                    "-c:a",
                    "aac",
                    path.to_str().unwrap(),
                ])
                .env("PATH", crate::engine::restricted_path())
                .status()
                .unwrap();
            assert!(status.success());
        }
        let clips = vec![
            probe_media(a.to_str().unwrap()),
            probe_media(b.to_str().unwrap()),
        ];
        assert!(clips[0].importable && clips[1].importable, "{clips:?}");
        let output = dir.join("a-merged.mp4");
        let work = dir.join("work");
        concat_aligned_clips(
            &clips,
            "standard",
            &output,
            &work,
            |args, partial, _duration| {
                if partial.exists() {
                    let _ = std::fs::remove_file(partial);
                }
                let status = sidecar_command(&ffmpeg)
                    .args(args)
                    .env("PATH", crate::engine::restricted_path())
                    .status()
                    .map_err(|err| format!("启动 FFmpeg 失败：{err}"))?;
                if status.success() {
                    Ok(())
                } else {
                    Err("FFmpeg 转码失败".into())
                }
            },
            |_| {},
        )
        .expect("concat pipeline");
        assert!(output.is_file());
        assert!(!partial_output_path(&output).exists());
        assert!(!work.exists());
        let info = probe_media(output.to_str().unwrap());
        assert!(info.importable, "{info:?}");
        assert_eq!(info.video_codec.as_deref(), Some("h264"));
        assert_eq!(info.width, Some(160));
        assert_eq!(info.height, Some(120));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn concat_enqueue_rejects_skipped_mix() {
        let mut bad = media("/bad.bin", 1920, 1080, Some(30.0));
        bad.importable = false;
        bad.error = Some("无法读取".into());
        let err = concat_enqueue_report(
            vec![
                media("/a.mp4", 1920, 1080, Some(30.0)),
                media("/b.mp4", 1280, 720, Some(24.0)),
                bad,
            ],
            OutputConfig {
                preset: "video-concat".into(),
                ..Default::default()
            },
            Path::new("/out"),
            "job-1".into(),
            |_| false,
            1,
        )
        .expect_err("mixed reject is whole-batch failure");
        assert_eq!(err, "无法读取");
    }

    #[test]
    fn concat_enqueue_missing_video_is_error_not_partial_ok() {
        let mut no_dims = media("/no-video.mp4", 1920, 1080, Some(30.0));
        no_dims.width = None;
        no_dims.height = None;
        match concat_enqueue_report(
            vec![
                media("/a.mp4", 1920, 1080, Some(30.0)),
                media("/b.mp4", 1280, 720, Some(24.0)),
                no_dims,
            ],
            OutputConfig {
                preset: "video-concat".into(),
                ..Default::default()
            },
            Path::new("/out"),
            "job-1".into(),
            |_| false,
            1,
        ) {
            Ok(report) => panic!(
                "must not succeed with {} jobs and {} skipped",
                report.jobs.len(),
                report.skipped.len()
            ),
            Err(message) => assert_eq!(message, "Each clip needs a video track"),
        }
    }
}
