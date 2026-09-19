use std::fs::{self, File};
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, ImageFormat};
use lopdf::Document as PdfDocument;
use printpdf::{BuiltinFont, Mm, PdfDocument as PrintPdfDocument};

use crate::convert::clamp_page_range;
use crate::engine::{current_target_triple, sidecar_filename};
use crate::naming::partial_output_path;
use crate::presets::normalize_quality;
use crate::queue::Job;

const CANNOT_CONVERT: &str = "无法转换该文档";
const CANCELLED: &str = "cancelled";

pub fn run_job(
    job: &Job,
    on_progress: impl Fn(f64),
    is_cancelled: impl Fn() -> bool,
) -> Result<Vec<String>, String> {
    check_cancel(&is_cancelled)?;
    let dests = destinations(job)?;
    let preset = job.config.preset.as_str();
    if preset.starts_with("image-") {
        on_progress(0.0);
        convert_image(job, &dests[0], &is_cancelled)?;
        on_progress(100.0);
        return Ok(vec![dests[0].to_string_lossy().to_string()]);
    }
    match preset {
        "office-pdf" => {
            on_progress(0.0);
            convert_office(&job.source_path, &dests[0], &is_cancelled)?;
            on_progress(100.0);
            Ok(vec![dests[0].to_string_lossy().to_string()])
        }
        "pdf-split" => split_pdf(job, &dests, &on_progress, &is_cancelled),
        "pdf-image" => rasterize_pdf(job, &dests, &on_progress, &is_cancelled),
        "pdf-txt" => extract_pdf_text(job, &dests[0], &on_progress, &is_cancelled),
        "pdf-compress" => compress_pdf(job, &dests[0], &on_progress, &is_cancelled),
        other => Err(format!("未知预设：{other}")),
    }
}

fn check_cancel(is_cancelled: &impl Fn() -> bool) -> Result<(), String> {
    if is_cancelled() {
        Err(CANCELLED.into())
    } else {
        Ok(())
    }
}

fn destinations(job: &Job) -> Result<Vec<PathBuf>, String> {
    if !job.output_paths.is_empty() {
        return Ok(job.output_paths.iter().map(PathBuf::from).collect());
    }
    job.output_path
        .as_ref()
        .map(|path| vec![PathBuf::from(path)])
        .ok_or_else(|| "没有输出路径".into())
}

fn write_replacing(dest: &Path, write_partial: impl FnOnce(&Path) -> Result<(), String>) -> Result<String, String> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|err| format!("无法写入输出文件：{err}"))?;
    }
    let partial = partial_output_path(dest);
    let _ = fs::remove_file(&partial);
    match write_partial(&partial) {
        Ok(()) => {
            if dest.exists() {
                let _ = fs::remove_file(dest);
            }
            fs::rename(&partial, dest).map_err(|err| format!("无法写入输出文件：{err}"))?;
            Ok(dest.to_string_lossy().to_string())
        }
        Err(err) => {
            let _ = fs::remove_file(&partial);
            Err(err)
        }
    }
}

fn quality_label(job: &Job) -> String {
    normalize_quality(job.config.quality.as_deref())
}

fn jpeg_quality(quality: &str, compressing: bool) -> u8 {
    if compressing {
        match quality {
            "original" | "high" => 92,
            "small" => 60,
            _ => 75,
        }
    } else {
        75
    }
}

fn scale_for_quality(quality: &str, compressing: bool) -> f32 {
    if compressing && quality == "small" {
        0.7
    } else {
        1.0
    }
}

fn pdf_image_max_edge(quality: &str) -> i32 {
    match quality {
        "original" | "high" => 1600,
        "small" => 800,
        _ => 1200,
    }
}

fn dest_extension(path: &Path, fallback: &str) -> String {
    path.extension()
        .and_then(|s| s.to_str())
        .map(|s| s.to_ascii_lowercase())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| fallback.to_string())
}

fn convert_image(job: &Job, dest: &Path, is_cancelled: &impl Fn() -> bool) -> Result<(), String> {
    check_cancel(is_cancelled)?;
    let mut img = image::open(&job.source_path).map_err(|_| "无法读取该文件".to_string())?;
    check_cancel(is_cancelled)?;
    let quality = quality_label(job);
    let compressing = job.config.preset == "image-compress";
    let ext = dest_extension(dest, "jpg");
    let lossless = matches!(ext.as_str(), "png" | "bmp");
    if compressing && lossless {
        let scale = scale_for_quality(&quality, true);
        if scale < 1.0 {
            let width = ((img.width() as f32) * scale).round().max(1.0) as u32;
            let height = ((img.height() as f32) * scale).round().max(1.0) as u32;
            img = img.resize_exact(width, height, image::imageops::FilterType::Triangle);
        }
    }
    let jpeg_q = jpeg_quality(&quality, compressing);
    write_replacing(dest, |partial| write_image(&img, partial, &ext, Some(jpeg_q)))?;
    Ok(())
}

