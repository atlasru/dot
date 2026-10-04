use base64::{engine::general_purpose, Engine as _};
use reqwest::blocking::Client;
use crate::subscription_identity::SubscriptionIdentity;
use std::{time::Duration, io::Read};
pub const MAX_SUBSCRIPTION_BYTES: usize = 4 * 1024 * 1024;

use crate::{model::VlessNode, vless::parse_vless};

pub struct SubscriptionClient {
    http: Client,
    os_version: String,
}

impl SubscriptionClient {
    pub fn new() -> Result<Self, String> {
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .user_agent(concat!("dot-desktop/", env!("CARGO_PKG_VERSION")))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|_| "failed to initialize subscription HTTP client".to_string())?;
        Ok(Self { http, os_version: crate::subscription_identity::os_version() })
    }

    pub(crate) fn request(&self, url: &str, hwid: &SubscriptionIdentity) -> Result<reqwest::blocking::RequestBuilder, String> {
        hwid.validate()?;
        Ok(self.http.get(url)
            .header("Accept", "*/*")
            .header("x-hwid", hwid.as_str())
            .header("x-device-os", "Windows")
            .header("x-ver-os", &self.os_version)
            .header("x-device-model", "dot Windows"))
    }

    pub fn fetch(&self, url: &str, hwid: &SubscriptionIdentity) -> Result<Vec<VlessNode>, String> {
        hwid.validate()?;
        let origin = url::Url::parse(url).map_err(|_| "invalid subscription URL".to_string())?;
        let mut target = origin.clone();
        let mut hops = 0;
        let response = loop {
            let request = if target.origin() == origin.origin() {
                self.request(target.as_str(), hwid)?
            } else {
                self.http.get(target.clone()).header("Accept", "*/*")
            };
            let response = request.send().map_err(format_request_error)?;
            if matches!(response.status().as_u16(), 301 | 302 | 303 | 307 | 308) {
                let Some(location) = response.headers().get(reqwest::header::LOCATION) else { break response; };
                if hops >= 10 { return Err("the subscription server returned too many redirects".into()); }
                target = target.join(location.to_str().map_err(|_| "invalid subscription redirect".to_string())?)
                    .map_err(|_| "invalid subscription redirect".to_string())?;
                if !matches!(target.scheme(), "http" | "https") { return Err("unsupported subscription redirect".into()); }
                hops += 1;
                continue;
            }
            break response;
        };

        let status = response.status();
        if !status.is_success() {
            return Err(match status.as_u16() {
                401 | 403 => "the subscription server rejected the request".into(),
                404 => "the subscription was not found".into(),
                code @ 500..=599 => format!("the subscription server returned an error (HTTP {code})"),
                code => format!("the subscription server returned HTTP {code}"),
            });
        }

        let mut bytes = Vec::new();
        response.take((MAX_SUBSCRIPTION_BYTES + 1) as u64).read_to_end(&mut bytes)
            .map_err(|_| "failed to read the subscription response".to_string())?;
        if bytes.len() > MAX_SUBSCRIPTION_BYTES { return Err("subscription exceeds 4 MB".into()); }
        let body = String::from_utf8(bytes).map_err(|_| "subscription is not UTF-8 text".to_string())?;
        decode_subscription(&body)
    }
}

fn format_request_error(error: reqwest::Error) -> String {
    if error.is_timeout() {
        "the subscription server did not respond in time".into()
    } else if error.is_connect() {
        "could not connect to the subscription server".into()
    } else if error.is_redirect() {
        "the subscription server returned an invalid redirect".into()
    } else {
        "subscription request failed".into()
    }
}

pub fn decode_subscription(body: &str) -> Result<Vec<VlessNode>, String> {
    if body.len() > MAX_SUBSCRIPTION_BYTES { return Err("subscription exceeds 4 MB".into()); }
    let body = body.trim().trim_start_matches('\u{feff}').trim();
    if body.is_empty() {
        return Err("subscription response is empty".into());
    }

    let plaintext = if body.to_ascii_lowercase().contains("vless://") {
        body.to_string()
    } else {
        decode_base64(body).ok_or_else(|| "unsupported subscription format".to_string())?
    };

    let mut nodes = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut errors = Vec::new();
    for token in plaintext
        .split(|c: char| c.is_whitespace())
        .map(str::trim)
        .filter(|s| s.to_ascii_lowercase().starts_with("vless://"))
    {
        match parse_vless(token) {
            Ok(node) => { if seen.insert(node.id.clone()) { nodes.push(node); } },
            Err(error) => errors.push(error),
        }
    }

    if nodes.is_empty() {
        if let Some(first) = errors.first() {
            Err(format!("subscription contains no usable VLESS nodes: {first}"))
        } else {
            Err("subscription contains no VLESS nodes".into())
        }
    } else {
        Ok(nodes)
    }
}

