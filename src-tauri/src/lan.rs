use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::lan_page::{render_lan_history_html, LanHistoryCopy};
use crate::queue::{Job, JobStatus};

pub const LAN_SHARE_PREFERRED_PORT: u16 = 17890;
pub const LAN_SHARE_PORT_ATTEMPTS: u16 = 10;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LanShareSettings {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub token: String,
}

pub fn normalize_lan_token(raw: &str) -> String {
    raw.trim().to_string()
}

pub fn lan_token_allows(stored: &str, query_k: Option<&str>) -> bool {
    if stored.is_empty() {
        return true;
    }
    query_k == Some(stored)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LanRoute {
    Home,
    Download {
        job_id: String,
        index: usize,
    },
    Media {
        job_id: String,
        index: usize,
    },
    Favicon,
    NotFound,
}

pub fn parse_lan_route(path: &str) -> LanRoute {
    let trimmed = path.split('?').next().unwrap_or(path);
    if trimmed == "/" || trimmed.is_empty() {
        return LanRoute::Home;
    }
    if trimmed == "/favicon.png" || trimmed == "/favicon.ico" {
        return LanRoute::Favicon;
    }
    let stripped = trimmed.trim_matches('/');
    let parts: Vec<&str> = if stripped.is_empty() {
        vec![]
    } else {
        stripped.split('/').collect()
    };
    if parts.len() < 2 || parts.len() > 3 {
        return LanRoute::NotFound;
    }
    let kind = parts[0];
    if kind != "d" && kind != "m" {
        return LanRoute::NotFound;
    }
    let job_id = parts[1];
    if job_id.is_empty() || job_id.contains("..") || job_id.contains('/') {
        return LanRoute::NotFound;
    }
    let index = if parts.len() == 2 {
        0usize
    } else {
        match parts[2].parse::<usize>() {
            Ok(n) => n,
            Err(_) => return LanRoute::NotFound,
        }
    };
    if kind == "d" {
        LanRoute::Download {
            job_id: job_id.to_string(),
            index,
        }
    } else {
        LanRoute::Media {
            job_id: job_id.to_string(),
            index,
        }
    }
}

pub fn lan_query_decode(raw: &str) -> String {
    let plus_as_space = raw.replace('+', " ");
    percent_decode(&plus_as_space)
}

fn percent_decode(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(byte) = u8::from_str_radix(
                std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or(""),
                16,
            ) {
                out.push(byte);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn parse_lan_query(raw: Option<&str>) -> HashMap<String, String> {
    let Some(raw) = raw.filter(|s| !s.is_empty()) else {
        return HashMap::new();
    };
    let mut result = HashMap::new();
    for part in raw.split('&') {
        if part.is_empty() {
            continue;
        }
        let (raw_key, raw_val) = match part.split_once('=') {
            Some((k, v)) => (k, v),
            None => (part, ""),
        };
        let key = lan_query_decode(raw_key);
        if key.is_empty() {
            continue;
        }
        result.insert(key, lan_query_decode(raw_val));
    }
    result
}

pub fn lan_query_encode(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.chars() {
        if ch.is_ascii_alphanumeric() || matches!(ch, '-' | '.' | '_' | '*') {
            out.push(ch);
        } else if ch == ' ' {
            out.push('+');
        } else {
            for byte in ch.to_string().as_bytes() {
                out.push('%');
                out.push_str(&format!("{byte:02X}"));
            }
        }
    }
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanHttpRequest {
    pub method: String,
    pub path: String,
    pub query: HashMap<String, String>,
    pub headers: HashMap<String, String>,
}

pub fn parse_http_request_line(line: &str) -> Option<LanHttpRequest> {
    let trimmed = line.trim();
    let first_space = trimmed.find(' ')?;
    if first_space == 0 {
        return None;
    }
    let method = trimmed[..first_space].to_string();
    let rest = trimmed[first_space + 1..].trim_start();
    if rest.is_empty() {
        return None;
    }
    let target_end = rest.find(' ');
    let target = if let Some(end) = target_end {
        &rest[..end]
    } else {
        rest
    };
    if target.is_empty() {
        return None;
    }
    let (path, query) = if let Some(query_start) = target.find('?') {
        let path = target[..query_start].to_string();
        let query = parse_lan_query(Some(&target[query_start + 1..]));
        (path, query)
    } else {
        (target.to_string(), HashMap::new())
    };
    Some(LanHttpRequest {
        method,
        path,
        query,
        headers: HashMap::new(),
    })
}

pub fn job_output_paths(job: &Job) -> Vec<String> {
    let paths: Vec<String> = job
        .output_paths
        .iter()
        .filter(|path| !path.trim().is_empty())
        .cloned()
        .collect();
    if !paths.is_empty() {
        return paths;
    }
    job.output_path
        .as_ref()
        .filter(|path| !path.trim().is_empty())
        .cloned()
        .into_iter()
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanDownloadTarget {
    pub path: String,
    pub download_name: String,
    pub content_type: String,
}

fn lan_download_name(path: &str) -> String {
    path.rsplit(['/', '\\'])
        .next()
        .filter(|name| !name.is_empty())
        .unwrap_or(path)
        .to_string()
}

pub fn resolve_lan_download(
    jobs: &[Job],
    job_id: &str,
    index: usize,
    exists: impl Fn(&str) -> bool,
) -> Option<LanDownloadTarget> {
    if job_id.contains("..") || job_id.contains('/') {
        return None;
    }
    let job = jobs.iter().find(|job| job.id == job_id)?;
    if job.status != JobStatus::Completed {
        return None;
    }
    let paths = job_output_paths(job);
    let path = paths.get(index)?;
    if !exists(path) {
        return None;
    }
    let download_name = lan_download_name(path);
    Some(LanDownloadTarget {
        path: path.clone(),
        download_name: download_name.clone(),
        content_type: lan_content_type(&download_name).to_string(),
    })
}

pub fn lan_content_type(file_name: &str) -> &'static str {
    let ext = file_name
        .rsplit_once('.')
        .map(|(_, ext)| ext)
        .unwrap_or("")
        .to_ascii_lowercase();
    match ext.as_str() {
        "mp4" => "video/mp4",
        "mov" => "video/quicktime",
        "mkv" => "video/x-matroska",
        "webm" => "video/webm",
        "avi" => "video/x-msvideo",
        "mp3" => "audio/mpeg",
        "m4a" => "audio/mp4",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "flac" => "audio/flac",
        "amr" => "audio/amr",
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "bmp" => "image/bmp",
        "pdf" => "application/pdf",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn lan_content_disposition(file_name: &str, inline: bool) -> String {
    let safe = file_name.replace(['\r', '\n', '"'], "_");
    let encoded = lan_query_encode(&safe).replace('+', "%20");
    let kind = if inline { "inline" } else { "attachment" };
    format!("{kind}; filename=\"{safe}\"; filename*=UTF-8''{encoded}")
}

const LAN_FAVICON_PNG: &[u8] = b"\x89PNG\r\n\x1a\n\x00\x00\x00\x00IHDR";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanHttpResponse {
    pub status: u16,
    pub content_type: String,
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
    pub file_path: Option<String>,
    pub send_body: bool,
}

fn lan_plain_text(status: u16, body: &str, send_body: bool) -> LanHttpResponse {
    LanHttpResponse {
        status,
        content_type: "text/plain; charset=utf-8".into(),
        body: body.as_bytes().to_vec(),
        headers: vec![],
        file_path: None,
        send_body,
    }
}

pub fn handle_lan_request(
    req: &LanHttpRequest,
    jobs: &[Job],
    token: &str,
    exists: impl Fn(&str) -> bool,
    copy: &LanHistoryCopy,
) -> LanHttpResponse {
    if req.method != "GET" && req.method != "HEAD" {
        return lan_plain_text(405, "Method Not Allowed", true);
    }
    let send_body = req.method != "HEAD";
    let route = parse_lan_route(&req.path);
    if matches!(route, LanRoute::Favicon) {
        return LanHttpResponse {
            status: 200,
            content_type: "image/png".into(),
            body: LAN_FAVICON_PNG.to_vec(),
            headers: vec![],
            file_path: None,
            send_body,
        };
    }
    if !lan_token_allows(token, req.query.get("k").map(String::as_str)) {
        return lan_plain_text(401, &copy.need_token, send_body);
    }
    match &route {
        LanRoute::Home => {
            let html = render_lan_history_html(jobs, token, copy, exists);
            LanHttpResponse {
                status: 200,
                content_type: "text/html; charset=utf-8".into(),
                body: html.into_bytes(),
                headers: vec![],
                file_path: None,
                send_body,
            }
        }
        LanRoute::Download { job_id, index } | LanRoute::Media { job_id, index } => {
            let inline = matches!(route, LanRoute::Media { .. });
            let Some(target) = resolve_lan_download(jobs, job_id, *index, exists) else {
                return lan_plain_text(404, "Not Found", send_body);
            };
            LanHttpResponse {
                status: 200,
                content_type: target.content_type.clone(),
                body: Vec::new(),
                headers: vec![
                    ("Content-Type".into(), target.content_type.clone()),
                    (
                        "Content-Disposition".into(),
                        lan_content_disposition(&target.download_name, inline),
                    ),
                    ("Accept-Ranges".into(), "bytes".into()),
                ],
                file_path: Some(target.path),
                send_body,
            }
        }
        LanRoute::NotFound | LanRoute::Favicon => lan_plain_text(404, "Not Found", send_body),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanIface {
    pub name: String,
    pub host_address: String,
    pub loopback: bool,
}

fn is_dotted_ipv4(host: &str) -> bool {
    let host = host.split('%').next().unwrap_or(host);
    let parts: Vec<&str> = host.split('.').collect();
    if parts.len() != 4 {
        return false;
    }
    parts.iter().all(|part| {
        part.parse::<u8>().is_ok() && !part.is_empty() && part.len() <= 3 && part.chars().all(|c| c.is_ascii_digit())
    })
}

fn is_skipped_desktop_iface(name: &str) -> bool {
    let n = name.to_lowercase();
    n.starts_with("lo")
        || n.starts_with("utun")
        || n.starts_with("awdl")
        || n.starts_with("llw")
}

fn is_rfc1918(host: &str) -> bool {
    let host = host.split('%').next().unwrap_or(host);
    if host.starts_with("10.") {
        return true;
    }
    if host.starts_with("192.168.") {
        return true;
    }
    if let Some(rest) = host.strip_prefix("172.") {
        if let Some(second) = rest.split('.').next() {
            if let Ok(octet) = second.parse::<u8>() {
                return (16..=31).contains(&octet);
            }
        }
    }
    false
}

pub fn collect_ifaces() -> Vec<LanIface> {
    #[cfg(unix)]
    {
        collect_ifaces_unix()
    }
    #[cfg(not(unix))]
    {
        Vec::new()
    }
}

#[cfg(unix)]
fn collect_ifaces_unix() -> Vec<LanIface> {
    use std::ffi::CStr;
    use std::net::Ipv4Addr;

    let mut out = Vec::new();
    unsafe {
        let mut ifap: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut ifap) != 0 {
            return out;
        }
        let mut cur = ifap;
        while !cur.is_null() {
            let iface = &*cur;
            if !iface.ifa_addr.is_null()
                && i32::from((*iface.ifa_addr).sa_family) == libc::AF_INET
            {
                let name = if iface.ifa_name.is_null() {
                    String::new()
                } else {
                    CStr::from_ptr(iface.ifa_name)
                        .to_string_lossy()
                        .into_owned()
                };
                let addr = &*(iface.ifa_addr as *const libc::sockaddr_in);
                let ip = Ipv4Addr::from(u32::from_be(addr.sin_addr.s_addr));
                if !ip.is_unspecified() {
                    let loopback = (iface.ifa_flags & libc::IFF_LOOPBACK as libc::c_uint) != 0
                        || ip.is_loopback();
                    out.push(LanIface {
                        name,
                        host_address: ip.to_string(),
                        loopback,
                    });
                }
            }
            cur = iface.ifa_next;
        }
        libc::freeifaddrs(ifap);
    }
    out
}

pub fn pick_lan_ipv4(ifaces: &[LanIface]) -> Option<String> {
    let usable: Vec<&LanIface> = ifaces
        .iter()
        .filter(|iface| {
            !iface.loopback
                && is_dotted_ipv4(&iface.host_address)
                && !is_skipped_desktop_iface(&iface.name)
        })
        .collect();
    if let Some(iface) = usable.iter().find(|i| is_rfc1918(&i.host_address)) {
        return Some(iface.host_address.clone());
    }
    usable.first().map(|i| i.host_address.clone())
}

pub fn choose_lan_port(
    preferred: u16,
    attempts: u16,
    occupied: &HashSet<u16>,
) -> Option<u16> {
    for offset in 0..attempts {
        let port = preferred.saturating_add(offset);
        if !occupied.contains(&port) {
            return Some(port);
        }
    }
    None
}

pub fn lan_public_url(ip: &str, port: u16, token: &str) -> String {
    let base = format!("http://{ip}:{port}/");
    if token.is_empty() {
        return base;
    }
    format!("{}?k={}", base, lan_query_encode(token))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lan_page::LanHistoryCopy;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::{Job, JobStatus};
    use std::collections::HashSet;

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

    fn completed_job(id: &str, output: &str) -> Job {
        Job {
            id: id.into(),
            source_path: "/tmp/src.mp4".into(),
            output_path: Some(output.into()),
            status: JobStatus::Completed,
            progress: 100.0,
            error: None,
            config: OutputConfig::default(),
            media: sample_media(),
            display_name: output.rsplit('/').next().unwrap_or(output).into(),
            output_paths: vec![output.into()],
            created_at_epoch_ms: Some(1),
            concat_source_paths: vec![],
        }
    }

    fn get(path: &str) -> LanHttpRequest {
        LanHttpRequest {
            method: "GET".into(),
            path: path.into(),
            query: HashMap::new(),
            headers: HashMap::new(),
        }
    }

    fn head(path: &str) -> LanHttpRequest {
        LanHttpRequest {
            method: "HEAD".into(),
            path: path.into(),
            query: HashMap::new(),
            headers: HashMap::new(),
        }
    }

    #[test]
    fn token_and_routes() {
        assert_eq!(normalize_lan_token("  ab  "), "ab");
        assert!(lan_token_allows("", None));
        assert!(!lan_token_allows("secret", None));
        assert!(lan_token_allows("secret", Some("secret")));
        assert!(matches!(parse_lan_route("/"), LanRoute::Home));
        assert!(matches!(
            parse_lan_route("/d/job1"),
            LanRoute::Download { index: 0, .. }
        ));
        assert!(matches!(
            parse_lan_route("/m/job1/2"),
            LanRoute::Media { index: 2, .. }
        ));
        assert!(matches!(parse_lan_route("/favicon.png"), LanRoute::Favicon));
        assert!(matches!(parse_lan_route("/d/../x"), LanRoute::NotFound));
    }

    #[test]
    fn pick_ipv4_allows_ethernet_skips_loopback() {
        assert_eq!(
            pick_lan_ipv4(&[LanIface {
                name: "lo0".into(),
                host_address: "127.0.0.1".into(),
                loopback: true,
            }]),
            None
        );
        assert_eq!(
            pick_lan_ipv4(&[
                LanIface {
                    name: "utun0".into(),
                    host_address: "10.8.0.2".into(),
                    loopback: false,
                },
                LanIface {
                    name: "en0".into(),
                    host_address: "192.168.1.20".into(),
                    loopback: false,
                },
            ]),
            Some("192.168.1.20".into())
        );
        assert_eq!(
            choose_lan_port(17890, 10, &HashSet::from([17890, 17891])),
            Some(17892)
        );
        assert_eq!(
            lan_public_url("10.0.0.8", 17890, "a b"),
            "http://10.0.0.8:17890/?k=a+b"
        );
        if let Some(ip) = pick_lan_ipv4(&collect_ifaces()) {
            assert_ne!(ip, "127.0.0.1");
            assert_ne!(ip, "0.0.0.0");
            assert!(!ip.starts_with("127."));
        }
    }

    #[test]
    fn download_and_auth() {
        let job = completed_job("j1", "/tmp/a.mp4");
        let exists = |p: &str| p == "/tmp/a.mp4";
        let copy = LanHistoryCopy::english();
        let mut req = get("/");
        req.query.insert("k".into(), "nope".into());
        assert_eq!(handle_lan_request(&req, &[job.clone()], "secret", exists, &copy).status, 401);
        let ok = handle_lan_request(&get("/d/j1"), &[job.clone()], "", exists, &copy);
        assert_eq!(ok.status, 200);
        assert_eq!(ok.file_path.as_deref(), Some("/tmp/a.mp4"));
        let head = handle_lan_request(&head("/d/j1"), &[job], "", exists, &copy);
        assert!(!head.send_body);
    }

    #[test]
    fn lan_content_type_matches_android() {
        assert_eq!(lan_content_type("a.mp4"), "video/mp4");
        assert_eq!(lan_content_type("a.MP4"), "video/mp4");
        assert_eq!(lan_content_type("a.mov"), "video/quicktime");
        assert_eq!(lan_content_type("a.mkv"), "video/x-matroska");
        assert_eq!(lan_content_type("a.webm"), "video/webm");
        assert_eq!(lan_content_type("a.avi"), "video/x-msvideo");
        assert_eq!(lan_content_type("a.mp3"), "audio/mpeg");
        assert_eq!(lan_content_type("a.m4a"), "audio/mp4");
        assert_eq!(lan_content_type("a.wav"), "audio/wav");
        assert_eq!(lan_content_type("a.ogg"), "audio/ogg");
        assert_eq!(lan_content_type("a.flac"), "audio/flac");
        assert_eq!(lan_content_type("a.amr"), "audio/amr");
        assert_eq!(lan_content_type("a.jpg"), "image/jpeg");
        assert_eq!(lan_content_type("a.jpeg"), "image/jpeg");
        assert_eq!(lan_content_type("a.png"), "image/png");
        assert_eq!(lan_content_type("a.webp"), "image/webp");
        assert_eq!(lan_content_type("a.gif"), "image/gif");
        assert_eq!(lan_content_type("a.bmp"), "image/bmp");
        assert_eq!(lan_content_type("a.pdf"), "application/pdf");
        assert_eq!(lan_content_type("a.txt"), "text/plain; charset=utf-8");
        assert_eq!(lan_content_type("a.bin"), "application/octet-stream");
    }

    #[test]
    fn handle_rejects_method_missing_and_serves_home() {
        let job = completed_job("j1", "/tmp/a.mp4");
        let exists = |p: &str| p == "/tmp/a.mp4";
        let copy = LanHistoryCopy::english();
        let post = LanHttpRequest {
            method: "POST".into(),
            path: "/".into(),
            query: HashMap::new(),
            headers: HashMap::new(),
        };
        assert_eq!(
            handle_lan_request(&post, &[job.clone()], "", exists, &copy).status,
            405
        );
        let mut denied_req = get("/");
        denied_req.query.insert("k".into(), "nope".into());
        let denied = handle_lan_request(&denied_req, &[job.clone()], "secret", exists, &copy);
        assert_eq!(denied.status, 401);
        assert_eq!(denied.content_type, "text/plain; charset=utf-8");
        assert_eq!(String::from_utf8_lossy(&denied.body), copy.need_token);
        assert_eq!(
            handle_lan_request(&get("/d/missing"), &[job.clone()], "", exists, &copy).status,
            404
        );
        assert_eq!(
            handle_lan_request(&get("/m/j1/9"), &[job.clone()], "", exists, &copy).status,
            404
        );
        let home = handle_lan_request(&get("/"), &[job.clone()], "", exists, &copy);
        assert_eq!(home.status, 200);
        let html = String::from_utf8(home.body.clone()).unwrap();
        assert!(html.contains("<title>LiteTrans</title>"));
        let fav = handle_lan_request(&get("/favicon.png"), &[job], "secret", exists, &copy);
        assert_eq!(fav.status, 200);
        assert!(fav.body.len() > 8);
        assert_eq!(&fav.body[..4], b"\x89PNG");
    }

    #[test]
    fn job_output_paths_falls_back_to_output_path() {
        let mut job = completed_job("j1", "/tmp/a.mp4");
        job.output_paths.clear();
        assert_eq!(job_output_paths(&job), vec!["/tmp/a.mp4".to_string()]);
        let exists = |p: &str| p == "/tmp/a.mp4";
        assert_eq!(
            resolve_lan_download(&[job.clone()], "j1", 0, exists).map(|t| t.path),
            Some("/tmp/a.mp4".into())
        );
        job.status = JobStatus::Running;
        assert!(resolve_lan_download(&[job], "j1", 0, exists).is_none());
    }
}