fn write_image(img: &DynamicImage, path: &Path, ext: &str, jpeg_quality: Option<u8>) -> Result<(), String> {
    match ext {
        "jpg" | "jpeg" => {
            let rgb = img.to_rgb8();
            let file = File::create(path).map_err(|err| format!("无法写入输出文件：{err}"))?;
            let mut writer = BufWriter::new(file);
            let mut encoder = JpegEncoder::new_with_quality(&mut writer, jpeg_quality.unwrap_or(75));
            encoder
                .encode_image(&rgb)
                .map_err(|err| format!("无法写出图片：{err}"))?;
            writer.flush().map_err(|err| format!("无法写入输出文件：{err}"))
        }
        "png" => img
            .save_with_format(path, ImageFormat::Png)
            .map_err(|err| format!("无法写出图片：{err}")),
        "webp" => img
            .save_with_format(path, ImageFormat::WebP)
            .map_err(|err| format!("无法写出图片：{err}")),
        "bmp" => img
            .save_with_format(path, ImageFormat::Bmp)
            .map_err(|err| format!("无法写出图片：{err}")),
        "gif" => img
            .save_with_format(path, ImageFormat::Gif)
            .map_err(|err| format!("无法写出图片：{err}")),
        _ => Err("无法写出图片".into()),
    }
}

fn page_range(job: &Job, page_count: u32) -> (u32, u32) {
    clamp_page_range(
        job.media.page_start.unwrap_or(1),
        job.media.page_end.unwrap_or(page_count),
        page_count,
    )
}

fn load_pdf(path: &str) -> Result<PdfDocument, String> {
    PdfDocument::load(path).map_err(|_| "无法读取该文件".to_string())
}

fn split_pdf(
    job: &Job,
    dests: &[PathBuf],
    on_progress: &impl Fn(f64),
    is_cancelled: &impl Fn() -> bool,
) -> Result<Vec<String>, String> {
    let source = load_pdf(&job.source_path)?;
    let count = source.get_pages().len() as u32;
    if count == 0 {
        return Err("无法读取该文件".into());
    }
    let (start, end) = page_range(job, count);
    let total = (end - start + 1) as usize;
    if dests.len() < total {
        return Err("没有输出路径".into());
    }
    let mut written = Vec::new();
    for (offset, page_num) in (start..=end).enumerate() {
        check_cancel(is_cancelled)?;
        let mut one = load_pdf(&job.source_path)?;
        let delete: Vec<u32> = one
            .get_pages()
            .keys()
            .copied()
            .filter(|num| *num != page_num)
            .collect();
        if !delete.is_empty() {
            one.delete_pages(&delete);
        }
        written.push(write_replacing(&dests[offset], |partial| {
            one.save(partial).map_err(|err| format!("无法写入输出文件：{err}"))?;
            Ok(())
        })?);
        on_progress((offset + 1) as f64 / total as f64 * 100.0);
    }
    Ok(written)
}

fn extract_pdf_text(
    job: &Job,
    dest: &Path,
    on_progress: &impl Fn(f64),
    is_cancelled: &impl Fn() -> bool,
) -> Result<Vec<String>, String> {
    check_cancel(is_cancelled)?;
    let pages = pdf_extract::extract_text_by_pages(&job.source_path)
        .map_err(|_| "无法读取该文件".to_string())?;
    let count = pages.len().max(1) as u32;
    let (start, end) = page_range(job, count);
    let total = (end - start + 1) as usize;
    let mut parts = Vec::new();
    for (offset, page_num) in (start..=end).enumerate() {
        check_cancel(is_cancelled)?;
        let text = pages
            .get((page_num - 1) as usize)
            .cloned()
            .unwrap_or_default();
        parts.push(text);
        on_progress((offset + 1) as f64 / total as f64 * 100.0);
    }
    let combined = parts.join("\n");
    if combined.trim().is_empty() {
        return Err("没有可提取的文本".into());
    }
    write_replacing(dest, |partial| {
        fs::write(partial, combined.as_bytes()).map_err(|err| format!("无法写入输出文件：{err}"))
    })?;
    Ok(vec![dest.to_string_lossy().to_string()])
}

