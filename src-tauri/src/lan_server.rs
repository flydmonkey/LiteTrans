use std::collections::{HashMap, HashSet};
use std::fs::{File, OpenOptions};
use std::io::{self, BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::Path;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::lan::{
    choose_lan_port, handle_lan_request, lan_public_url, parse_http_request_line, pick_lan_ipv4,
    LanIface, LanShareSettings, LAN_SHARE_PORT_ATTEMPTS, LAN_SHARE_PREFERRED_PORT,
};
use crate::lan_page::LanHistoryCopy;
use crate::queue::Job;

#[derive(Debug, Default)]
pub struct LanRuntime {
    pub settings: LanShareSettings,
    pub bound: Option<(String, u16)>,
    pub stop: Option<mpsc::Sender<()>>,
    done: Option<mpsc::Receiver<()>>,
    error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LanStatus {
    pub enabled: bool,
    pub token: String,
    pub url: Option<String>,
    pub error: Option<String>,
}

pub fn parse_byte_range(header: &str, total: u64) -> Result<(u64, u64), ()> {
    let raw = header.trim();
    if raw.is_empty() || total == 0 || raw.contains(',') {
        return Err(());
    }
    let lower = raw.to_ascii_lowercase();
    if !lower.starts_with("bytes=") {
        return Err(());
    }
    let spec = &raw["bytes=".len()..];
    let (start_text, end_text) = spec.split_once('-').ok_or(())?;
    if start_text.is_empty() {
        return Err(());
    }
    let start: u64 = start_text.parse().map_err(|_| ())?;
    if start >= total {
        return Err(());
    }
    let end = if end_text.is_empty() {
        total - 1
    } else {
        let parsed: u64 = end_text.parse().map_err(|_| ())?;
        if parsed < start {
            return Err(());
        }
        parsed.min(total - 1)
    };
    Ok((start, end))
}

pub fn status_from_runtime(runtime: &LanRuntime) -> LanStatus {
    let url = runtime.bound.as_ref().and_then(|(ip, port)| {
        if is_user_facing_lan_ip(ip) {
            Some(lan_public_url(ip, *port, &runtime.settings.token))
        } else {
            None
        }
    });
    LanStatus {
        enabled: runtime.settings.enabled,
        token: runtime.settings.token.clone(),
        url,
        error: runtime.error.clone(),
    }
}

fn is_user_facing_lan_ip(ip: &str) -> bool {
    ip != "0.0.0.0" && !ip.starts_with("127.")
}

pub fn stop_lan_runtime(runtime: &mut LanRuntime) {
    if let Some(tx) = runtime.stop.take() {
        let _ = tx.send(());
    }
    if let Some(done) = runtime.done.take() {
        let _ = done.recv_timeout(Duration::from_secs(2));
    }
    runtime.bound = None;
}

pub fn sync_lan_runtime(
    runtime: &mut LanRuntime,
    ifaces: &[LanIface],
    mut bind: impl FnMut(&str, u16) -> io::Result<TcpListener>,
    serve: impl FnOnce(TcpListener, mpsc::Receiver<()>) + Send + 'static,
) -> Result<LanStatus, String> {
    stop_lan_runtime(runtime);
    runtime.error = None;

    if !runtime.settings.enabled {
        return Ok(status_from_runtime(runtime));
    }

    let Some(ip) = pick_lan_ipv4(ifaces).filter(|ip| is_user_facing_lan_ip(ip)) else {
        runtime.error = Some("lan_need_address".into());
        return Err("lan_need_address".into());
    };

    let mut occupied = HashSet::new();
    let mut listener = None;
    let mut bound_port = None;
    for _ in 0..LAN_SHARE_PORT_ATTEMPTS {
        let Some(port) = choose_lan_port(LAN_SHARE_PREFERRED_PORT, LAN_SHARE_PORT_ATTEMPTS, &occupied)
        else {
            break;
        };
        match bind(&ip, port) {
            Ok(bound) => {
                listener = Some(bound);
                bound_port = Some(port);
                break;
            }
            Err(_) => {
                occupied.insert(port);
            }
        }
    }

    let (Some(listener), Some(port)) = (listener, bound_port) else {
        runtime.error = Some("lan_ports_busy".into());
        return Err("lan_ports_busy".into());
    };

    let (stop_tx, stop_rx) = mpsc::channel();
    let (done_tx, done_rx) = mpsc::channel();
    thread::spawn(move || {
        serve(listener, stop_rx);
        let _ = done_tx.send(());
    });
    runtime.stop = Some(stop_tx);
    runtime.done = Some(done_rx);
    runtime.bound = Some((ip, port));
    runtime.error = None;
    Ok(status_from_runtime(runtime))
}

pub fn serve_lan_listener(
    listener: TcpListener,
    stop: mpsc::Receiver<()>,
    token: String,
    load_jobs: impl Fn() -> Vec<Job>,
    load_copy: impl Fn() -> LanHistoryCopy,
) {
    let _ = listener.set_nonblocking(true);
    loop {
        match stop.try_recv() {
            Ok(()) | Err(mpsc::TryRecvError::Disconnected) => break,
            Err(mpsc::TryRecvError::Empty) => {}
        }
        match listener.accept() {
            Ok((mut stream, _)) => {
                let _ = stream.set_nonblocking(false);
                let jobs = load_jobs();
                let copy = load_copy();
                let _ = handle_lan_stream(&mut stream, &jobs, &token, &copy);
            }
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(50));
            }
            Err(_) => break,
        }
    }
}

