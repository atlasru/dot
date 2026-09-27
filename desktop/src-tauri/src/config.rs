use serde_json::{json, Map, Value};

use crate::model::{ProxyConfig, Security, Transport, ProxyNode};

pub fn build_xray_config(node: &ProxyNode) -> Result<Value, String> {
    let proxy = build_proxy_outbound(node)?;

    Ok(json!({
        "log": { "loglevel": "warning" },
        "inbounds": [
            {
                "tag": "dot-tun",
                "protocol": "tun",
                "settings": {
                    "name": "dot0",
                    "desc": "dot.",
                    "mtu": 1500,
                    "gateway": ["10.77.0.1/30"],
                    "dns": ["1.1.1.1", "8.8.8.8"],
                    "autoSystemRoutingTable": ["0.0.0.0/0"],
                    "autoOutboundsInterface": "auto"
                },
                "sniffing": {
                    "enabled": true,
                    "destOverride": ["http", "tls", "quic"]
                }
            }
        ],
        "outbounds": [
            proxy,
            {
                "tag": "direct",
                "protocol": "freedom"
            }
        ],
        "routing": {
            "domainStrategy": "AsIs",
            "rules": [
                {
                    "type": "field",
                    "inboundTag": ["dot-tun"],
                    "outboundTag": "proxy"
                }
            ]
        }
    }))
}

pub fn build_url_test_config(node: &ProxyNode, port: u16) -> Result<Value, String> {
    let proxy = build_proxy_outbound(node)?;

    Ok(json!({
        "log": { "loglevel": "warning" },
        "inbounds": [
            {
                "tag": "dot-url-test",
                "listen": "127.0.0.1",
                "port": port,
                "protocol": "http",
                "settings": { "timeout": 5 }
            }
        ],
        "outbounds": [
            proxy,
            {
                "tag": "direct",
                "protocol": "freedom"
            }
        ],
        "routing": {
            "domainStrategy": "AsIs",
            "rules": [
                {
                    "type": "field",
                    "inboundTag": ["dot-url-test"],
                    "outboundTag": "proxy"
                }
            ]
        }
    }))
}

