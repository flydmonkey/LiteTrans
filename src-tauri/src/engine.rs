use std::env;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};

use serde::Serialize;
use tauri::{AppHandle, Emitter};

use crate::args::{build_ffmpeg_args, output_duration_secs};
use crate::naming::{ffmpeg_file_arg, partial_output_path};
use crate::presets::ResolvedConfig;
use crate::probe::{parse_ffprobe_json, probe_document, unreadable, MediaInfo};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProgressPayload {
    pub id: String,
    pub percent: f64,
}

pub fn current_target_triple() -> String {
    let arch = env::consts::ARCH;
    let os = env::consts::OS;
    match (os, arch) {
        ("macos", "aarch64") => "aarch64-apple-darwin".into(),
        ("macos", "x86_64") => "x86_64-apple-darwin".into(),
        ("linux", "x86_64") => "x86_64-unknown-linux-gnu".into(),
        ("linux", "aarch64") => "aarch64-unknown-linux-gnu".into(),
        ("windows", "x86_64") => "x86_64-pc-windows-msvc".into(),
        ("windows", "aarch64") => "aarch64-pc-windows-msvc".into(),
        _ => format!("{arch}-unknown-{os}"),
    }
}

pub fn sidecar_filename(name: &str) -> String {
    let triple = current_target_triple();
    if cfg!(windows) {
        format!("{name}-{triple}.exe")
    } else {
        format!("{name}-{triple}")
    }
}

pub fn resolve_binary(name: &str) -> Result<PathBuf, String> {
    let filename = sidecar_filename(name);
    let mut candidates = Vec::new();

    if let Ok(exe) = env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join(&filename));
            candidates.push(dir.join(name));
            if cfg!(windows) {
                candidates.push(dir.join(format!("{name}.exe")));
            }
            candidates.push(dir.join("binaries").join(&filename));
        }
    }

    candidates.push(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("binaries")
            .join(&filename),
    );

    for path in candidates {
        if path.is_file() {
            return Ok(path);
        }
    }

    Err(format!(
        "找不到打包的 {name}。请先运行 npm run fetch-ffmpeg"
    ))
}

pub(crate) fn sidecar_command(bin: impl AsRef<Path>) -> Command {
    let mut cmd = Command::new(bin.as_ref());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    cmd
}

pub fn probe_media(path: &str) -> MediaInfo {
    if let Some(info) = probe_document(path) {
        return info;
    }

    let ffprobe = match resolve_binary("ffprobe") {
        Ok(path) => path,
        Err(err) => return unreadable(path, err),
    };

    let input = ffmpeg_file_arg(path);
    let output = sidecar_command(ffprobe)
        .args([
            "-v",
            "error",
            "-probesize",
            "1000000",
            "-analyzeduration",
            "200000",
            "-show_entries",
            "format=format_name,duration:stream=codec_type,codec_name,width,height,r_frame_rate,channels",
            "-of",
            "json",
            &input,
        ])
        .env("PATH", restricted_path())
        .output();

    match output {
        Ok(out) if out.status.success() => {
            let json = String::from_utf8_lossy(&out.stdout);
            parse_ffprobe_json(path, &json)
        }
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr);
            let reason = if stderr.trim().is_empty() {
                "无法读取该文件".to_string()
            } else {
                stderr.lines().next().unwrap_or("无法读取该文件").to_string()
            };
            unreadable(path, reason)
        }
        Err(err) => unreadable(path, format!("启动 FFprobe 失败：{err}")),
    }
}

pub fn extract_preview_frame(path: &str, time_secs: f64) -> Result<String, String> {
    let ffmpeg = resolve_binary("ffmpeg")?;
    let time = format!("{:.3}", time_secs.max(0.0));
    let output = sidecar_command(ffmpeg)
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-ss",
            &time,
            "-i",
            &ffmpeg_file_arg(path),
            "-frames:v",
            "1",
            "-an",
            "-vf",
            "scale='min(720,iw)':-2",
            "-q:v",
            "4",
            "-f",
            "image2pipe",
            "-vcodec",
            "mjpeg",
            "pipe:1",
        ])
        .env("PATH", restricted_path())
        .output()
        .map_err(|err| format!("抽帧失败：{err}"))?;
    if !output.status.success() || output.stdout.is_empty() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let reason = stderr
            .lines()
            .next()
            .unwrap_or("无法读取这一帧");
        return Err(reason.to_string());
    }
    Ok(base64::Engine::encode(
        &base64::engine::general_purpose::STANDARD,
        &output.stdout,
    ))
}

