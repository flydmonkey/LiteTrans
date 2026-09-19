use crate::naming::ffmpeg_file_arg;
use crate::presets::ResolvedConfig;
use crate::probe::MediaInfo;

const CONTAINERS: &[&str] = &[
    "mp4", "webm", "mkv", "mov", "avi", "gif", "mp3", "m4a", "wav", "flac", "ogg", "amr",
];
const VIDEO_ENCODERS: &[&str] = &["h264", "h265", "vp9", "mpeg4", "gif", "copy"];
const AUDIO_ENCODERS: &[&str] = &[
    "aac", "opus", "mp3", "copy", "pcm_s16le", "flac", "libvorbis", "amr_nb",
];

pub fn validate(config: &ResolvedConfig, media: &MediaInfo) -> Result<(), String> {
    if !CONTAINERS.contains(&config.container.as_str()) {
        return Err(format!("不支持的容器：{}", config.container));
    }
    if let Some(encoder) = &config.video_encoder {
        if !VIDEO_ENCODERS.contains(&encoder.as_str()) {
            return Err(format!("不支持的视频编码器：{encoder}"));
        }
    }
    if let Some(encoder) = &config.audio_encoder {
        if !AUDIO_ENCODERS.contains(&encoder.as_str()) {
            return Err(format!("不支持的音频编码器：{encoder}"));
        }
    }

    if is_audio_only(config) {
        if media.audio_codec.is_none() {
            return Err("该文件没有音频流，无法导出音频".into());
        }
        return Ok(());
    }

    if config.container == "gif" || config.preset == "gif" {
        if media.video_codec.is_none() {
            return Err("该文件没有视频流，无法导出 GIF".into());
        }
        return Ok(());
    }

    if config.video_encoder.as_deref() == Some("copy") {
        let Some(codec) = media.video_codec.as_deref() else {
            return Err("该文件没有视频流，无法复制视频".into());
        };
        if !container_accepts_video(&config.container, codec) {
            return Err("目标容器不支持当前视频编码，请改为重新编码".into());
        }
        if config.max_width.is_some() || config.max_height.is_some() || config.frame_rate.is_some()
        {
            return Err("复制视频流时不能同时修改分辨率或帧率，请改为重新编码".into());
        }
    }

    if config.audio_encoder.as_deref() == Some("copy") {
        if let Some(codec) = media.audio_codec.as_deref() {
            if !container_accepts_audio(&config.container, codec)
                && config.video_encoder.as_deref() != Some("copy")
            {
                return Err("目标容器不支持当前音频编码，请改为重新编码".into());
            }
        }
    }

    Ok(())
}

pub fn container_accepts_video(container: &str, codec: &str) -> bool {
    let codec = normalize_codec(codec);
    match container {
        "mp4" | "mov" => matches!(codec, "h264" | "hevc" | "mpeg4" | "av1"),
        "webm" => matches!(codec, "vp8" | "vp9" | "av1"),
        "avi" => matches!(codec, "mpeg4" | "h264" | "mjpeg" | "mpeg1video" | "mpeg2video"),
        "gif" => true,
        "mkv" => true,
        _ => false,
    }
}

pub fn container_accepts_audio(container: &str, codec: &str) -> bool {
    let codec = normalize_codec(codec);
    match container {
        "mp4" | "mov" | "m4a" => matches!(codec, "aac" | "mp3" | "ac3" | "alac"),
        "webm" => matches!(codec, "opus" | "vorbis"),
        "avi" => matches!(codec, "mp3" | "mp2" | "ac3" | "pcm_s16le"),
        "mkv" => true,
        "mp3" => codec == "mp3",
        _ => false,
    }
}

fn normalize_codec(codec: &str) -> &str {
    match codec {
        "h265" => "hevc",
        other => other,
    }
}

