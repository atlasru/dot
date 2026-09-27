use std::collections::HashMap;

use percent_encoding::percent_decode_str;
use url::Url;
use uuid::Uuid;

use crate::model::{Hysteria2Config, ProxyConfig, ProxyNode, Security, Transport};

pub fn parse_hysteria2(raw: &str) -> Result<ProxyNode, String> {
    let raw = raw.trim();
    if !raw.to_ascii_lowercase().starts_with("hy2://")
        && !raw.to_ascii_lowercase().starts_with("hysteria2://") {
        return Err("not a Hysteria2 URI".into());
    }
    let url = Url::parse(raw).map_err(|_| "invalid Hysteria2 URI".to_string())?;
    let host = url.host_str().ok_or("Hysteria2 host is missing")?
        .trim_matches(|c| c == '[' || c == ']').to_string();
    let port = url.port().unwrap_or(443);
    let decode = |value: &str| percent_decode_str(value).decode_utf8().map(|v| v.into_owned())
        .map_err(|_| "invalid Hysteria2 percent encoding".to_string());
    let auth = match url.password() {
        Some(password) => format!("{}:{}", decode(url.username())?, decode(password)?),
        None => decode(url.username())?,
    };
    if auth.is_empty() { return Err("Hysteria2 authentication is missing".into()); }
    let query: HashMap<String, String> = url.query_pairs().into_owned().collect();
    let get = |keys: &[&str]| keys.iter().find_map(|key| query.get(*key)).filter(|s| !s.is_empty()).cloned();
    let insecure = match get(&["insecure", "allowInsecure"]).as_deref() {
        None | Some("0" | "false") => false,
        Some("1" | "true") => true,
        _ => return Err("invalid Hysteria2 insecure flag".into()),
    };
    let mask = get(&["obfs", "finalmask", "mask"]);
    if mask.as_deref().is_some_and(|v| !v.eq_ignore_ascii_case("salamander")) {
        return Err("unsupported Hysteria2 mask".into());
    }
    let salamander_password = get(&["obfs-password", "obfsPassword", "salamanderPassword"]);
    let packet_size = get(&["packetSize", "packet_size"]);
    if mask.is_some() && salamander_password.is_none() { return Err("Salamander password is missing".into()); }
    if mask.is_none() && (salamander_password.is_some() || packet_size.is_some()) {
        return Err("Salamander settings require obfs=salamander".into());
    }
    if let Some(range) = &packet_size {
        let parts: Vec<_> = range.split('-').collect();
        let sizes: Option<Vec<u16>> = parts.iter().map(|v| v.parse::<u16>().ok()).collect();
        let sizes = sizes.ok_or("invalid Salamander packet size")?;
        if sizes.is_empty() || sizes.len() > 2 || sizes[0] == 0 || sizes.iter().any(|v| *v > 2048)
            || (sizes.len() == 2 && sizes[0] > sizes[1]) {
            return Err("invalid Salamander packet size".into());
        }
    }
    let congestion = get(&["congestion"]);
    if congestion.as_deref().is_some_and(|v| !matches!(v, "bbr" | "reno" | "brutal" | "force-brutal")) {
        return Err("unsupported Hysteria2 congestion".into());
    }
    let name = url.fragment().filter(|v| !v.is_empty()).map(decode).transpose()?
        .unwrap_or_else(|| format!("{host}:{port}"));
    let sni = get(&["sni", "serverName", "peer"]);
    let alpn: Vec<String> = get(&["alpn"]).map(|v| v.split(',').map(str::trim)
        .filter(|v| !v.is_empty()).map(str::to_string).collect()).unwrap_or_default();
    let proxy_config = ProxyConfig::Hysteria2(Hysteria2Config {
        auth, sni: sni.clone(), insecure, alpn: alpn.clone(), congestion,
        up: get(&["up", "upmbps"]), down: get(&["down", "downmbps"]),
        salamander_password, packet_size,
    });
    Ok(ProxyNode {
        id: Uuid::new_v5(&Uuid::NAMESPACE_URL, raw.as_bytes()).to_string(),
        name, host, port, user_id: String::new(), encryption: "none".into(), flow: None,
        security: Security::Tls, transport: Transport::Raw, sni, fingerprint: None,
        public_key: None, short_id: None, spider_x: None, mldsa65_verify: None,
        path: None, host_header: None, service_name: None, mode: None, alpn,
        raw_uri: raw.to_string(), proxy_config,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_alias_ipv6_and_mask() {
        let node = parse_hysteria2("hy2://a%40b%3Ac@[::1]:8443?sni=example.org&alpn=h3,h2&obfs=salamander&obfs-password=secret&packetSize=512-1200#%D0%A2%D0%B5%D1%81%D1%82").unwrap();
        assert_eq!(node.name, "Тест");
        assert_eq!(node.host, "::1");
        match node.proxy_config { ProxyConfig::Hysteria2(c) => {
            assert_eq!(c.auth, "a@b:c");
            assert_eq!(c.packet_size.as_deref(), Some("512-1200"));
        }, _ => panic!("wrong protocol") }
    }
    #[test]
    fn ignores_unknown_options_and_rejects_bad_mask() {
        assert!(parse_hysteria2("hysteria2://pass@example.com?newOption=x").is_ok());
        assert!(parse_hysteria2("hy2://pass@example.com?obfs=salamander").is_err());
        assert!(parse_hysteria2("hy2://pass@example.com?obfs=salamander&obfs-password=x&packetSize=3000").is_err());
    }
}