fn file_extension(path: &str) -> String {
    Path::new(path)
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_lowercase()
}

pub fn native_preview_likely(path: &str, _container: Option<&str>, video_codec: Option<&str>) -> bool {
    let ext = file_extension(path);
    let codec = video_codec.unwrap_or("").to_lowercase();
    match ext.as_str() {
        "mp4" | "m4v" | "mov" => matches!(codec.as_str(), "" | "h264" | "hevc" | "h265"),
        "webm" => matches!(codec.as_str(), "" | "vp8" | "vp9" | "av1"),
        _ => false,
    }
}

pub fn preview_can_remux(video_codec: Option<&str>) -> bool {
    matches!(video_codec.unwrap_or(""), "h264" | "hevc" | "h265")
}

fn preview_cache_file(cache_dir: &Path, source: &str, kind: &str) -> PathBuf {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    use std::time::UNIX_EPOCH;

    let meta = std::fs::metadata(source).ok();
    let stamp = meta
        .map(|m| {
            let modified = m
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            format!("{}-{}", m.len(), modified)
        })
        .unwrap_or_default();
    let mut hasher = DefaultHasher::new();
    source.hash(&mut hasher);
    stamp.hash(&mut hasher);
    kind.hash(&mut hasher);
    cache_dir.join(format!("preview-{:x}-{kind}.mp4", hasher.finish()))
}

fn run_ffmpeg(args: &[String]) -> Result<(), String> {
    let ffmpeg = resolve_binary("ffmpeg")?;
    let output = sidecar_command(ffmpeg)
        .args(args)
        .env("PATH", restricted_path())
        .output()
        .map_err(|err| format!("生成预览失败：{err}"))?;
    if output.status.success() {
        return Ok(());
    }
    let stderr = String::from_utf8_lossy(&output.stderr);
    let reason = stderr
        .lines()
        .rev()
        .find(|line| !line.trim().is_empty())
        .unwrap_or("无法生成可播放预览");
    Err(reason.to_string())
}

fn remux_preview(input: &str, output: &Path, audio_codec: Option<&str>) -> Result<(), String> {
    let mut args = vec![
        "-hide_banner".into(),
        "-y".into(),
        "-i".into(),
        ffmpeg_file_arg(input),
        "-map".into(),
        "0:v:0".into(),
        "-map".into(),
        "0:a:0?".into(),
        "-c:v".into(),
        "copy".into(),
    ];
    if matches!(audio_codec.unwrap_or(""), "aac") {
        args.extend(["-c:a".into(), "copy".into()]);
    } else {
        args.extend(["-c:a".into(), "aac".into(), "-b:a".into(), "128k".into()]);
    }
    args.extend([
        "-avoid_negative_ts".into(),
        "make_zero".into(),
        "-movflags".into(),
        "+faststart".into(),
        "-f".into(),
        "mp4".into(),
        ffmpeg_file_arg(&output.to_string_lossy()),
    ]);
    run_ffmpeg(&args)
}

fn transcode_preview(input: &str, output: &Path) -> Result<(), String> {
    let codecs: &[&str] = if cfg!(target_os = "macos") {
        &["h264_videotoolbox", "libx264"]
    } else {
        &["libx264"]
    };
    let mut last_err = "无法生成可播放预览".to_string();
    for codec in codecs {
        match transcode_preview_with(input, output, codec) {
            Ok(()) => return Ok(()),
            Err(err) => last_err = err,
        }
    }
    Err(last_err)
}

fn transcode_preview_with(input: &str, output: &Path, video_codec: &str) -> Result<(), String> {
    let mut args = vec![
        "-hide_banner".into(),
        "-y".into(),
        "-i".into(),
        ffmpeg_file_arg(input),
        "-map".into(),
        "0:v:0".into(),
        "-map".into(),
        "0:a:0?".into(),
        "-vf".into(),
        "scale='min(720,iw)':-2".into(),
        "-c:v".into(),
        video_codec.into(),
    ];
    if video_codec.contains("videotoolbox") {
        args.extend(["-allow_sw".into(), "1".into(), "-q:v".into(), "65".into()]);
    } else {
        args.extend(["-preset".into(), "ultrafast".into(), "-crf".into(), "28".into()]);
    }
    args.extend([
        "-pix_fmt".into(),
        "yuv420p".into(),
        "-c:a".into(),
        "aac".into(),
        "-b:a".into(),
        "96k".into(),
        "-ac".into(),
        "2".into(),
        "-movflags".into(),
        "+faststart".into(),
        "-f".into(),
        "mp4".into(),
        ffmpeg_file_arg(&output.to_string_lossy()),
    ]);
    run_ffmpeg(&args)
}

