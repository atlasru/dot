use std::{net::{IpAddr, Ipv4Addr, Ipv6Addr, ToSocketAddrs}, time::Duration};

use reqwest::blocking::Client;
use serde::Serialize;
use serde_json::Value;

#[derive(Debug, Clone, Serialize)]
pub struct GeoPoint {
    pub host: String,
    pub ip: Option<String>,
    pub latitude: f64,
    pub longitude: f64,
    pub country: String,
    pub country_code: String,
    pub city: Option<String>,
    pub source: String,
}

#[tauri::command]
pub async fn node_geo(host: String, name: String) -> Result<GeoPoint, String> {
    tauri::async_runtime::spawn_blocking(move || resolve(&host, &name))
        .await
        .map_err(|e| format!("GeoIP task failed: {e}"))?
}

fn resolve(host: &str, name: &str) -> Result<GeoPoint, String> {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(7))
        .user_agent("dot-desktop/0.2.2")
        .build()
        .map_err(|e| format!("failed to initialize GeoIP client: {e}"))?;

    if let Ok(ip) = resolve_public_ip(host) {
        if let Ok(point) = lookup_ip(&client, host, ip) { return Ok(point); }
    }

    let code = country_code_from_name(name).ok_or_else(|| format!("could not locate {host}"))?;
    lookup_country_center(&client, host, &code)
}

fn lookup_ip(client: &Client, host: &str, ip: IpAddr) -> Result<GeoPoint, String> {
    let text = client.get(format!("https://ipapi.co/{ip}/json/"))
        .send().map_err(|e| format!("GeoIP request failed: {e}"))?
        .error_for_status().map_err(|e| format!("GeoIP HTTP error: {e}"))?
        .text().map_err(|e| format!("failed to read GeoIP response: {e}"))?;
    let value: Value = serde_json::from_str(&text).map_err(|e| format!("invalid GeoIP response: {e}"))?;
    if value.get("error").and_then(Value::as_bool) == Some(true) { return Err("GeoIP lookup failed".into()); }
    let code = value.get("country_code").and_then(Value::as_str).unwrap_or("").trim().to_ascii_uppercase();
    if code.len() != 2 { return Err("GeoIP response has no country code".into()); }
    let latitude = value.get("latitude").and_then(Value::as_f64).ok_or("GeoIP response has no latitude")?;
    let longitude = value.get("longitude").and_then(Value::as_f64).ok_or("GeoIP response has no longitude")?;
    Ok(GeoPoint {
        host: host.to_string(), ip: Some(ip.to_string()), latitude, longitude,
        country: value.get("country_name").and_then(Value::as_str).unwrap_or(&code).to_string(),
        country_code: code,
        city: value.get("city").and_then(Value::as_str).filter(|v| !v.trim().is_empty()).map(str::to_string),
        source: "geo_ip".into(),
    })
}

fn lookup_country_center(client: &Client, host: &str, code: &str) -> Result<GeoPoint, String> {
    let text = client.get(format!("https://restcountries.com/v3.1/alpha/{code}?fields=cca2,name,latlng"))
        .send().map_err(|e| format!("country fallback request failed: {e}"))?
        .error_for_status().map_err(|e| format!("country fallback HTTP error: {e}"))?
        .text().map_err(|e| format!("failed to read country fallback: {e}"))?;
    let value: Value = serde_json::from_str(&text).map_err(|e| format!("invalid country fallback response: {e}"))?;
    let root = if value.is_array() { value.get(0).unwrap_or(&value) } else { &value };
    let latlng = root.get("latlng").and_then(Value::as_array).ok_or("country fallback has no coordinates")?;
    let latitude = latlng.first().and_then(Value::as_f64).ok_or("country fallback has no latitude")?;
    let longitude = latlng.get(1).and_then(Value::as_f64).ok_or("country fallback has no longitude")?;
    let country = root.get("name").and_then(|v| v.get("common")).and_then(Value::as_str).unwrap_or(code).to_string();
    Ok(GeoPoint { host: host.into(), ip: None, latitude, longitude, country, country_code: code.into(), city: None, source: "name_fallback".into() })
}

