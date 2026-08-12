#[cfg(test)]
mod tests {
    use std::{env, fs, path::PathBuf, process::Command};

    use crate::{config::build_xray_config, vless::parse_vless};

    #[test]
    fn generated_windows_tun_config_is_accepted_by_pinned_xray() {
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
        let config = build_xray_config(&node).expect("smoke-test config should build");

        let config_path = env::temp_dir().join(format!("dot-xray-smoke-{}.json", std::process::id()));
        fs::write(
            &config_path,
            serde_json::to_vec_pretty(&config).expect("serialize smoke config"),
        )
        .expect("write smoke config");

        let output = Command::new(&xray)
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
            "pinned Xray rejected generated config:\nstdout: {}\nstderr: {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