pub fn handle_lan_stream(
    stream: &mut TcpStream,
    jobs: &[Job],
    token: &str,
    copy: &LanHistoryCopy,
) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(Duration::from_secs(15)))?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line)?;
    if request_line.is_empty() {
        return Ok(());
    }
    let mut header_lines = Vec::new();
    loop {
        let mut line = String::new();
        reader.read_line(&mut line)?;
        if line.is_empty() || line == "\r\n" || line == "\n" {
            break;
        }
        header_lines.push(line);
    }
    let Some(mut req) = parse_http_request_line(&request_line) else {
        return Ok(());
    };
    req.headers = parse_header_lines(&header_lines);
    let response = handle_lan_request(&req, jobs, token, lan_file_is_regular, copy);
    write_lan_response(
        stream,
        &response,
        req.headers.get("range").map(String::as_str),
    )?;
    let _ = stream.shutdown(Shutdown::Write);
    Ok(())
}

fn parse_header_lines(lines: &[String]) -> HashMap<String, String> {
    let mut headers = HashMap::new();
    for line in lines {
        let line = line.trim_end_matches(['\r', '\n']);
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let name = name.trim();
        if name.is_empty() {
            continue;
        }
        headers.insert(name.to_ascii_lowercase(), value.trim().to_string());
    }
    headers
}

fn lan_file_is_regular(path: &str) -> bool {
    std::fs::symlink_metadata(path)
        .map(|meta| meta.file_type().is_file())
        .unwrap_or(false)
}

fn open_lan_file(path: &Path) -> io::Result<File> {
    if !std::fs::symlink_metadata(path)
        .map(|meta| meta.file_type().is_file())
        .unwrap_or(false)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "not a regular file",
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        return OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path);
    }
    #[cfg(not(unix))]
    {
        File::open(path)
    }
}

fn lan_http_reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        206 => "Partial Content",
        401 => "Unauthorized",
        404 => "Not Found",
        405 => "Method Not Allowed",
        416 => "Range Not Satisfiable",
        _ => "OK",
    }
}

fn write_headers(
    stream: &mut TcpStream,
    status: u16,
    content_type: &str,
    extra: &[(String, String)],
    content_length: u64,
) -> io::Result<()> {
    write!(
        stream,
        "HTTP/1.1 {status} {}\r\n",
        lan_http_reason(status)
    )?;
    write!(stream, "Content-Type: {content_type}\r\n")?;
    for (name, value) in extra {
        if name.eq_ignore_ascii_case("content-type")
            || name.eq_ignore_ascii_case("content-length")
        {
            continue;
        }
        write!(stream, "{name}: {value}\r\n")?;
    }
    write!(stream, "Content-Length: {content_length}\r\n")?;
    write!(stream, "Connection: close\r\n\r\n")?;
    Ok(())
}

fn write_lan_response(
    stream: &mut TcpStream,
    response: &crate::lan::LanHttpResponse,
    range_header: Option<&str>,
) -> io::Result<()> {
    if let Some(path) = response.file_path.as_deref() {
        return write_file_response(stream, response, path, range_header);
    }
    let length = response.body.len() as u64;
    write_headers(
        stream,
        response.status,
        &response.content_type,
        &response.headers,
        length,
    )?;
    if response.send_body {
        stream.write_all(&response.body)?;
    }
    stream.flush()
}

