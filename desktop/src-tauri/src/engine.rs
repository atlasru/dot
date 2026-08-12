use std::{
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{atomic::{AtomicBool, Ordering}, Arc, Mutex, RwLock, Weak},
    thread,
    time::Duration,
};

use reqwest::blocking::Client;

use crate::{
    config::build_xray_config,
    job::ProcessJob,
    model::{EnginePhase, EngineSnapshot, VlessNode},
    session::SessionJournal,
};

pub struct VpnEngine {
    runtime_source: PathBuf,
    work_dir: PathBuf,
    child: Option<Child>,
    job: Option<ProcessJob>,
    shared: Arc<RwLock<EngineSnapshot>>,
    cancel: Arc<AtomicBool>,
    journal: SessionJournal,
}

impl VpnEngine {
    pub fn new(
        runtime_source: PathBuf,
        work_dir: PathBuf,
        shared: Arc<RwLock<EngineSnapshot>>,
        cancel: Arc<AtomicBool>,
    ) -> Result<Self, String> {
        fs::create_dir_all(&work_dir).map_err(|e| format!("failed to create VPN work directory: {e}"))?;
        let journal = SessionJournal::new(&work_dir)?;
        let _ = journal.clear_stale()?;
        let engine = Self { runtime_source, work_dir, child: None, job: None, shared, cancel, journal };
        engine.publish(EngineSnapshot::default());
        Ok(engine)
    }

    pub fn start(&mut self, node: &VlessNode) -> Result<EngineSnapshot, String> {
        self.stop_internal();
        self.cancel.store(false, Ordering::SeqCst);
        self.set_starting(node, "validating Xray configuration");

        let (xray, _) = self.prepare_runtime().map_err(|e| self.publish_error(node, e))?;
        let config = build_xray_config(node).map_err(|e| self.publish_error(node, e))?;
        let config_path = self.work_dir.join("config.json");
        let bytes = serde_json::to_vec_pretty(&config).map_err(|e| e.to_string())?;
        fs::write(&config_path, bytes)
            .map_err(|e| self.publish_error(node, format!("failed to write Xray config: {e}")))?;
        if self.is_cancelled() { return Ok(self.cancelled()); }

        let tested = command(&xray, &self.work_dir)
            .args(["run", "-test", "-config"])
            .arg(&config_path)
            .output()
            .map_err(|e| self.publish_error(node, format!("failed to test Xray config: {e}")))?;
        if !tested.status.success() {
            let stderr = String::from_utf8_lossy(&tested.stderr).trim().to_string();
            return Err(self.publish_error(node, if stderr.is_empty() { "Xray rejected the generated config".into() } else { stderr }));
        }
        if self.is_cancelled() { return Ok(self.cancelled()); }

        self.set_starting(node, "starting Windows TUN");
        let mut child = command(&xray, &self.work_dir)
            .args(["run", "-config"])
            .arg(&config_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| self.publish_error(node, format!("failed to start Xray: {e}")))?;

        let job = ProcessJob::kill_on_close().map_err(|e| {
            let _ = child.kill();
            let _ = child.wait();
            self.publish_error(node, e)
        })?;
        if let Err(error) = job.assign(&child) {
            drop(job);
            let _ = child.kill();
            let _ = child.wait();
            return Err(self.publish_error(node, error));
        }
        if let Err(error) = self.journal.write(child.id(), &node.id, &node.name, &xray) {
            terminate_local(job, &mut child);
            return Err(self.publish_error(node, error));
        }

        for _ in 0..12 {
            if self.is_cancelled() {
                terminate_local(job, &mut child);
                let _ = self.journal.clear();
                return Ok(self.cancelled());
            }
            thread::sleep(Duration::from_millis(250));
            match child.try_wait() {
                Ok(Some(status)) => {
                    drop(job);
                    let _ = self.journal.clear();
                    return Err(self.publish_error(node, format!("Xray exited during startup ({status})")));
                }
                Ok(None) => {}
                Err(error) => {
                    terminate_local(job, &mut child);
                    let _ = self.journal.clear();
                    return Err(self.publish_error(node, format!("failed to inspect Xray: {error}")));
                }
            }
        }

        self.set_starting(node, "checking connectivity");
        match connectivity_probe(&self.cancel) {
            Ok(true) => {}
            Ok(false) => {
                terminate_local(job, &mut child);
                let _ = self.journal.clear();
                return Ok(self.cancelled());
            }
            Err(error) => {
                terminate_local(job, &mut child);
                let _ = self.journal.clear();
                return Err(self.publish_error(node, error));
            }
        }
        if self.is_cancelled() {
            terminate_local(job, &mut child);
            let _ = self.journal.clear();
            return Ok(self.cancelled());
        }

        self.job = Some(job);
        self.child = Some(child);
        let value = EngineSnapshot { phase: EnginePhase::Connected, node_name: Some(node.name.clone()), message: Some("connected".into()) };
        self.publish(value.clone());
        Ok(value)
    }