pub fn prepare_preview(
    path: &str,
    cache_dir: &Path,
    container: Option<&str>,
    video_codec: Option<&str>,
    audio_codec: Option<&str>,
    force: bool,
) -> Result<PathBuf, String> {
    if !force && native_preview_likely(path, container, video_codec) {
        return Ok(PathBuf::from(path));
    }

    std::fs::create_dir_all(cache_dir).map_err(|err| format!("无法创建预览缓存：{err}"))?;
    let kind = if !force && preview_can_remux(video_codec) {
        "copy"
    } else {
        "h264"
    };
    let output = preview_cache_file(cache_dir, path, kind);
    if output.is_file() && output.metadata().map(|m| m.len() > 0).unwrap_or(false) {
        return Ok(output);
    }

    let partial = cache_dir.join(format!(
        "{}.partial.mp4",
        output.file_stem().and_then(|s| s.to_str()).unwrap_or("preview")
    ));
    if partial.exists() {
        let _ = std::fs::remove_file(&partial);
    }

    let result = if kind == "copy" {
        remux_preview(path, &partial, audio_codec).or_else(|_| transcode_preview(path, &partial))
    } else {
        transcode_preview(path, &partial)
    };

    match result {
        Ok(()) => {
            std::fs::rename(&partial, &output).map_err(|err| format!("无法写入预览文件：{err}"))?;
            Ok(output)
        }
        Err(err) => {
            let _ = std::fs::remove_file(&partial);
            Err(err)
        }
    }
}

pub struct ActiveTranscode {
    pub job_id: String,
    pub child: Child,
    pub partial: PathBuf,
}

pub fn transcode_job(
    app: &AppHandle,
    job_id: &str,
    media: &MediaInfo,
    config: &ResolvedConfig,
    output_path: &Path,
    active: &Arc<Mutex<Option<ActiveTranscode>>>,
) -> Result<(), String> {
    let partial = partial_output_path(output_path);
    let args = build_ffmpeg_args(
        &media.path,
        partial.to_string_lossy().as_ref(),
        config,
        media,
    )?;
    let duration = output_duration_secs(config, media);
    run_tracked_ffmpeg(app, job_id, &args, &partial, duration, active, |percent| {
        percent
    })?;
    if output_path.exists() {
        let _ = std::fs::remove_file(output_path);
    }
    std::fs::rename(&partial, output_path).map_err(|err| format!("无法写入输出文件：{err}"))
}

pub(crate) fn run_tracked_ffmpeg(
    app: &AppHandle,
    job_id: &str,
    args: &[String],
    partial: &Path,
    duration_secs: f64,
    active: &Arc<Mutex<Option<ActiveTranscode>>>,
    map_percent: impl Fn(f64) -> f64 + Send + 'static,
) -> Result<(), String> {
    let ffmpeg = resolve_binary("ffmpeg")?;
    if partial.exists() {
        let _ = std::fs::remove_file(partial);
    }

    let mut command = sidecar_command(&ffmpeg);
    command
        .args(args)
        .env("PATH", restricted_path())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = command
        .spawn()
        .map_err(|err| format!("启动 FFmpeg 失败：{err}"))?;

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    {
        let mut slot = active.lock().map_err(|_| "转码状态锁失败".to_string())?;
        *slot = Some(ActiveTranscode {
            job_id: job_id.to_string(),
            child,
            partial: partial.to_path_buf(),
        });
    }

    if let Some(stdout) = stdout {
        let app_clone = app.clone();
        let job_id = job_id.to_string();
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                if let Some(percent) = parse_progress_line(&line, duration_secs) {
                    let _ = app_clone.emit(
                        "job-progress",
                        ProgressPayload {
                            id: job_id.clone(),
                            percent: map_percent(percent),
                        },
                    );
                }
            }
        });
    }

    let stderr_handle = stderr.map(|pipe| {
        std::thread::spawn(move || {
            let reader = BufReader::new(pipe);
            reader.lines().map_while(Result::ok).collect::<Vec<_>>()
        })
    });

    let status = loop {
        std::thread::sleep(std::time::Duration::from_millis(80));
        let mut slot = active.lock().map_err(|_| "转码状态锁失败".to_string())?;
        match slot.as_mut() {
            None => {
                cleanup_partial(partial);
                return Err("cancelled".into());
            }
            Some(active_job) => match active_job.child.try_wait() {
                Ok(Some(status)) => {
                    *slot = None;
                    break status;
                }
                Ok(None) => {}
                Err(err) => {
                    *slot = None;
                    cleanup_partial(partial);
                    return Err(format!("等待 FFmpeg 失败：{err}"));
                }
            },
        }
    };

    let stderr_text = stderr_handle
        .and_then(|h| h.join().ok())
        .map(|lines| lines.join("\n"))
        .unwrap_or_default();

    if status.success() {
        Ok(())
    } else {
        cleanup_partial(partial);
        let reason = stderr_text
            .lines()
            .rev()
            .find(|line| !line.trim().is_empty())
            .unwrap_or("FFmpeg 转码失败");
        Err(reason.to_string())
    }
}

