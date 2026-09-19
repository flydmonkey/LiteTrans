use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

pub const LAN_SHARE_PREFERRED_PORT: u16 = 17890;
pub const LAN_SHARE_PORT_ATTEMPTS: u16 = 10;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LanShareSettings {
    pub enabled: bool,
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
    })
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
    use std::collections::HashSet;

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
    }
}