fn decode_base64(input: &str) -> Option<String> {
    let compact: String = input.chars().filter(|c| !c.is_whitespace()).collect();
    for engine in [
        &general_purpose::STANDARD,
        &general_purpose::STANDARD_NO_PAD,
        &general_purpose::URL_SAFE,
        &general_purpose::URL_SAFE_NO_PAD,
    ] {
        if let Ok(bytes) = engine.decode(compact.as_bytes()) {
            if let Ok(text) = String::from_utf8(bytes) {
                if text.to_ascii_lowercase().contains("vless://") {
                    return Some(text);
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::engine::general_purpose::STANDARD;

    pub(super) const LINK: &str = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=ws&path=%2Fdot#node";

    #[test]
    fn accepts_plaintext() {
        assert_eq!(decode_subscription(LINK).unwrap().len(), 1);
    }

    #[test]
    fn accepts_base64() {
        let encoded = STANDARD.encode(LINK.as_bytes());
        assert_eq!(decode_subscription(&encoded).unwrap().len(), 1);
    }

    #[test]
    fn parse_errors_do_not_echo_vless_credentials() {
        let secret = "vless://secret-user@example.com:443?security=reality&type=ws#bad";
        let error = decode_subscription(secret).unwrap_err();
        assert!(!error.contains("secret-user"));
        assert!(!error.contains("vless://"));
    }
}

#[cfg(test)]
mod import_tests {
    use super::*;
    #[test]
    fn removes_identical_links_without_merging_different_transports() {
        let a = "vless://test@example.com:443?security=tls&type=tcp#node";
        let b = "vless://test@example.com:443?security=tls&type=ws#node";
        assert_eq!(decode_subscription(&format!("{a}\n{a}\n{b}")).unwrap().len(), 2);
    }
    #[test]
    fn rejects_empty_and_oversized_input() {
        assert!(decode_subscription(" ").is_err());
        assert!(decode_subscription(&"x".repeat(MAX_SUBSCRIPTION_BYTES + 1)).is_err());
    }
}

#[cfg(test)]
mod identity_tests {
    use super::*;
    use std::{io::{Read, Write}, net::TcpListener, thread};

    fn read_request(socket: &mut std::net::TcpStream) -> String {
        socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut bytes = Vec::new();
        while !bytes.ends_with(b"\r\n\r\n") {
            let mut byte = [0];
            if socket.read(&mut byte).unwrap() == 0 { break; }
            bytes.push(byte[0]);
        }
        String::from_utf8(bytes).unwrap()
    }

    #[test]
    fn builder_preserves_exact_hwid_and_metadata_without_global_identity_headers() {
        let client = SubscriptionClient { http: Client::new(), os_version: "10.0.26100".into() };
        let identity = SubscriptionIdentity::parse("ABC123-existing-Value==".into()).unwrap();
        let request = client.request("https://example.invalid/sub", &identity).unwrap().header("X-Provider-Custom", "preserved").build().unwrap();
        for (header, value) in [("x-hwid", identity.as_str()), ("x-device-os", "Windows"), ("x-ver-os", "10.0.26100"), ("x-device-model", "dot Windows"), ("Accept", "*/*"), ("X-Provider-Custom", "preserved")] {
            assert_eq!(request.headers().get(header).unwrap().to_str().unwrap(), value);
        }
        assert!(client.http.get("https://example.invalid/update-check").build().unwrap().headers().get("x-hwid").is_none());
    }

    #[test]
    fn initial_fetch_and_retry_identity_is_the_one_saved_with_imported_subscription() {
        let server = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/import", server.local_addr().unwrap());
        let identity = SubscriptionIdentity::generate().unwrap();
        let expected = identity.clone();
        let worker = thread::spawn(move || {
            for status in ["503 Service Unavailable", "200 OK"] {
                let (mut socket, _) = server.accept().unwrap();
                let request = read_request(&mut socket);
                assert!(request.contains(&format!("x-hwid: {}\r\n", expected.as_str())));
                assert!(request.contains("user-agent: dot-desktop/"));
                let body = super::tests::LINK;
                write!(socket, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        let client = SubscriptionClient::new().unwrap();
        assert!(client.fetch(&url, &identity).is_err());
        let nodes = client.fetch(&url, &identity).unwrap();
        let dir = std::env::temp_dir().join(format!("dot-identity-import-{}-{}", std::process::id(), crate::refresh::now_ms()));
        let store = crate::storage::Store::open(dir.join("state.json")).unwrap();
        store.upsert_group(crate::model::SubscriptionGroup { id: "import".into(), name: "import".into(), url, updated_at_ms: 1, nodes, hwid: Some(identity.clone()) }).unwrap();
        assert_eq!(crate::storage::Store::open(dir.join("state.json")).unwrap().subscription_source("import").unwrap().1, identity);
        worker.join().unwrap();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn redirects_keep_same_origin_identity_and_strip_metadata_at_other_origins() {
        let first = TcpListener::bind("127.0.0.1:0").unwrap();
        let second = TcpListener::bind("127.0.0.1:0").unwrap();
        let start = format!("http://{}/start", first.local_addr().unwrap());
        let end = format!("http://{}/cdn", second.local_addr().unwrap());
        let identity = SubscriptionIdentity::generate().unwrap();
        let expected = identity.clone();
        let worker = thread::spawn(move || {
            for location in ["/same-origin".to_string(), end] {
                let (mut socket, _) = first.accept().unwrap();
                assert!(read_request(&mut socket).contains(&format!("x-hwid: {}\r\n", expected.as_str())));
                write!(socket, "HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").unwrap();
            }
            let (mut socket, _) = second.accept().unwrap();
            let request = read_request(&mut socket);
            for header in ["x-hwid:", "x-device-os:", "x-ver-os:", "x-device-model:"] { assert!(!request.contains(header)); }
            let body = super::tests::LINK;
            write!(socket, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
        });
        assert_eq!(SubscriptionClient::new().unwrap().fetch(&start, &identity).unwrap().len(), 1);
        worker.join().unwrap();
    }
}