pub fn build_ffmpeg_args(
    input: &str,
    output_partial: &str,
    config: &ResolvedConfig,
    media: &MediaInfo,
) -> Result<Vec<String>, String> {
    validate(config, media)?;

    let mut args = start_args(input, config, media);

    if is_audio_only(config) {
        let encoder = config.audio_encoder.as_deref().unwrap_or(if config.container == "m4a" {
            "aac"
        } else {
            "mp3"
        });
        args.extend([
            "-vn".into(),
            "-c:a".into(),
            ffmpeg_audio_codec(encoder).into(),
        ]);
        if encoder == "amr_nb" {
            args.extend(["-ar".into(), "8000".into(), "-ac".into(), "1".into()]);
        }
        if let Some(bitrate) = config.audio_bitrate_kbps {
            args.push("-b:a".into());
            args.push(audio_bitrate_arg(encoder, bitrate));
        }
        push_output(&mut args, &config.container, output_partial);
        return Ok(args);
    }

    if config.container == "gif" || config.preset == "gif" {
        args.extend(["-an".into(), "-c:v".into(), "gif".into()]);
        if let Some(filter) = gif_filter(config, media) {
            args.push("-vf".into());
            args.push(filter);
        }
        push_output(&mut args, "gif", output_partial);
        return Ok(args);
    }

    let video_encoder = config.video_encoder.as_deref().unwrap_or("h264");
    args.push("-c:v".into());
    args.push(ffmpeg_video_codec(video_encoder).into());

    if video_encoder != "copy" {
        if let Some(bitrate) = config.video_bitrate_kbps {
            args.push("-b:v".into());
            args.push(format!("{bitrate}k"));
        } else {
            args.extend(video_quality_args(video_encoder, &config.quality));
        }

        if video_encoder == "h264" || video_encoder == "h265" || video_encoder == "mpeg4" {
            args.extend(["-pix_fmt".into(), "yuv420p".into()]);
        }
        if video_encoder == "h265" && matches!(config.container.as_str(), "mp4" | "mov") {
            args.extend(["-tag:v".into(), "hvc1".into()]);
        }
        if video_encoder == "mpeg4" && config.container == "avi" {
            args.extend(["-vtag".into(), "xvid".into()]);
        }

        if let Some(fps) = config.frame_rate {
            args.push("-r".into());
            args.push(fps.to_string());
        }

        if let Some(filter) = scale_filter(config, media, true) {
            args.push("-vf".into());
            args.push(filter);
        }
    }

    if !config.keep_audio || media.audio_codec.is_none() {
        args.push("-an".into());
    } else {
        let audio_encoder = effective_audio_encoder(config, media);
        args.push("-c:a".into());
        args.push(ffmpeg_audio_codec(audio_encoder).into());
        if audio_encoder != "copy" {
            if audio_encoder == "amr_nb" {
                args.extend(["-ar".into(), "8000".into(), "-ac".into(), "1".into()]);
            }
            if let Some(bitrate) = config.audio_bitrate_kbps {
                args.push("-b:a".into());
                args.push(audio_bitrate_arg(audio_encoder, bitrate));
            }
        }
    }

    if config.container == "mp4" || config.container == "mov" {
        args.extend(["-movflags".into(), "+faststart".into()]);
    }

    push_output(&mut args, &config.container, output_partial);
    Ok(args)
}

fn start_args(input: &str, config: &ResolvedConfig, media: &MediaInfo) -> Vec<String> {
    let mut args = vec![
        "-hide_banner".into(),
        "-nostats".into(),
        "-progress".into(),
        "pipe:1".into(),
        "-y".into(),
    ];
    if let Some((start, duration)) = trim_window(config, media) {
        let stream_copy = config.video_encoder.as_deref() == Some("copy");
        if stream_copy {
            args.extend([
                "-ss".into(),
                format_secs(start),
                "-i".into(),
                ffmpeg_file_arg(input),
                "-t".into(),
                format_secs(duration),
            ]);
        } else if start > 1.5 {
            args.extend([
                "-ss".into(),
                format_secs(start - 1.5),
                "-i".into(),
                ffmpeg_file_arg(input),
                "-ss".into(),
                format_secs(1.5),
                "-t".into(),
                format_secs(duration),
            ]);
        } else {
            args.extend([
                "-i".into(),
                ffmpeg_file_arg(input),
                "-ss".into(),
                format_secs(start),
                "-t".into(),
                format_secs(duration),
            ]);
        }
    } else {
        args.extend(["-i".into(), ffmpeg_file_arg(input)]);
    }
    args
}

pub fn output_duration_secs(config: &ResolvedConfig, media: &MediaInfo) -> f64 {
    if let Some((_, duration)) = trim_window(config, media) {
        duration
    } else {
        media.duration_secs.unwrap_or(0.0)
    }
}

