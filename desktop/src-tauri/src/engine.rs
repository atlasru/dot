use std::{
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    thread,
    time::Duration,
};

use reqwest::blocking::Client;

use crate::{config::build_xray_config, model::{EnginePhase, EngineSnapshot, VlessNode}};

pub struct VpnEngine {
    runtime_source: PathBuf,
    work_dir: PathBuf,
    child: Option<Child>,
    snapshot: EngineSnapshot,
}

impl VpnEngine {
    pub fn new(runtime_source: PathBuf, work_dir: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&work_dir).map_err(|e| format!("failed to create VPN work directory: {e}"))?;
        Ok(Self {
            runtime_source,
            work_dir,
            child: None,
            snapshot: EngineSnapshot::default(),
        })
    }

    pub fn start(&mut self, node: &VlessNode) -> Result<EngineSnapshot, String> {
        self.stop_internal();
        self.snapshot = EngineSnapshot {
            phase: EnginePhase::Starting,
            node_name: Some(node.name.clone()),
            message: Some("validating Xray configuration".into()),
        };

        let (xray, _wintun) = self.prepare_runtime()?;
        let config = build_xray_config(node)?;
        let config_path = self.work_dir.join("config.json");
        fs::write(&config_path, serde_json::to_vec_pretty(&config).map_err(|e| e.to_string())?)
            .map_err(|e| format!("failed to write Xray config: {e}"))?;

        let test = command(&xray, &self.work_dir)
            .arg("run")
            .arg("-test")
            .arg("-config")
            .arg(&config_path)
            .output()
            .map_err(|e| format!("failed to test Xray config: {e}"))?;
        if !test.status.success() {
            let error = String::from_utf8_lossy(&test.stderr).trim().to_string();
            self.snapshot.phase = EnginePhase::Error;
            self.snapshot.message = Some(if error.is_empty() { "Xray rejected the generated config".into() } else { error.clone() });
            return Err(self.snapshot.message.clone().unwrap());
        }

        self.snapshot.message = Some("starting TUN".into());
        let mut child = command(&xray, &self.work_dir)
            .arg("run")
            .arg("-config")
            .arg(&config_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("failed to start Xray: {e}"))?;

        for _ in 0..12 {
            thread::sleep(Duration::from_millis(250));
            if let Some(status) = child.try_wait().map_err(|e| format!("failed to inspect Xray: {e}"))? {
                self.snapshot.phase = EnginePhase::Error;
                self.snapshot.message = Some(format!("Xray exited during startup ({status})"));
                return Err(self.snapshot.message.clone().unwrap());
            }
        }

        self.snapshot.message = Some("checking connectivity".into());
        if let Err(error) = connectivity_probe() {
            let _ = child.kill();
            let _ = child.wait();
            self.snapshot.phase = EnginePhase::Error;
            self.snapshot.message = Some(error.clone());
            return Err(error);
        }

        self.child = Some(child);
        self.snapshot = EngineSnapshot {
            phase: EnginePhase::Connected,
            node_name: Some(node.name.clone()),
            message: Some("connected".into()),
        };
        Ok(self.snapshot.clone())
    }

    pub fn stop(&mut self) -> EngineSnapshot {
        self.snapshot.phase = EnginePhase::Stopping;
        self.stop_internal();
        self.snapshot = EngineSnapshot::default();
        self.snapshot.clone()
    }

    pub fn status(&mut self) -> EngineSnapshot {
        if let Some(child) = self.child.as_mut() {
            match child.try_wait() {
                Ok(Some(status)) => {
                    self.child = None;
                    self.snapshot.phase = EnginePhase::Error;
                    self.snapshot.message = Some(format!("Xray stopped unexpectedly ({status})"));
                }
                Ok(None) => {}
                Err(error) => {
                    self.snapshot.phase = EnginePhase::Error;
                    self.snapshot.message = Some(format!("failed to inspect Xray process: {error}"));
                }
            }
        }
        self.snapshot.clone()
    }

    fn prepare_runtime(&self) -> Result<(PathBuf, PathBuf), String> {
        let source_xray = self.runtime_source.join("xray.exe");
        let source_wintun = self.runtime_source.join("wintun.dll");
        if !source_xray.exists() || !source_wintun.exists() {
            return Err(format!("Xray runtime is incomplete at {}", self.runtime_source.display()));
        }

        let runtime = self.work_dir.join("runtime");
        fs::create_dir_all(&runtime).map_err(|e| format!("failed to create runtime directory: {e}"))?;
        let xray = runtime.join("xray.exe");
        let wintun = runtime.join("wintun.dll");
        copy_if_changed(&source_xray, &xray)?;
        copy_if_changed(&source_wintun, &wintun)?;
        Ok((xray, wintun))
    }

    fn stop_internal(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

impl Drop for VpnEngine {
    fn drop(&mut self) {
        self.stop_internal();
    }
}

fn copy_if_changed(source: &Path, target: &Path) -> Result<(), String> {
    let same_len = source.metadata().ok().zip(target.metadata().ok()).map(|(a, b)| a.len() == b.len()).unwrap_or(false);
    if !same_len {
        fs::copy(source, target).map_err(|e| format!("failed to prepare {}: {e}", target.display()))?;
    }
    Ok(())
}

fn connectivity_probe() -> Result<(), String> {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(|e| format!("failed to initialize connectivity check: {e}"))?;

    let probes = [
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204",
        "https://www.msftconnecttest.com/connecttest.txt",
    ];

    let mut last_error = String::new();
    for url in probes {
        match client.get(url).send() {
            Ok(response) if response.status().is_success() || response.status().as_u16() == 204 => return Ok(()),
            Ok(response) => last_error = format!("connectivity check returned HTTP {}", response.status().as_u16()),
            Err(error) => last_error = error.to_string(),
        }
    }
    Err(format!("VPN started but connectivity check failed: {last_error}"))
}

fn command(xray: &Path, current_dir: &Path) -> Command {
    let mut command = Command::new(xray);
    command.current_dir(current_dir);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    command
}