fn write_file_response(
    stream: &mut TcpStream,
    response: &crate::lan::LanHttpResponse,
    path: &str,
    range_header: Option<&str>,
) -> io::Result<()> {
    let file_path = Path::new(path);
    let meta = match std::fs::symlink_metadata(file_path) {
        Ok(meta) if meta.file_type().is_file() => meta,
        _ => return write_plain(stream, 404, "Not Found", response.send_body),
    };
    let total = meta.len();
    let (status, start, length, extra, send_file) = match range_header.filter(|h| !h.is_empty()) {
        None => (response.status, 0, total, response.headers.clone(), true),
        Some(header) => match parse_byte_range(header, total) {
            Ok((start, end)) => {
                let mut headers = response.headers.clone();
                headers.push((
                    "Content-Range".into(),
                    format!("bytes {start}-{end}/{total}"),
                ));
                (206, start, end - start + 1, headers, true)
            }
            Err(()) => {
                let mut headers = response.headers.clone();
                headers.push(("Content-Range".into(), format!("bytes */{total}")));
                (416, 0, 0, headers, false)
            }
        },
    };
    write_headers(stream, status, &response.content_type, &extra, length)?;
    if response.send_body && send_file {
        let mut file = match open_lan_file(file_path) {
            Ok(file) => file,
            Err(_) => return stream.flush(),
        };
        file.seek(SeekFrom::Start(start))?;
        let mut remaining = length;
        let mut buf = [0u8; 8192];
        while remaining > 0 {
            let want = remaining.min(buf.len() as u64) as usize;
            let read = file.read(&mut buf[..want])?;
            if read == 0 {
                break;
            }
            stream.write_all(&buf[..read])?;
            remaining -= read as u64;
        }
    }
    stream.flush()
}