fn compress_pdf(
    job: &Job,
    dest: &Path,
    on_progress: &impl Fn(f64),
    is_cancelled: &impl Fn() -> bool,
) -> Result<Vec<String>, String> {
    check_cancel(is_cancelled)?;
    let mut doc = load_pdf(&job.source_path)?;
    let count = doc.get_pages().len() as u32;
    if count == 0 {
        return Err("无法读取该文件".into());
    }
    let (start, end) = page_range(job, count);
    let delete: Vec<u32> = doc
        .get_pages()
        .keys()
        .copied()
        .filter(|num| *num < start || *num > end)
        .collect();
    if !delete.is_empty() {
        doc.delete_pages(&delete);
    }
    check_cancel(is_cancelled)?;
    doc.compress();
    on_progress(100.0);
    write_replacing(dest, |partial| {
        doc.save(partial).map_err(|err| format!("无法写入输出文件：{err}"))?;
        Ok(())
    })?;
    Ok(vec![dest.to_string_lossy().to_string()])
}

fn rasterize_pdf(
    job: &Job,
    dests: &[PathBuf],
    on_progress: &impl Fn(f64),
    is_cancelled: &impl Fn() -> bool,
) -> Result<Vec<String>, String> {
    check_cancel(is_cancelled)?;
    let pdfium = bind_pdfium()?;
    let document = pdfium
        .load_pdf_from_file(job.source_path.as_str(), None)
        .map_err(|_| "无法读取该文件".to_string())?;
    let count = document.pages().len() as u32;
    if count == 0 {
        return Err("无法读取该文件".into());
    }
    let (start, end) = page_range(job, count);
    let total = (end - start + 1) as usize;
    if dests.len() < total {
        return Err("没有输出路径".into());
    }
    let quality = quality_label(job);
    let max_edge = pdf_image_max_edge(&quality);
    let ext = dest_extension(&dests[0], "jpg");
    let jpeg_q = jpeg_quality(&quality, false);
    let render = pdfium_render::prelude::PdfRenderConfig::new()
        .set_target_width(max_edge)
        .set_maximum_height(max_edge);
    let mut written = Vec::new();
    for (offset, page_num) in (start..=end).enumerate() {
        check_cancel(is_cancelled)?;
        let page = document
            .pages()
            .get((page_num - 1) as u16)
            .map_err(|_| "无法读取该文件".to_string())?;
        let image = page
            .render_with_config(&render)
            .map_err(|_| "无法读取该文件".to_string())?
            .as_image();
        written.push(write_replacing(&dests[offset], |partial| {
            write_image(&image, partial, &ext, Some(jpeg_q))
        })?);
        on_progress((offset + 1) as f64 / total as f64 * 100.0);
    }
    Ok(written)
}

fn bind_pdfium() -> Result<pdfium_render::prelude::Pdfium, String> {
    let path = resolve_pdfium()?;
    pdfium_render::prelude::Pdfium::bind_to_library(&path)
        .map(pdfium_render::prelude::Pdfium::new)
        .map_err(|_| "找不到打包的 pdfium。请先运行 npm run fetch-pdfium".to_string())
}

fn pdfium_candidate_names() -> Vec<String> {
    let triple = current_target_triple();
    let mut names = vec![
        format!("pdfium-{triple}"),
        sidecar_filename("pdfium"),
    ];
    if cfg!(windows) {
        names.push(format!("pdfium-{triple}.dll"));
        names.push("pdfium.dll".into());
    } else if cfg!(target_os = "macos") {
        names.push(format!("pdfium-{triple}.dylib"));
        names.push("libpdfium.dylib".into());
    } else {
        names.push(format!("pdfium-{triple}.so"));
        names.push("libpdfium.so".into());
    }
    names
}

fn resolve_pdfium() -> Result<PathBuf, String> {
    let names = pdfium_candidate_names();
    let mut dirs = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            dirs.push(dir.to_path_buf());
            dirs.push(dir.join("binaries"));
            dirs.push(dir.join("resources"));
            dirs.push(dir.join("resources").join("binaries"));
        }
    }
    dirs.push(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("binaries"));
    for dir in dirs {
        for name in &names {
            let path = dir.join(name);
            if path.is_file() {
                return Ok(path);
            }
        }
    }
    Err("找不到打包的 pdfium。请先运行 npm run fetch-pdfium".into())
}

fn convert_office(source: &str, dest: &Path, is_cancelled: &impl Fn() -> bool) -> Result<(), String> {
    check_cancel(is_cancelled)?;
    let paragraphs = docx_paragraphs(source)?;
    if paragraphs
        .iter()
        .all(|p| p.trim().is_empty())
    {
        return Err(CANNOT_CONVERT.into());
    }
    check_cancel(is_cancelled)?;
    write_replacing(dest, |partial| write_office_pdf(&paragraphs, partial))?;
    Ok(())
}