fn trim_window(config: &ResolvedConfig, media: &MediaInfo) -> Option<(f64, f64)> {
    let total = media.duration_secs.filter(|value| *value > 0.05)?;
    let start = config.trim_start_secs.unwrap_or(0.0).clamp(0.0, total);
    let end = config.trim_end_secs.unwrap_or(total).clamp(0.0, total);
    if end - start < 0.05 {
        return None;
    }
    if start <= 0.05 && total - end <= 0.05 {
        return None;
    }
    Some((start, end - start))
}

fn format_secs(value: f64) -> String {
    format!("{value:.3}")
}

fn push_output(args: &mut Vec<String>, container: &str, output_partial: &str) {
    args.push("-f".into());
    args.push(ffmpeg_muxer(container).into());
    args.push(ffmpeg_file_arg(output_partial));
}

fn ffmpeg_muxer(container: &str) -> &'static str {
    match container {
        "mkv" => "matroska",
        "webm" => "webm",
        "mov" => "mov",
        "avi" => "avi",
        "gif" => "gif",
        "mp3" => "mp3",
        "m4a" => "ipod",
        "wav" => "wav",
        "flac" => "flac",
        "ogg" => "ogg",
        "amr" => "amr",
        _ => "mp4",
    }
}

fn uses_videotoolbox(encoder: &str) -> bool {
    cfg!(target_os = "macos") && matches!(encoder, "h264" | "h265")
}

fn ffmpeg_video_codec(encoder: &str) -> &'static str {
    match encoder {
        "h264" if uses_videotoolbox(encoder) => "h264_videotoolbox",
        "h264" => "libx264",
        "h265" if uses_videotoolbox(encoder) => "hevc_videotoolbox",
        "h265" => "libx265",
        "vp9" => "libvpx-vp9",
        "mpeg4" => "mpeg4",
        "gif" => "gif",
        "copy" => "copy",
        _ => "libx264",
    }
}

const AMR_NB_RATES_BPS: &[u32] = &[4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200];

fn audio_bitrate_arg(encoder: &str, kbps: u32) -> String {
    if encoder == "amr_nb" {
        format!("{}k", amr_nb_bitrate_label(kbps))
    } else {
        format!("{kbps}k")
    }
}

fn amr_nb_bitrate_label(kbps: u32) -> &'static str {
    const LABELS: &[&str] = &[
        "4.75", "5.15", "5.90", "6.70", "7.40", "7.95", "10.2", "12.2",
    ];
    let target = if kbps >= 1000 { kbps } else { kbps.saturating_mul(1000) };
    let idx = AMR_NB_RATES_BPS
        .iter()
        .enumerate()
        .min_by_key(|(_, rate)| rate.abs_diff(target))
        .map(|(i, _)| i)
        .unwrap_or(LABELS.len() - 1);
    LABELS[idx]
}

fn ffmpeg_audio_codec(encoder: &str) -> &'static str {
    match encoder {
        "aac" => "aac",
        "opus" => "libopus",
        "mp3" => "libmp3lame",
        "pcm_s16le" => "pcm_s16le",
        "flac" => "flac",
        "libvorbis" => "libvorbis",
        "amr_nb" => "libopencore_amrnb",
        "copy" => "copy",
        _ => "aac",
    }
}

fn fallback_audio_encoder(container: &str) -> &'static str {
    match container {
        "webm" => "opus",
        "mp3" => "mp3",
        "avi" => "mp3",
        _ => "aac",
    }
}

fn effective_audio_encoder<'a>(config: &'a ResolvedConfig, media: &MediaInfo) -> &'a str {
    let encoder = config.audio_encoder.as_deref().unwrap_or("aac");
    if encoder == "copy" {
        if let Some(codec) = media.audio_codec.as_deref() {
            if !container_accepts_audio(&config.container, codec) {
                return fallback_audio_encoder(&config.container);
            }
        }
    }
    encoder
}

fn is_audio_only(config: &ResolvedConfig) -> bool {
    matches!(
        config.container.as_str(),
        "mp3" | "m4a" | "wav" | "flac" | "ogg" | "amr"
    ) || config.preset.starts_with("audio-")
}

