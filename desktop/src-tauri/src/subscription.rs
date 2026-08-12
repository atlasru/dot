use base64::{engine::general_purpose, Engine as _};
use reqwest::blocking::Client;
use std::time::Duration;

use crate::{model::VlessNode, vless::parse_vless};

pub struct SubscriptionClient {
    http: Client,
}

impl SubscriptionClient {
    pub fn new() -> Result<Self, String> {
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .user_agent("dot-desktop/0.1.0-alpha.1")
            .build()
            .map_err(|e| format!("failed to initialize HTTP client: {e}"))?;
        Ok(Self { http })
    }

    pub fn fetch(&self, url: &str) -> Result<Vec<VlessNode>, String> {
        let response = self
            .http
            .get(url)
            .header("Accept", "*/*")
            .send()
            .map_err(|e| format!("subscription request failed: {e}"))?;

        let status = response.status();
        if !status.is_success() {
            return Err(format!("subscription server returned HTTP {}", status.as_u16()));
        }

        let body = response
            .text()
            .map_err(|e| format!("failed to read subscription response: {e}"))?;
        decode_subscription(&body)
    }
}

pub fn decode_subscription(body: &str) -> Result<Vec<VlessNode>, String> {
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
    let mut errors = Vec::new();
    for token in plaintext
        .split(|c: char| c.is_whitespace())
        .map(str::trim)
        .filter(|s| s.to_ascii_lowercase().starts_with("vless://"))
    {
        match parse_vless(token) {
            Ok(node) => nodes.push(node),
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
}