fn build_proxy_outbound(node: &ProxyNode) -> Result<Value, String> {
    if let ProxyConfig::Hysteria2(hy2) = &node.proxy_config {
        if hy2.auth.is_empty() { return Err("Hysteria2 authentication is missing".into()); }
        if hy2.packet_size.is_some() && hy2.salamander_password.is_none() {
            return Err("Salamander packet size requires a password".into());
        }
        let mut tls = Map::new();
        if let Some(sni) = &hy2.sni { tls.insert("serverName".into(), json!(sni)); }
        if hy2.insecure { tls.insert("allowInsecure".into(), json!(true)); }
        if !hy2.alpn.is_empty() { tls.insert("alpn".into(), json!(hy2.alpn)); }
        let mut hysteria = Map::new();
        hysteria.insert("version".into(), json!(2));
        hysteria.insert("auth".into(), json!(hy2.auth));
        if let Some(up) = &hy2.up { hysteria.insert("up".into(), json!(up)); }
        if let Some(down) = &hy2.down { hysteria.insert("down".into(), json!(down)); }
        let mut stream = Map::new();
        stream.insert("method".into(), json!("hysteria"));
        stream.insert("security".into(), json!("tls"));
        stream.insert("tlsSettings".into(), Value::Object(tls));
        stream.insert("hysteriaSettings".into(), Value::Object(hysteria));
        let mut finalmask = Map::new();
        if let Some(congestion) = &hy2.congestion {
            finalmask.insert("quicParams".into(), json!({"congestion": congestion}));
        }
        if let Some(password) = &hy2.salamander_password {
            let mut settings = Map::new();
            settings.insert("password".into(), json!(password));
            if let Some(size) = &hy2.packet_size { settings.insert("packetSize".into(), json!(size)); }
            finalmask.insert("udp".into(), json!([{"type": "salamander", "settings": settings}]));
        }
        if !finalmask.is_empty() { stream.insert("finalmask".into(), Value::Object(finalmask)); }
        return Ok(json!({
            "tag": "proxy", "protocol": "hysteria",
            "settings": {"version": 2, "address": node.host, "port": node.port},
            "streamSettings": stream,
        }));
    }
    validate_node(node)?;

    let mut settings = Map::new();
    settings.insert("address".into(), json!(node.host));
    settings.insert("port".into(), json!(node.port));
    settings.insert("id".into(), json!(node.user_id));
    settings.insert("encryption".into(), json!(node.encryption));
    if let Some(flow) = &node.flow {
        settings.insert("flow".into(), json!(flow));
    }

    let mut stream = Map::new();
    stream.insert("method".into(), json!(transport_method(&node.transport)));
    stream.insert("security".into(), json!(security_name(&node.security)));

    match node.transport {
        Transport::Raw => {}
        Transport::Websocket => {
            let mut ws = Map::new();
            ws.insert("path".into(), json!(node.path.as_deref().unwrap_or("/")));
            if let Some(host) = &node.host_header {
                ws.insert("host".into(), json!(host));
            }
            stream.insert("wsSettings".into(), Value::Object(ws));
        }
        Transport::Grpc => {
            let mut grpc = Map::new();
            grpc.insert("serviceName".into(), json!(node.service_name.as_deref().unwrap_or("")));
            stream.insert("grpcSettings".into(), Value::Object(grpc));
        }
        Transport::Xhttp => {
            let mut xhttp = Map::new();
            if let Some(path) = &node.path {
                xhttp.insert("path".into(), json!(path));
            }
            if let Some(host) = &node.host_header {
                xhttp.insert("host".into(), json!(host));
            }
            if let Some(mode) = &node.mode {
                xhttp.insert("mode".into(), json!(mode));
            }
            stream.insert("xhttpSettings".into(), Value::Object(xhttp));
        }
        Transport::Httpupgrade => {
            let mut upgrade = Map::new();
            upgrade.insert("path".into(), json!(node.path.as_deref().unwrap_or("/")));
            if let Some(host) = &node.host_header {
                upgrade.insert("host".into(), json!(host));
            }
            stream.insert("httpupgradeSettings".into(), Value::Object(upgrade));
        }
    }

    match node.security {
        Security::None => {}
        Security::Tls => {
            let mut tls = Map::new();
            if let Some(sni) = &node.sni {
                tls.insert("serverName".into(), json!(sni));
            }
            if let Some(fp) = &node.fingerprint {
                tls.insert("fingerprint".into(), json!(fp));
            }
            if !node.alpn.is_empty() {
                tls.insert("alpn".into(), json!(node.alpn));
            }
            stream.insert("tlsSettings".into(), Value::Object(tls));
        }
        Security::Reality => {
            let mut reality = Map::new();
            reality.insert("serverName".into(), json!(node.sni.as_deref().unwrap()));
            reality.insert(
                "fingerprint".into(),
                json!(node.fingerprint.as_deref().unwrap_or("chrome")),
            );
            reality.insert("password".into(), json!(node.public_key.as_deref().unwrap()));
            reality.insert("shortId".into(), json!(node.short_id.as_deref().unwrap_or("")));
            if let Some(spider_x) = &node.spider_x {
                reality.insert("spiderX".into(), json!(spider_x));
            }
            if let Some(verify) = &node.mldsa65_verify {
                reality.insert("mldsa65Verify".into(), json!(verify));
            }
            stream.insert("realitySettings".into(), Value::Object(reality));
        }
    }

    Ok(json!({
        "tag": "proxy",
        "protocol": "vless",
        "settings": Value::Object(settings),
        "streamSettings": Value::Object(stream)
    }))
}

fn validate_node(node: &ProxyNode) -> Result<(), String> {
    if node.security == Security::Reality {
        if node.sni.as_deref().unwrap_or("").is_empty() {
            return Err("REALITY node is missing SNI/serverName".into());
        }
        if node.public_key.as_deref().unwrap_or("").is_empty() {
            return Err("REALITY node is missing public key/password".into());
        }
    }
    Ok(())
}

fn transport_method(transport: &Transport) -> &'static str {
    match transport {
        Transport::Raw => "raw",
        Transport::Websocket => "websocket",
        Transport::Grpc => "grpc",
        Transport::Xhttp => "xhttp",
        Transport::Httpupgrade => "httpupgrade",
    }
}

fn security_name(security: &Security) -> &'static str {
    match security {
        Security::None => "none",
        Security::Tls => "tls",
        Security::Reality => "reality",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vless::parse_vless;

    fn reality_node() -> ProxyNode {
        parse_vless("vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&security=reality&sni=example.org&fp=chrome&pbk=password&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision#node").unwrap()
    }

    #[test]
    fn emits_current_reality_client_fields() {
        let config = build_xray_config(&reality_node()).unwrap();
        assert_eq!(config["outbounds"][0]["streamSettings"]["method"], "raw");
        assert_eq!(config["outbounds"][0]["streamSettings"]["realitySettings"]["serverName"], "example.org");
        assert_eq!(config["outbounds"][0]["streamSettings"]["realitySettings"]["password"], "password");
        assert_eq!(config["inbounds"][0]["settings"]["autoOutboundsInterface"], "auto");
    }

    #[test]
    fn emits_isolated_http_proxy_for_url_tests() {
        let config = build_url_test_config(&reality_node(), 18080).unwrap();
        assert_eq!(config["inbounds"][0]["protocol"], "http");
        assert_eq!(config["inbounds"][0]["listen"], "127.0.0.1");
        assert_eq!(config["inbounds"][0]["port"], 18080);
        assert_eq!(config["routing"]["rules"][0]["outboundTag"], "proxy");
    }
}
