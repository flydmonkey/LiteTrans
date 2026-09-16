use std::path::{Path, PathBuf};

pub fn source_stem(path: &str) -> String {
    Path::new(path)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("output")
        .to_string()
}

/// FFmpeg 按最后一个扩展名选 muxer，所以临时文件必须是 `stem.partial.mp4`，
/// 不能是 `stem.mp4.partial`。
pub fn partial_output_path(output: &Path) -> PathBuf {
    let ext = output
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("bin");
    let stem = output
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("output");
    match output.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => {
            parent.join(format!("{stem}.partial.{ext}"))
        }
        _ => PathBuf::from(format!("{stem}.partial.{ext}")),
    }
}

/// 避免路径里的 `[` `]` 被当成 glob。
pub fn ffmpeg_file_arg(path: &str) -> String {
    if path.starts_with("file:") || path.starts_with("pipe:") {
        path.to_string()
    } else {
        let normalized = if cfg!(windows) {
            path.replace('\\', "/")
        } else {
            path.to_string()
        };
        format!("file:{normalized}")
    }
}

pub fn allocate_output_path(
    output_dir: &Path,
    stem: &str,
    ext: &str,
    exists: impl Fn(&Path) -> bool,
) -> PathBuf {
    let candidate = output_dir.join(format!("{stem}.{ext}"));
    if !exists(&candidate) {
        return candidate;
    }
    let mut index = 1;
    loop {
        let candidate = output_dir.join(format!("{stem}-{index}.{ext}"));
        if !exists(&candidate) {
            return candidate;
        }
        index += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn unique_name() {
        let path = allocate_output_path(Path::new("/out"), "clip", "mp4", |_| false);
        assert_eq!(path, PathBuf::from("/out/clip.mp4"));
    }

    #[test]
    fn collision_uses_numeric_suffix() {
        let existing = PathBuf::from("/out/clip.mp4");
        let path = allocate_output_path(Path::new("/out"), "clip", "mp4", |p| p == existing);
        assert_eq!(path, PathBuf::from("/out/clip-1.mp4"));
    }

    #[test]
    fn collision_skips_taken_suffixes() {
        let path = allocate_output_path(Path::new("/out"), "clip", "mp4", |p| {
            p.file_name()
                .and_then(|s| s.to_str())
                .is_some_and(|name| name == "clip.mp4" || name == "clip-1.mp4")
        });
        assert_eq!(path, PathBuf::from("/out/clip-2.mp4"));
    }

    #[test]
    fn partial_keeps_real_extension() {
        let path = partial_output_path(Path::new("/out/[4K高清]clip_mp4-h264.mp4"));
        assert_eq!(
            path,
            PathBuf::from("/out/[4K高清]clip_mp4-h264.partial.mp4")
        );
    }

    #[test]
    fn ffmpeg_file_arg_prefixes_local_paths() {
        assert_eq!(
            ffmpeg_file_arg("/tmp/[4K]clip.mp4"),
            "file:/tmp/[4K]clip.mp4"
        );
        assert_eq!(ffmpeg_file_arg("pipe:1"), "pipe:1");
    }

    #[test]
    fn ffmpeg_file_arg_uses_forward_slashes_on_windows() {
        let value = ffmpeg_file_arg(r"C:\Users\a\[4K]clip.mp4");
        if cfg!(windows) {
            assert_eq!(value, "file:C:/Users/a/[4K]clip.mp4");
        } else {
            assert_eq!(value, r"file:C:\Users\a\[4K]clip.mp4");
        }
    }
}
