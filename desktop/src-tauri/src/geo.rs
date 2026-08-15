use std::{net::{IpAddr, ToSocketAddrs}, time::Duration};

use reqwest::blocking::Client;
use serde::Serialize;
use serde_json::Value;

#[derive(Debug, Clone, Serialize)]
pub struct GeoPoint {
    pub host: String,
    pub ip: String,
    pub latitude: f64,
    pub longitude: f64,
    pub country: String,
    pub country_code: String,
}

#[tauri::command]
pub async fn node_geo(host: String) -> Result<GeoPoint, String> {
    tauri::async_runtime::spawn_blocking(move || resolve(&host))
        .await
        .map_err(|e| format!("GeoIP task failed: {e}"))?
}

fn resolve(host: &str) -> Result<GeoPoint, String> {
    let ip = resolve_ip(host)?;
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(5))
        .user_agent("dot-desktop/0.2")
        .build()
        .map_err(|e| format!("failed to initialize GeoIP client: {e}"))?;

    let value: Value = client
        .get(format!("https://ipwho.is/{ip}"))
        .send()
        .map_err(|e| format!("GeoIP request failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("GeoIP HTTP error: {e}"))?
        .json()
        .map_err(|e| format!("invalid GeoIP response: {e}"))?;

    if value.get("success").and_then(Value::as_bool) == Some(false) {
        return Err(value.get("message").and_then(Value::as_str).unwrap_or("GeoIP lookup failed").to_string());
    }

    let latitude = value.get("latitude").and_then(Value::as_f64).ok_or("GeoIP response has no latitude")?;
    let longitude = value.get("longitude").and_then(Value::as_f64).ok_or("GeoIP response has no longitude")?;
    Ok(GeoPoint {
        host: host.to_string(),
        ip: ip.to_string(),
        latitude,
        longitude,
        country: value.get("country").and_then(Value::as_str).unwrap_or("").to_string(),
        country_code: value.get("country_code").and_then(Value::as_str).unwrap_or("").to_string(),
    })
}

fn resolve_ip(host: &str) -> Result<IpAddr, String> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(ip);
    }
    (host, 443)
        .to_socket_addrs()
        .map_err(|e| format!("DNS lookup failed for {host}: {e}"))?
        .map(|addr| addr.ip())
        .find(IpAddr::is_ipv4)
        .or_else(|| (host, 443).to_socket_addrs().ok()?.next().map(|addr| addr.ip()))
        .ok_or_else(|| format!("DNS lookup returned no addresses for {host}"))
}