fn docx_paragraphs(source: &str) -> Result<Vec<String>, String> {
    let file = File::open(source).map_err(|_| CANNOT_CONVERT.to_string())?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| CANNOT_CONVERT.to_string())?;
    let mut xml_file = archive
        .by_name("word/document.xml")
        .map_err(|_| CANNOT_CONVERT.to_string())?;
    let mut xml = String::new();
    xml_file
        .read_to_string(&mut xml)
        .map_err(|_| CANNOT_CONVERT.to_string())?;
    let paragraphs = parse_word_paragraphs(&xml);
    if paragraphs.is_empty() {
        Err(CANNOT_CONVERT.into())
    } else {
        Ok(paragraphs)
    }
}

fn parse_word_paragraphs(xml: &str) -> Vec<String> {
    xml.split("</w:p>")
        .filter(|chunk| chunk.contains("<w:p"))
        .map(collect_wt_text)
        .collect()
}

fn collect_wt_text(xml: &str) -> String {
    let mut out = String::new();
    let mut rest = xml;
    while let Some(start) = rest.find("<w:t") {
        let after = &rest[start + 4..];
        let Some(gt) = after.find('>') else { break };
        if after[..gt].ends_with('/') {
            rest = &after[gt + 1..];
            continue;
        }
        let content = &after[gt + 1..];
        let end = content
            .find("</w:t>")
            .or_else(|| content.find("</t>"));
        let Some(end) = end else { break };
        out.push_str(&unescape_xml(&content[..end]));
        rest = &content[end..];
    }
    out
}

fn unescape_xml(text: &str) -> String {
    text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}

fn wrap_text(text: &str, max_chars: usize) -> Vec<String> {
    if text.is_empty() {
        return vec![String::new()];
    }
    let mut lines = Vec::new();
    let mut current = String::new();
    for ch in text.chars() {
        if ch == '\n' {
            lines.push(std::mem::take(&mut current));
            continue;
        }
        current.push(ch);
        if current.chars().count() >= max_chars {
            lines.push(std::mem::take(&mut current));
        }
    }
    lines.push(current);
    lines
}

