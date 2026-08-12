use std::collections::HashMap;

use url::Url;
use uuid::Uuid;

use crate::model::{Security, Transport, VlessNode};

pub fn parse_vless(raw: &str) -> Result<VlessNode, String> {
    let raw = raw.trim();
    if !raw.to_ascii_lowercase().starts_with("vless://") {
        return Err("not a VLESS URI".into());
    }

    let url = Url::parse(raw).map_err(|e| format!("invalid VLESS URI: {e}"))?;
    let user_id = url.username().to_string();
    if user_id.is_empty() {
        return Err("VLESS user id is missing".into());
    }
    if user_id.as_bytes().len() > 30 {
        return Err("VLESS user id is longer than 30 bytes".into());
    }

    let host = url
        .host_str()
        .ok_or_else(|| "VLESS host is missing".to_string())?
        .to_string();
    let port = url.port().unwrap_or(443);
    let query: HashMap<String, String> = url.query_pairs().into_owned().collect();

    let security = match query.get("security").map(|v| v.to_ascii_lowercase()) {
        Some(v) if v == "reality" => Security::Reality,
        Some(v) if v == "tls" => Security::Tls,
        _ => Security::None,
    };

    let transport = match query
        .get("type")
        .map(|v| v.to_ascii_lowercase())
        .unwrap_or_else(|| "tcp".into())
        .as_str()
    {
        "tcp" | "raw" => Transport::Raw,
        "ws" | "websocket" => Transport::Websocket,
        "grpc" => Transport::Grpc,
        "xhttp" | "splithttp" => Transport::Xhttp,
        "httpupgrade" => Transport::Httpupgrade,
        other => return Err(format!("unsupported VLESS transport: {other}")),
    };

    if security == Security::Reality && matches!(transport, Transport::Websocket | Transport::Httpupgrade) {
        return Err("REALITY cannot be used with WebSocket/HTTPUpgrade".into());
    }

    let name = url
        .fragment()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| host.as_str())
        .to_string();

    let stable_id = Uuid::new_v5(&Uuid::NAMESPACE_URL, raw.as_bytes()).to_string();
    let alpn = query
        .get("alpn")
        .map(|v| v.split(',').map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect())
        .unwrap_or_default();

    Ok(VlessNode {
        id: stable_id,
        name,
        host,
        port,
        user_id,
        encryption: query.get("encryption").cloned().unwrap_or_else(|| "none".into()),
        flow: non_empty(&query, "flow"),
        security,
        transport,
        sni: non_empty(&query, "sni").or_else(|| non_empty(&query, "serverName")),
        fingerprint: non_empty(&query, "fp").or_else(|| non_empty(&query, "fingerprint")),
        public_key: non_empty(&query, "pbk").or_else(|| non_empty(&query, "publicKey")),
        short_id: query.get("sid").cloned().or_else(|| query.get("shortId").cloned()),
        spider_x: non_empty(&query, "spx"),
        mldsa65_verify: non_empty(&query, "pqv"),
        path: non_empty(&query, "path"),
        host_header: non_empty(&query, "host"),
        service_name: non_empty(&query, "serviceName"),
        mode: non_empty(&query, "mode"),
        alpn,
        raw_uri: raw.to_string(),
    })
}

fn non_empty(query: &HashMap<String, String>, key: &str) -> Option<String> {
    query.get(key).filter(|value| !value.is_empty()).cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_reality_vision_link() {
        let node = parse_vless("vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&security=reality&sni=example.org&fp=chrome&pbk=abc&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision#France%20%2328").unwrap();
        assert_eq!(node.name, "France #28");
        assert_eq!(node.security, Security::Reality);
        assert_eq!(node.transport, Transport::Raw);
        assert_eq!(node.sni.as_deref(), Some("example.org"));
        assert_eq!(node.flow.as_deref(), Some("xtls-rprx-vision"));
    }

    #[test]
    fn rejects_reality_websocket() {
        let error = parse_vless("vless://id@example.com:443?security=reality&type=ws&sni=x&pbk=y").unwrap_err();
        assert!(error.contains("REALITY"));
    }
}
