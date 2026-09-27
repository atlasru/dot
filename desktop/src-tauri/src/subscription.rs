use base64::{engine::general_purpose, Engine as _};
use reqwest::blocking::Client;
use std::{time::Duration, io::Read};
pub const MAX_SUBSCRIPTION_BYTES: usize = 4 * 1024 * 1024;

use crate::{hysteria2::parse_hysteria2, model::ProxyNode, vless::parse_vless};

fn supported(line: &str) -> bool {
    ["vless://", "hy2://", "hysteria2://"].iter().any(|scheme| line.to_ascii_lowercase().starts_with(scheme))
}

fn parse_node(line: &str) -> Result<ProxyNode, String> {
    if line.to_ascii_lowercase().starts_with("vless://") { parse_vless(line) }
    else { parse_hysteria2(line) }
}

pub struct SubscriptionClient {
    http: Client,
}

impl SubscriptionClient {
    pub fn new() -> Result<Self, String> {
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .user_agent(concat!("dot-desktop/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|_| "failed to initialize subscription HTTP client".to_string())?;
        Ok(Self { http })
    }

    pub fn fetch(&self, url: &str) -> Result<Vec<ProxyNode>, String> {
        let response = self
            .http
            .get(url)
            .header("Accept", "*/*")
            .send()
            .map_err(format_request_error)?;

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

pub fn decode_subscription(body: &str) -> Result<Vec<ProxyNode>, String> {
    if body.len() > MAX_SUBSCRIPTION_BYTES { return Err("subscription exceeds 4 MB".into()); }
    let body = body.trim().trim_start_matches('\u{feff}').trim();
    if body.is_empty() {
        return Err("subscription response is empty".into());
    }

    let plaintext = if body.split_whitespace().any(supported) {
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
        .filter(|s| supported(s))
    {
        match parse_node(token) {
            Ok(node) => { if seen.insert(node.id.clone()) { nodes.push(node); } },
            Err(error) => errors.push(error),
        }
    }

    if nodes.is_empty() {
        if let Some(first) = errors.first() {
            Err(format!("subscription contains no usable nodes: {first}"))
        } else {
            Err("subscription contains no supported nodes".into())
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
                if text.split_whitespace().any(supported) {
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

    const LINK: &str = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=ws&path=%2Fdot#node";

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
    fn accepts_hy2_only_and_mixed_base64() {
        let hy2 = "hy2://password@example.com:443?sni=example.com#HY2";
        assert_eq!(decode_subscription(hy2).unwrap()[0].protocol(), "HY2");
        let mixed = STANDARD.encode(format!("{LINK}\n{hy2}\nhysteria2://pass@other.example:8443#other\nunsupported://x"));
        let nodes = decode_subscription(&mixed).unwrap();
        assert_eq!(nodes.len(), 3);
        assert_eq!(nodes[0].protocol(), "VLESS");
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