fn resolve_public_ip(host: &str) -> Result<IpAddr, String> {
    let clean = host.trim().trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = clean.parse::<IpAddr>() {
        if is_public(ip) { return Ok(ip); }
        return Err("node host resolved to a private address".into());
    }
    (clean, 443).to_socket_addrs()
        .map_err(|e| format!("DNS lookup failed for {clean}: {e}"))?
        .map(|addr| addr.ip())
        .find(|ip| is_public(*ip))
        .ok_or_else(|| format!("DNS lookup returned no public addresses for {clean}"))
}

fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => is_public_v4(v),
        IpAddr::V6(v) => is_public_v6(v),
    }
}

fn is_public_v4(ip: Ipv4Addr) -> bool {
    !(ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_broadcast() || ip.is_documentation() || ip.is_unspecified() || ip.is_multicast())
}

fn is_public_v6(ip: Ipv6Addr) -> bool {
    let octets = ip.octets();
    !(ip.is_loopback() || ip.is_unspecified() || ip.is_multicast() || (octets[0] & 0xfe) == 0xfc || (octets[0] == 0xfe && (octets[1] & 0xc0) == 0x80))
}

fn country_code_from_name(name: &str) -> Option<String> {
    let chars: Vec<char> = name.chars().collect();
    for pair in chars.windows(2) {
        let first = pair[0] as u32;
        let second = pair[1] as u32;
        if (0x1f1e6..=0x1f1ff).contains(&first) && (0x1f1e6..=0x1f1ff).contains(&second) {
            let a = char::from_u32('A' as u32 + first - 0x1f1e6)?;
            let b = char::from_u32('A' as u32 + second - 0x1f1e6)?;
            return Some(format!("{a}{b}"));
        }
    }
    let lower = name.to_lowercase();
    let aliases = [
        ("united states", "US"), (" usa", "US"), ("america", "US"), ("united kingdom", "GB"), ("great britain", "GB"), ("england", "GB"),
        ("netherlands", "NL"), ("holland", "NL"), ("germany", "DE"), ("deutschland", "DE"), ("france", "FR"), ("finland", "FI"),
        ("sweden", "SE"), ("norway", "NO"), ("poland", "PL"), ("spain", "ES"), ("italy", "IT"), ("switzerland", "CH"), ("austria", "AT"),
        ("czech", "CZ"), ("romania", "RO"), ("bulgaria", "BG"), ("ukraine", "UA"), ("russia", "RU"), ("россия", "RU"), ("japan", "JP"),
        ("singapore", "SG"), ("hong kong", "HK"), ("korea", "KR"), ("canada", "CA"), ("brazil", "BR"), ("australia", "AU"), ("india", "IN"),
        ("turkey", "TR"), ("türkiye", "TR"), ("israel", "IL"), ("uae", "AE"),
    ];
    if let Some((_, code)) = aliases.iter().find(|(alias, _)| lower.contains(alias)) { return Some((*code).into()); }
    for token in name.split(|c: char| !c.is_ascii_alphabetic()) {
        if token.len() == 2 {
            let code = token.to_ascii_uppercase();
            if code == "UK" { return Some("GB".into()); }
            if KNOWN_CODES.contains(&code.as_str()) { return Some(code); }
        }
    }
    None
}

const KNOWN_CODES: &[&str] = &["US","GB","NL","DE","FR","FI","SE","NO","PL","ES","IT","CH","AT","CZ","RO","BG","UA","RU","JP","SG","HK","KR","CA","BR","AU","IN","TR","IL","AE","BE","DK","IE","PT","GR","HU","RS","HR","EE","LV","LT","IS","LU","MD","GE","AM","AZ","KZ","TH","VN","ID","MY","PH","TW","NZ","MX","AR","CL","ZA"];