fn write_plain(stream: &mut TcpStream, status: u16, body: &str, send_body: bool) -> io::Result<()> {
    write_headers(stream, status, "text/plain; charset=utf-8", &[], body.len() as u64)?;
    if send_body {
        stream.write_all(body.as_bytes())?;
    }
    stream.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lan::LanIface;
    use crate::presets::OutputConfig;
    use crate::probe::MediaInfo;
    use crate::queue::{Job, JobStatus};
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    #[test]
    fn parse_byte_range_accepts_single_span() {
        assert_eq!(parse_byte_range("bytes=0-1", 10), Ok((0, 1)));
    }

    #[test]
    fn parse_byte_range_rejects_out_of_bounds() {
        assert_eq!(parse_byte_range("bytes=10-11", 10), Err(()));
    }

    #[test]
    fn sync_enabled_without_iface_does_not_bind() {
        let mut runtime = LanRuntime {
            settings: LanShareSettings {
                enabled: true,
                token: String::new(),
            },
            ..Default::default()
        };
        let mut bind_called = false;
        let err = sync_lan_runtime(
            &mut runtime,
            &[],
            |ip, port| {
                bind_called = true;
                TcpListener::bind((ip, port))
            },
            |_, _| {},
        )
        .unwrap_err();
        assert!(!bind_called, "enabled+no iface must not bind a listener");
        assert!(runtime.bound.is_none());
        assert_eq!(err, "lan_need_address");
    }

    #[test]
    fn sync_enabled_without_usable_address_rejects_loopback_iface() {
        let mut runtime = LanRuntime {
            settings: LanShareSettings {
                enabled: true,
                token: String::new(),
            },
            ..Default::default()
        };
        let mut bind_called = false;
        let err = sync_lan_runtime(
            &mut runtime,
            &[LanIface {
                name: "lo0".into(),
                host_address: "127.0.0.1".into(),
                loopback: true,
            }],
            |ip, port| {
                bind_called = true;
                TcpListener::bind((ip, port))
            },
            |_, _| {},
        )
        .unwrap_err();
        assert!(!bind_called);
        assert!(runtime.bound.is_none());
        assert_eq!(err, "lan_need_address");
    }

    #[test]
    fn sync_binds_chosen_lan_ip_not_wildcard() {
        let mut runtime = LanRuntime {
            settings: LanShareSettings {
                enabled: true,
                token: String::new(),
            },
            ..Default::default()
        };
        let mut seen = Vec::new();
        let err = sync_lan_runtime(
            &mut runtime,
            &[LanIface {
                name: "en0".into(),
                host_address: "192.168.1.20".into(),
                loopback: false,
            }],
            |ip, _port| {
                seen.push(ip.to_string());
                Err(io::Error::new(io::ErrorKind::AddrInUse, "busy"))
            },
            |_, _| {},
        )
        .unwrap_err();
        assert_eq!(err, "lan_ports_busy");
        assert!(runtime.bound.is_none());
        assert_eq!(seen.len(), 10);
        assert!(seen.iter().all(|ip| ip == "192.168.1.20"));
        assert!(!seen.iter().any(|ip| ip == "0.0.0.0" || ip == "127.0.0.1"));
    }

    #[test]
    fn sync_disabled_stops_and_clears_bound() {
        let (tx, rx) = mpsc::channel();
        let mut runtime = LanRuntime {
            settings: LanShareSettings {
                enabled: false,
                token: "secret".into(),
            },
            bound: Some(("192.168.1.20".into(), 17890)),
            stop: Some(tx),
            ..Default::default()
        };
        let status = sync_lan_runtime(
            &mut runtime,
            &[LanIface {
                name: "en0".into(),
                host_address: "192.168.1.20".into(),
                loopback: false,
            }],
            |_, _| panic!("disabled must not bind"),
            |_, _| panic!("disabled must not serve"),
        )
        .unwrap();
        assert!(!status.enabled);
        assert!(runtime.bound.is_none());
        assert!(status.url.is_none());
        assert_eq!(rx.try_recv(), Ok(()));
    }

    #[test]
    fn loopback_file_range_is_206() {
        let dir = std::env::temp_dir().join(format!(
            "lan-server-range-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("clip.bin");
        std::fs::write(&file, b"0123456789").unwrap();
        let job = completed_job("j1", file.to_str().unwrap());

        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let addr = listener.local_addr().unwrap();
        let jobs = vec![job];
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            handle_lan_stream(&mut stream, &jobs, "", &LanHistoryCopy::english()).unwrap();
        });

        let mut client = TcpStream::connect_timeout(&addr, Duration::from_secs(2)).unwrap();
        client
            .write_all(b"GET /d/j1 HTTP/1.1\r\nRange: bytes=0-1\r\n\r\n")
            .unwrap();
        let mut raw = Vec::new();
        client.read_to_end(&mut raw).unwrap();
        let text = String::from_utf8_lossy(&raw);
        assert!(text.starts_with("HTTP/1.1 206"), "{text}");
        assert!(text.contains("Content-Range: bytes 0-1/10"), "{text}");
        assert!(raw.ends_with(b"01"), "{text}");
        server.join().unwrap();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn loopback_file_bad_range_is_416() {
        let dir = std::env::temp_dir().join(format!(
            "lan-server-range-bad-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("clip.bin");
        std::fs::write(&file, b"0123456789").unwrap();
        let job = completed_job("j1", file.to_str().unwrap());

        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let addr = listener.local_addr().unwrap();
        let jobs = vec![job];
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            handle_lan_stream(&mut stream, &jobs, "", &LanHistoryCopy::english()).unwrap();
        });

        let mut client = TcpStream::connect_timeout(&addr, Duration::from_secs(2)).unwrap();
        client
            .write_all(b"GET /d/j1 HTTP/1.1\r\nRange: bytes=10-11\r\n\r\n")
            .unwrap();
        let mut raw = Vec::new();
        client.read_to_end(&mut raw).unwrap();
        let text = String::from_utf8_lossy(&raw);
        assert!(text.starts_with("HTTP/1.1 416"), "{text}");
        assert!(text.contains("Content-Range: bytes */10"), "{text}");
        server.join().unwrap();
        let _ = std::fs::remove_dir_all(dir);
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
            media: MediaInfo {
                path: "/tmp/src.mp4".into(),
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
            display_name: output.rsplit('/').next().unwrap_or(output).into(),
            output_paths: vec![output.into()],
            created_at_epoch_ms: Some(1),
            concat_source_paths: vec![],
        }
    }

    #[test]
    fn pick_lan_ipv4_still_rejects_loopback() {
        assert_eq!(
            crate::lan::pick_lan_ipv4(&[LanIface {
                name: "lo0".into(),
                host_address: "127.0.0.1".into(),
                loopback: true,
            }]),
            None
        );
    }
}