fn write_office_pdf(paragraphs: &[String], dest: &Path) -> Result<(), String> {
    let (doc, mut page, mut layer) = PrintPdfDocument::new("Document", Mm(210.0), Mm(297.0), "Layer 1");
    let font = doc
        .add_builtin_font(BuiltinFont::Helvetica)
        .map_err(|_| CANNOT_CONVERT.to_string())?;
    let margin = 15.0;
    let line_h = 5.0;
    let page_h = 297.0;
    let mut y = page_h - margin;
    for paragraph in paragraphs {
        for line in wrap_text(paragraph, 90) {
            if y < margin {
                let next = doc.add_page(Mm(210.0), Mm(297.0), "Layer 1");
                page = next.0;
                layer = next.1;
                y = page_h - margin;
            }
            let current = doc.get_page(page).get_layer(layer);
            current.use_text(line, 11.0, Mm(margin), Mm(y), &font);
            y -= line_h;
        }
        y -= line_h / 2.0;
    }
    let file = File::create(dest).map_err(|_| CANNOT_CONVERT.to_string())?;
    doc.save(&mut BufWriter::new(file))
        .map_err(|_| CANNOT_CONVERT.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::{Job, JobStatus};
    use std::fs;
    use std::io::Write;
    use std::path::{Path, PathBuf};

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "video-converter-doc-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn media(path: &Path, page_count: Option<u32>, page_start: Option<u32>, page_end: Option<u32>) -> MediaInfo {
        MediaInfo {
            path: path.to_string_lossy().to_string(),
            duration_secs: None,
            container: path
                .extension()
                .and_then(|s| s.to_str())
                .map(|s| s.to_ascii_lowercase()),
            video_codec: None,
            width: None,
            height: None,
            frame_rate: None,
            audio_codec: None,
            channels: None,
            importable: true,
            error: None,
            trim_start_secs: None,
            trim_end_secs: None,
            page_count,
            page_start,
            page_end,
        }
    }

    fn job(
        preset: &str,
        source: &Path,
        outputs: &[PathBuf],
        page_count: Option<u32>,
        page_start: Option<u32>,
        page_end: Option<u32>,
        container: Option<&str>,
    ) -> Job {
        let output_paths: Vec<String> = outputs
            .iter()
            .map(|p| p.to_string_lossy().to_string())
            .collect();
        Job {
            id: "job-doc".into(),
            source_path: source.to_string_lossy().to_string(),
            output_path: output_paths.first().cloned(),
            status: JobStatus::Queued,
            progress: 0.0,
            error: None,
            config: OutputConfig {
                preset: preset.into(),
                container: container.map(|s| s.into()),
                ..Default::default()
            },
            media: media(source, page_count, page_start, page_end),
            display_name: source
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("output")
                .into(),
            output_paths,
            created_at_epoch_ms: None,
            concat_source_paths: vec![],
        }
    }

    fn write_png_8x8(path: &Path) {
        let img = image::RgbImage::from_pixel(8, 8, image::Rgb([200, 40, 40]));
        img.save(path).unwrap();
    }

    fn write_pdf_pages(path: &Path, pages: u32) {
        use lopdf::{content::Content, dictionary, Document, Object, Stream};

        let mut doc = Document::with_version("1.5");
        let pages_id = doc.new_object_id();
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Helvetica",
        });
        let resources_id = doc.add_object(dictionary! {
            "Font" => dictionary! { "F1" => font_id },
        });
        let content_id = doc.add_object(Stream::new(
            dictionary! {},
            Content { operations: vec![] }.encode().unwrap(),
        ));
        let page_ids: Vec<Object> = (0..pages)
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
                "Count" => pages as i64,
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

    fn write_min_docx(path: &Path) {
        use zip::write::SimpleFileOptions;
        use zip::ZipWriter;

        let file = fs::File::create(path).unwrap();
        let mut zip = ZipWriter::new(file);
        zip.start_file(
            "word/document.xml",
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored),
        )
        .unwrap();
        zip.write_all(
            br#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>Hello</w:t></w:r></w:p>
  </w:body>
</w:document>"#,
        )
        .unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn converts_8x8_png_to_nonempty_jpg() {
        let dir = temp_dir();
        let source = dir.join("dot.png");
        let dest = dir.join("dot.jpg");
        write_png_8x8(&source);
        let work = job("image-jpg", &source, &[dest.clone()], None, None, None, None);
        let paths = run_job(&work, |_| {}, || false).unwrap();
        assert_eq!(paths.len(), 1);
        let bytes = fs::read(&dest).unwrap();
        assert!(!bytes.is_empty(), "jpg should be nonempty");
        assert!(dest.exists());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn splits_two_page_pdf_into_two_files() {
        let dir = temp_dir();
        let source = dir.join("scan.pdf");
        let out1 = dir.join("scan-001.pdf");
        let out2 = dir.join("scan-002.pdf");
        write_pdf_pages(&source, 2);
        let work = job(
            "pdf-split",
            &source,
            &[out1.clone(), out2.clone()],
            Some(2),
            Some(1),
            Some(2),
            None,
        );
        let paths = run_job(&work, |_| {}, || false).unwrap();
        assert_eq!(paths.len(), 2);
        assert!(out1.is_file() && fs::metadata(&out1).unwrap().len() > 0);
        assert!(out2.is_file() && fs::metadata(&out2).unwrap().len() > 0);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn converts_min_docx_zip_to_pdf_header() {
        let dir = temp_dir();
        let source = dir.join("note.docx");
        let dest = dir.join("note.pdf");
        write_min_docx(&source);
        let work = job("office-pdf", &source, &[dest.clone()], None, None, None, None);
        let paths = run_job(&work, |_| {}, || false).unwrap();
        assert_eq!(paths.len(), 1);
        let bytes = fs::read(&dest).unwrap();
        assert!(bytes.starts_with(b"%PDF"), "docx output should start with %PDF");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn rasterizes_one_page_pdf_to_nonempty_jpg() {
        let dir = temp_dir();
        let source = dir.join("page.pdf");
        let dest = dir.join("page.jpg");
        write_pdf_pages(&source, 1);
        let work = job(
            "pdf-image",
            &source,
            &[dest.clone()],
            Some(1),
            Some(1),
            Some(1),
            Some("jpg"),
        );
        let paths = run_job(&work, |_| {}, || false).unwrap();
        assert_eq!(paths.len(), 1);
        assert!(fs::metadata(&dest).unwrap().len() > 0);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn cancel_during_loop_returns_cancelled() {
        let dir = temp_dir();
        let source = dir.join("scan.pdf");
        let out1 = dir.join("scan-001.pdf");
        let out2 = dir.join("scan-002.pdf");
        write_pdf_pages(&source, 2);
        let work = job(
            "pdf-split",
            &source,
            &[out1, out2],
            Some(2),
            Some(1),
            Some(2),
            None,
        );
        let err = run_job(&work, |_| {}, || true).unwrap_err();
        assert_eq!(err, "cancelled");
        let _ = fs::remove_dir_all(dir);
    }
}
