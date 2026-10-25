#[cfg(test)]
mod tests {
    use std::{env, fs, path::PathBuf, process::Command};

    use crate::{config::{build_url_test_config, build_xray_config}, hysteria2::parse_hysteria2, vless::parse_vless};

    #[test]
    fn generated_configs_are_accepted_by_pinned_xray() {
        let Ok(xray) = env::var("DOT_XRAY_EXE") else {
            // Local unit-test runs do not have to download Xray. CI always sets
            // DOT_XRAY_EXE, so the compatibility check is mandatory there.
            return;
        };
        let xray = PathBuf::from(xray);
        assert!(xray.is_file(), "DOT_XRAY_EXE does not point to xray.exe");

        let node = parse_vless(
            "vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&security=tls&sni=example.com&fp=chrome&type=tcp#smoke",
        )
        .expect("smoke-test VLESS URI should parse");

        assert_xray_accepts(&xray, "tun", &build_xray_config(&node).expect("TUN config should build"));
        assert_xray_accepts(&xray, "url-test", &build_url_test_config(&node, 18080).expect("URL test config should build"));
        let mut advanced=crate::settings::AdvancedSettings::default();
        advanced.dns="8.8.8.8".into(); advanced.bypass_lan=true; advanced.log_level="error".into();
        let mut configured=build_xray_config(&node).unwrap();
        crate::config::apply_settings(&mut configured,&advanced);
        assert_xray_accepts(&xray,"advanced-tun",&configured);
        use base64::Engine;
        let key=base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([1u8;32]);
        let reality=parse_vless(&format!("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=reality&sni=example.com&pbk={key}&type=tcp&fp=chrome&flow=xtls-rprx-vision")).unwrap();
        assert_xray_accepts(&xray,"reality-tun",&build_xray_config(&reality).unwrap());
        assert_xray_accepts(&xray,"reality-test",&build_url_test_config(&reality,18082).unwrap());
        for (name, uri) in [
            ("hy2-basic", "hy2://password@example.com:443?sni=example.com&alpn=h3#basic"),
            ("hy2-mask", "hysteria2://password@example.com:443?sni=example.com&obfs=salamander&obfs-password=mask&packetSize=512-1200&congestion=bbr#mask"),
        ] {
            let node = parse_hysteria2(uri).expect("Hysteria2 fixture should parse");
            assert_xray_accepts(&xray, name, &build_xray_config(&node).expect("HY2 TUN config should build"));
            assert_xray_accepts(&xray, &format!("{name}-url"), &build_url_test_config(&node, 18081).expect("HY2 URL test should build"));
        }
    }

    fn assert_xray_accepts(xray: &PathBuf, name: &str, config: &serde_json::Value) {
        let config_path = env::temp_dir().join(format!("dot-xray-{name}-smoke-{}.json", std::process::id()));
        fs::write(
            &config_path,
            serde_json::to_vec_pretty(config).expect("serialize smoke config"),
        )
        .expect("write smoke config");

        let output = Command::new(xray)
            .arg("run")
            .arg("-test")
            .arg("-config")
            .arg(&config_path)
            .current_dir(xray.parent().expect("xray.exe has a parent directory"))
            .output()
            .expect("execute pinned xray.exe");
        let _ = fs::remove_file(&config_path);

        assert!(
            output.status.success(),
            "pinned Xray rejected {name} config:\nstdout: {}\nstderr: {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