fn video_quality_args(encoder: &str, quality: &str) -> Vec<String> {
    if uses_videotoolbox(encoder) {
        let q = match quality {
            "original" | "high" => "70",
            "small" => "40",
            _ => "55",
        };
        let mut args = vec![
            "-allow_sw".into(),
            "1".into(),
            "-q:v".into(),
            q.into(),
        ];
        if encoder == "h264" {
            args.extend(["-profile:v".into(), "high".into()]);
        }
        return args;
    }
    match encoder {
        "h264" => vec![
            "-preset".into(),
            "medium".into(),
            "-crf".into(),
            match quality {
                "original" | "high" => "16",
                "small" => "28",
                _ => "23",
            }
            .into(),
        ],
        "h265" => vec![
            "-preset".into(),
            "medium".into(),
            "-crf".into(),
            match quality {
                "original" | "high" => "18",
                "small" => "32",
                _ => "28",
            }
            .into(),
        ],
        "vp9" => vec![
            "-row-mt".into(),
            "1".into(),
            "-deadline".into(),
            "good".into(),
            "-cpu-used".into(),
            match quality {
                "original" | "high" => "2",
                "small" => "6",
                _ => "5",
            }
            .into(),
            "-b:v".into(),
            "0".into(),
            "-crf".into(),
            match quality {
                "original" | "high" => "20",
                "small" => "40",
                _ => "32",
            }
            .into(),
        ],
        "mpeg4" => vec![
            "-q:v".into(),
            match quality {
                "original" | "high" => "2",
                "small" => "10",
                _ => "6",
            }
            .into(),
        ],
        _ => Vec::new(),
    }
}

fn gif_fps(quality: &str) -> &'static str {
    match quality {
        "original" | "high" => "15",
        "small" => "8",
        _ => "12",
    }
}

fn gif_filter(config: &ResolvedConfig, media: &MediaInfo) -> Option<String> {
    let fps = gif_fps(&config.quality);
    match scale_filter(config, media, false) {
        Some(scale) => Some(format!("fps={fps},{scale}")),
        None => Some(format!("fps={fps}")),
    }
}