pub fn cancel_active(active: &Arc<Mutex<Option<ActiveTranscode>>>, job_id: &str) -> Result<bool, String> {
    let mut slot = active.lock().map_err(|_| "转码状态锁失败".to_string())?;
    if let Some(mut current) = slot.take() {
        if current.job_id == job_id {
            let _ = current.child.kill();
            let _ = current.child.wait();
            cleanup_partial(&current.partial);
            return Ok(true);
        }
        *slot = Some(current);
    }
    Ok(false)
}

fn cleanup_partial(path: &Path) {
    if path.exists() {
        let _ = std::fs::remove_file(path);
    }
}

pub(crate) fn restricted_path() -> &'static str {
    if cfg!(windows) {
        r"C:\Windows\System32"
    } else {
        "/usr/bin:/bin"
    }
}

pub fn parse_progress_line(line: &str, duration_secs: f64) -> Option<f64> {
    let (key, value) = line.split_once('=')?;
    if key != "out_time_ms" && key != "out_time_us" {
        return None;
    }
    let n: f64 = value.trim().parse().ok()?;
    if duration_secs <= 0.0 {
        return None;
    }
    let seconds = if key == "out_time_us" {
        n / 1_000_000.0
    } else {
        n / 1_000_000.0
    };
    // FFmpeg -progress uses out_time_ms as microseconds despite the name.
    Some(((seconds / duration_secs) * 100.0).clamp(0.0, 100.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_preview_accepts_mp4_h264() {
        assert!(native_preview_likely(
            "/tmp/a.mp4",
            Some("mov,mp4,m4a,3gp,3g2,mj2"),
            Some("h264"),
        ));
    }

    #[test]
    fn native_preview_rejects_mkv_and_avi() {
        assert!(!native_preview_likely(
            "/tmp/a.mkv",
            Some("matroska,webm"),
            Some("h264"),
        ));
        assert!(!native_preview_likely("/tmp/a.avi", Some("avi"), Some("mpeg4")));
    }

    #[test]
    fn preview_remuxes_h264_but_not_mpeg4() {
        assert!(preview_can_remux(Some("h264")));
        assert!(preview_can_remux(Some("hevc")));
        assert!(!preview_can_remux(Some("mpeg4")));
    }

    #[test]
    fn prepare_preview_returns_mp4_unchanged() {
        let dir = env::temp_dir().join(format!("video-converter-preview-mp4-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sample = dir.join("clip.mp4");
        std::fs::write(&sample, b"fake").unwrap();
        let out = prepare_preview(
            sample.to_str().unwrap(),
            &dir.join("cache"),
            Some("mov,mp4,m4a,3gp,3g2,mj2"),
            Some("h264"),
            Some("aac"),
            false,
        )
        .unwrap();
        assert_eq!(out, sample);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn prepare_preview_remuxes_mkv_to_mp4() {
        let Ok(ffmpeg) = resolve_binary("ffmpeg") else {
            eprintln!("skip: run npm run fetch-ffmpeg first");
            return;
        };
        let dir = env::temp_dir().join(format!("video-converter-preview-mkv-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sample = dir.join("clip.mkv");
        let status = Command::new(ffmpeg)
            .args([
                "-hide_banner",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=160x120:rate=10",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=1",
                "-shortest",
                "-c:v",
                "libx264",
                "-c:a",
                "aac",
                "-f",
                "matroska",
                sample.to_str().unwrap(),
            ])
            .env("PATH", restricted_path())
            .status()
            .unwrap();
        assert!(status.success());
        let out = prepare_preview(
            sample.to_str().unwrap(),
            &dir.join("cache"),
            Some("matroska,webm"),
            Some("h264"),
            Some("aac"),
            false,
        )
        .unwrap();
        assert_ne!(out, sample);
        assert_eq!(out.extension().and_then(|s| s.to_str()), Some("mp4"));
        assert!(out.is_file());
        let info = probe_media(out.to_str().unwrap());
        assert_eq!(info.video_codec.as_deref(), Some("h264"));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn prepare_preview_transcodes_avi_mpeg4() {
        let Ok(ffmpeg) = resolve_binary("ffmpeg") else {
            eprintln!("skip: run npm run fetch-ffmpeg first");
            return;
        };
        let dir = env::temp_dir().join(format!("video-converter-preview-avi-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sample = dir.join("clip.avi");
        let status = Command::new(ffmpeg)
            .args([
                "-hide_banner",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=160x120:rate=10",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=1",
                "-shortest",
                "-c:v",
                "mpeg4",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "mp3",
                sample.to_str().unwrap(),
            ])
            .env("PATH", restricted_path())
            .status()
            .unwrap();
        assert!(status.success());
        let out = prepare_preview(
            sample.to_str().unwrap(),
            &dir.join("cache"),
            Some("avi"),
            Some("mpeg4"),
            Some("mp3"),
            false,
        )
        .unwrap();
        assert_ne!(out, sample);
        let info = probe_media(out.to_str().unwrap());
        assert_eq!(info.video_codec.as_deref(), Some("h264"), "{info:?}");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn progress_from_out_time() {
        let percent = parse_progress_line("out_time_ms=5000000", 10.0).unwrap();
        assert!((percent - 50.0).abs() < 0.1);
    }

    #[test]
    fn sidecar_filename_includes_target_triple() {
        let name = sidecar_filename("ffmpeg");
        assert!(name.contains("ffmpeg"));
        if cfg!(windows) {
            assert!(name.ends_with(".exe"));
        }
    }

    #[test]
    fn resolve_ffprobe_if_bundled() {
        match resolve_binary("ffprobe") {
            Ok(path) => assert!(path.is_file()),
            Err(_) => eprintln!("skip: run npm run fetch-ffmpeg first"),
        }
    }

    #[test]
    fn probe_generated_clip_with_restricted_path() {
        let Ok(ffmpeg) = resolve_binary("ffmpeg") else {
            eprintln!("skip: run npm run fetch-ffmpeg first");
            return;
        };
        let dir = env::temp_dir().join(format!("video-converter-probe-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let sample = dir.join("sample.mp4");
        let status = Command::new(ffmpeg)
            .args([
                "-hide_banner",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=160x120:rate=10",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=1",
                "-shortest",
                "-c:v",
                "libx264",
                "-c:a",
                "aac",
                sample.to_str().unwrap(),
            ])
            .env("PATH", restricted_path())
            .status()
            .unwrap();
        assert!(status.success());
        let info = probe_media(sample.to_str().unwrap());
        assert!(info.importable, "{info:?}");
        assert_eq!(info.video_codec.as_deref(), Some("h264"));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn ffmpeg_accepts_brackets_and_partial_before_extension() {
        let Ok(ffmpeg) = resolve_binary("ffmpeg") else {
            eprintln!("skip: run npm run fetch-ffmpeg first");
            return;
        };
        let dir = env::temp_dir().join(format!(
            "video-converter-partial-{}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let sample = dir.join("[4K高清]clip.mp4");
        let sample_arg = ffmpeg_file_arg(sample.to_str().unwrap());
        let status = Command::new(&ffmpeg)
            .args([
                "-hide_banner",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=160x120:rate=10",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=1",
                "-shortest",
                "-c:v",
                "libx264",
                "-c:a",
                "aac",
                sample_arg.as_str(),
            ])
            .env("PATH", restricted_path())
            .status()
            .unwrap();
        assert!(status.success());

        let partial = dir.join("[4K高清]clip.partial.mp4");
        let partial_arg = ffmpeg_file_arg(partial.to_str().unwrap());
        let status = Command::new(&ffmpeg)
            .args([
                "-hide_banner",
                "-y",
                "-i",
                sample_arg.as_str(),
                "-c:v",
                "copy",
                "-c:a",
                "copy",
                "-f",
                "mp4",
                partial_arg.as_str(),
            ])
            .env("PATH", restricted_path())
            .status()
            .unwrap();
        assert!(status.success(), "partial mux should succeed");
        assert!(partial.is_file());
        let _ = std::fs::remove_dir_all(dir);
    }
}