    pub fn stop(&mut self) -> EngineSnapshot {
        self.cancel.store(true, Ordering::SeqCst);
        let current = self.snapshot();
        self.publish(EngineSnapshot { phase: EnginePhase::Stopping, node_name: current.node_name, message: Some("disconnecting".into()) });
        self.stop_internal();
        let value = EngineSnapshot::default();
        self.publish(value.clone());
        value
    }

    pub fn poll(&mut self) {
        let result = match self.child.as_mut() {
            Some(child) => child.try_wait(),
            None => return,
        };
        match result {
            Ok(Some(status)) => {
                self.child = None;
                self.job = None;
                let _ = self.journal.clear();
                let current = self.snapshot();
                self.publish(EngineSnapshot { phase: EnginePhase::Error, node_name: current.node_name, message: Some(format!("Xray stopped unexpectedly ({status})")) });
            }
            Ok(None) => {}
            Err(error) => {
                let current = self.snapshot();
                self.publish(EngineSnapshot { phase: EnginePhase::Error, node_name: current.node_name, message: Some(format!("failed to inspect Xray process: {error}")) });
            }
        }
    }

    fn set_starting(&self, node: &VlessNode, message: &str) {
        self.publish(EngineSnapshot { phase: EnginePhase::Starting, node_name: Some(node.name.clone()), message: Some(message.into()) });
    }

    fn publish_error(&self, node: &VlessNode, message: String) -> String {
        self.publish(EngineSnapshot { phase: EnginePhase::Error, node_name: Some(node.name.clone()), message: Some(message.clone()) });
        message
    }

    fn cancelled(&self) -> EngineSnapshot {
        let value = EngineSnapshot::default();
        self.publish(value.clone());
        value
    }

    fn publish(&self, value: EngineSnapshot) {
        match self.shared.write() {
            Ok(mut shared) => *shared = value,
            Err(poisoned) => *poisoned.into_inner() = value,
        }
    }

    fn snapshot(&self) -> EngineSnapshot {
        match self.shared.read() {
            Ok(shared) => shared.clone(),
            Err(poisoned) => poisoned.into_inner().clone(),
        }
    }

    fn is_cancelled(&self) -> bool { self.cancel.load(Ordering::SeqCst) }

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
        drop(self.job.take());
        if let Some(mut child) = self.child.take() {
            if child.try_wait().ok().flatten().is_none() { let _ = child.kill(); }
            let _ = child.wait();
        }
        let _ = self.journal.clear();
    }
}

impl Drop for VpnEngine {
    fn drop(&mut self) { self.stop_internal(); }
}

pub fn spawn_watchdog(engine: &Arc<Mutex<VpnEngine>>) {
    let weak: Weak<Mutex<VpnEngine>> = Arc::downgrade(engine);
    thread::spawn(move || loop {
        thread::sleep(Duration::from_secs(1));
        let Some(engine) = weak.upgrade() else { break; };
        let lock_result = engine.try_lock();
        if let Ok(mut locked) = lock_result { locked.poll(); }
    });
}

fn terminate_local(job: ProcessJob, child: &mut Child) {
    drop(job);
    if child.try_wait().ok().flatten().is_none() { let _ = child.kill(); }
    let _ = child.wait();
}

fn copy_if_changed(source: &Path, target: &Path) -> Result<(), String> {
    let same_len = source.metadata().ok().zip(target.metadata().ok()).map(|(a, b)| a.len() == b.len()).unwrap_or(false);
    if !same_len { fs::copy(source, target).map_err(|e| format!("failed to prepare {}: {e}", target.display()))?; }
    Ok(())
}

fn connectivity_probe(cancel: &AtomicBool) -> Result<bool, String> {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|e| format!("failed to initialize connectivity check: {e}"))?;
    let probes = [
        "https://cp.cloudflare.com/generate_204",
        "https://www.google.com/generate_204",
        "https://www.msftconnecttest.com/connecttest.txt",
    ];
    let mut last_error = String::new();
    for url in probes {
        if cancel.load(Ordering::SeqCst) { return Ok(false); }
        match client.get(url).send() {
            Ok(response) if response.status().is_success() || response.status().as_u16() == 204 => return Ok(true),
            Ok(response) => last_error = format!("connectivity check returned HTTP {}", response.status().as_u16()),
            Err(error) => last_error = error.to_string(),
        }
    }
    if cancel.load(Ordering::SeqCst) { return Ok(false); }
    Err(format!("VPN started but connectivity check failed: {last_error}"))
}

fn command(xray: &Path, current_dir: &Path) -> Command {
    let mut value = Command::new(xray);
    value.current_dir(current_dir);
    #[cfg(windows)] {
        use std::os::windows::process::CommandExt;
        value.creation_flags(0x08000000);
    }
    value
}