fn scale_filter(config: &ResolvedConfig, media: &MediaInfo, even: bool) -> Option<String> {
    if config.max_width.is_none() && config.max_height.is_none() {
        return None;
    }
    let max_w = config.max_width.unwrap_or(u32::MAX);
    let max_h = config.max_height.unwrap_or(u32::MAX);
    if let (Some(w), Some(h)) = (media.width, media.height) {
        if w <= max_w && h <= max_h {
            return None;
        }
    }
    let scale = format!(
        "scale='min(iw,{max_w}):min(ih,{max_h}):force_original_aspect_ratio=decrease'"
    );
    if even {
        Some(format!("{scale},scale=trunc(iw/2)*2:trunc(ih/2)*2"))
    } else {
        Some(scale)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::{resolve_config, OutputConfig};
    use crate::probe::MediaInfo;

    fn h264_source() -> MediaInfo {
        MediaInfo {
            path: "/tmp/a.mkv".into(),
            duration_secs: Some(5.0),
            container: Some("matroska".into()),
            video_codec: Some("h264".into()),
            width: Some(1920),
            height: Some(1080),
            frame_rate: Some(30.0),
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
    fn rejects_unknown_encoder() {
        let mut config = resolve_config(&OutputConfig::default()).unwrap();
        config.video_encoder = Some("mpeg2".into());
        let err = validate(&config, &h264_source()).unwrap_err();
        assert!(err.contains("不支持的视频编码器"));
    }

    #[test]
    fn rejects_audio_mp3_without_audio() {
        let config = resolve_config(&OutputConfig {
            preset: "audio-mp3".into(),
            ..Default::default()
        })
        .unwrap();
        let mut media = h264_source();
        media.audio_codec = None;
        let err = validate(&config, &media).unwrap_err();
        assert!(err.contains("没有音频流"));
    }

    #[test]
    fn original_quality_is_tighter() {
        let config = resolve_config(&OutputConfig {
            quality: Some("original".into()),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.mp4", &config, &h264_source()).unwrap();
        if cfg!(target_os = "macos") {
            let codec = args.iter().position(|a| a == "-c:v").unwrap();
            assert_eq!(args[codec + 1], "h264_videotoolbox");
            let q = args.iter().position(|a| a == "-q:v").unwrap();
            assert_eq!(args[q + 1], "70");
        } else {
            let crf = args.iter().position(|a| a == "-crf").unwrap();
            assert_eq!(args[crf + 1], "16");
        }
    }

    #[test]
    fn gif_preset_has_fps_filter() {
        let config = resolve_config(&OutputConfig {
            preset: "gif".into(),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.gif", &config, &h264_source()).unwrap();
        assert!(args.contains(&"gif".into()));
        let vf = args.iter().position(|a| a == "-vf").unwrap();
        assert!(args[vf + 1].starts_with("fps="));
    }

    #[test]
    fn copy_h264_into_mp4_is_allowed() {
        let config = resolve_config(&OutputConfig {
            preset: "custom".into(),
            container: Some("mp4".into()),
            video_encoder: Some("copy".into()),
            audio_encoder: Some("copy".into()),
            ..Default::default()
        })
        .unwrap();
        validate(&config, &h264_source()).unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial", &config, &h264_source()).unwrap();
        assert!(args.contains(&"copy".into()));
        assert!(!args.iter().any(|a| a == "-vf"));
    }

    #[test]
    fn copy_vp9_into_mp4_is_rejected() {
        let config = resolve_config(&OutputConfig {
            preset: "custom".into(),
            container: Some("mp4".into()),
            video_encoder: Some("copy".into()),
            ..Default::default()
        })
        .unwrap();
        let mut media = h264_source();
        media.video_codec = Some("vp9".into());
        let err = validate(&config, &media).unwrap_err();
        assert!(err.contains("请改为重新编码"));
    }

    #[test]
    fn scale_filter_added_when_source_is_larger() {
        let config = resolve_config(&OutputConfig {
            preset: "mp4-h264".into(),
            max_width: Some(1280),
            max_height: Some(720),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial", &config, &h264_source()).unwrap();
        let vf = args.iter().position(|a| a == "-vf").unwrap();
        assert!(args[vf + 1].contains("1280"));
    }

    #[test]
    fn args_are_argv_not_shell_string() {
        let config = resolve_config(&OutputConfig::default()).unwrap();
        let args = build_ffmpeg_args(
            "/tmp/my file.mp4",
            "/tmp/out.partial",
            &config,
            &h264_source(),
        )
        .unwrap();
        assert!(args.iter().any(|a| a == "file:/tmp/my file.mp4"));
        assert!(!args.iter().any(|a| a.contains("ffmpeg ")));
    }

    #[test]
    fn output_uses_muxer_and_file_protocol() {
        let config = resolve_config(&OutputConfig::default()).unwrap();
        let args = build_ffmpeg_args(
            "/tmp/[4K]a.mp4",
            "/tmp/[4K]a.partial.mp4",
            &config,
            &h264_source(),
        )
        .unwrap();
        let input_at = args.iter().position(|a| a == "-i").unwrap();
        assert_eq!(args[input_at + 1], "file:/tmp/[4K]a.mp4");
        let format_at = args.iter().rposition(|a| a == "-f").unwrap();
        assert_eq!(args[format_at + 1], "mp4");
        assert_eq!(args[format_at + 2], "file:/tmp/[4K]a.partial.mp4");
    }

    #[test]
    fn trim_adds_seek_and_duration() {
        let config = resolve_config(&OutputConfig {
            trim_start_secs: Some(1.0),
            trim_end_secs: Some(3.0),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.mp4", &config, &h264_source()).unwrap();
        let input_at = args.iter().position(|a| a == "-i").unwrap();
        let ss = args.iter().position(|a| a == "-ss").unwrap();
        assert!(ss > input_at, "re-encode trim should seek after opening the file");
        assert_eq!(args[ss + 1], "1.000");
        let t = args.iter().position(|a| a == "-t").unwrap();
        assert_eq!(args[t + 1], "2.000");
        assert!((output_duration_secs(&config, &h264_source()) - 2.0).abs() < 0.01);
    }

    #[test]
    fn copy_trim_seeks_before_input() {
        let config = resolve_config(&OutputConfig {
            preset: "mp4-copy".into(),
            trim_start_secs: Some(1.0),
            trim_end_secs: Some(3.0),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.mp4", &config, &h264_source()).unwrap();
        let ss = args.iter().position(|a| a == "-ss").unwrap();
        assert!(ss < args.iter().position(|a| a == "-i").unwrap());
        assert_eq!(args[ss + 1], "1.000");
    }

    #[test]
    fn long_reencode_trim_uses_hybrid_seek() {
        let config = resolve_config(&OutputConfig {
            trim_start_secs: Some(10.0),
            trim_end_secs: Some(12.0),
            ..Default::default()
        })
        .unwrap();
        let mut media = h264_source();
        media.duration_secs = Some(30.0);
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.mp4", &config, &media).unwrap();
        let first_ss = args.iter().position(|a| a == "-ss").unwrap();
        let input_at = args.iter().position(|a| a == "-i").unwrap();
        assert!(first_ss < input_at);
        assert_eq!(args[first_ss + 1], "8.500");
        let second_ss = args.iter().rposition(|a| a == "-ss").unwrap();
        assert!(second_ss > input_at);
        assert_eq!(args[second_ss + 1], "1.500");
    }

    #[test]
    fn vp9_uses_faster_deadline() {
        let config = resolve_config(&OutputConfig {
            preset: "webm-vp9".into(),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.webm", &config, &h264_source()).unwrap();
        assert_eq!(args[args.iter().position(|a| a == "-c:v").unwrap() + 1], "libvpx-vp9");
        assert!(args.contains(&"-row-mt".into()));
        assert!(args.contains(&"good".into()));
        assert!(args.contains(&"-cpu-used".into()));
    }

    #[test]
    fn copy_incompatible_audio_falls_back_to_aac() {
        let config = resolve_config(&OutputConfig {
            preset: "mp4-copy".into(),
            ..Default::default()
        })
        .unwrap();
        let mut media = h264_source();
        media.audio_codec = Some("flac".into());
        validate(&config, &media).unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.mp4", &config, &media).unwrap();
        let video = args.iter().position(|a| a == "-c:v").unwrap();
        assert_eq!(args[video + 1], "copy");
        let audio = args.iter().position(|a| a == "-c:a").unwrap();
        assert_eq!(args[audio + 1], "aac");
    }

    #[test]
    fn full_clip_omits_trim() {
        let config = resolve_config(&OutputConfig::default()).unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.mp4", &config, &h264_source()).unwrap();
        assert!(!args.contains(&"-ss".into()));
        assert!(!args.contains(&"-t".into()));
    }

    fn assert_legal_amr_nb_bitrate(value: &str) {
        const LEGAL: &[&str] = &[
            "4.75k", "5.15k", "5.90k", "6.70k", "7.40k", "7.95k", "10.2k", "12.2k",
        ];
        assert!(
            LEGAL.contains(&value),
            "AMR-NB bitrate must be a legal rate, got {value}"
        );
    }

    #[test]
    fn amr_forces_8khz_mono_and_legal_bitrate() {
        let config = resolve_config(&OutputConfig {
            preset: "audio-amr".into(),
            quality: Some("original".into()),
            ..Default::default()
        })
        .unwrap();
        let args = build_ffmpeg_args("/tmp/a.mkv", "/tmp/a.partial.amr", &config, &h264_source()).unwrap();
        assert_eq!(args[args.iter().position(|a| a == "-c:a").unwrap() + 1], "libopencore_amrnb");
        assert_eq!(args[args.iter().position(|a| a == "-ar").unwrap() + 1], "8000");
        assert_eq!(args[args.iter().position(|a| a == "-ac").unwrap() + 1], "1");
        assert_legal_amr_nb_bitrate(&args[args.iter().position(|a| a == "-b:a").unwrap() + 1]);
    }

    #[test]
    fn lossless_wav_and_flac_omit_audio_bitrate() {
        for preset in ["audio-wav", "audio-flac"] {
            let config = resolve_config(&OutputConfig {
                preset: preset.into(),
                ..Default::default()
            })
            .unwrap();
            assert!(
                config.audio_bitrate_kbps.is_none(),
                "{preset} should not invent a bitrate"
            );
            let ext = if preset == "audio-wav" { "wav" } else { "flac" };
            let args = build_ffmpeg_args(
                "/tmp/a.mkv",
                &format!("/tmp/a.partial.{ext}"),
                &config,
                &h264_source(),
            )
            .unwrap();
            assert!(
                !args.iter().any(|a| a == "-b:a"),
                "{preset} must not pass -b:a, got {args:?}"
            );
        }
    }
}
