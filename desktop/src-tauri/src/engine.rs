use std::{
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, RwLock, Weak,
    },
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
    shared_snapshot: Arc<RwLock<EngineSnapshot>>,
    cancel: Arc<AtomicBool>,
    journal: SessionJournal,
}

impl VpnEngine {
    pub fn new(
        runtime_source: PathBuf,
        work_dir: PathBuf,
        shared_snapshot: Arc<RwLock<EngineSnapshot>>,
        cancel: Arc<AtomicBool>,
    ) -> Result<Self, String> {
        fs::create_dir_all(&work_dir)
            .map_err(|e| format!("failed to create VPN work directory: {e}"))?;
        let journal = SessionJournal::new(&work_dir)?;
        let _ = journal.clear_stale()?;

        let engine = Self {
            runtime_source,
            work_dir,
            child: None,
            job: None,
            shared_snapshot,
            cancel,
            journal,
        };
        engine.publish(EngineSnapshot::default());
        Ok(engine)
    }

    pub fn start(&mut self, node: &VlessNode) -> Result<EngineSnapshot, String> {
        self.stop_internal();
        self.cancel.store(false, Ordering::SeqCst);
        self.publish(EngineSnapshot {
            phase: EnginePhase::Starting,
            node_name: Some(node.name.clone()),
            message: Some("validating Xray configuration".into()),
        });

        let (xray, _wintun) = match self.prepare_runtime() {
            Ok(runtime) => runtime,
            Err(error) => return self.fail(node, error),
        };
        let config = match build_xray_config(node) {
            Ok(config) => config,
            Err(error) => return self.fail(node, error),
        };
        let config_path = self.work_dir.join("config.json");
        if let Err(error) = fs::write(
            &config_path,
            serde_json::to_vec_pretty(&config).map_err(|e| e.to_string())?,
        ) {
            return self.fail(node, format!("failed to write Xray config: {error}"));
        }

        if self.is_cancelled() {
            return Ok(self.cancelled());
        }

        let test = match command(&xray, &self.work_dir)
            .arg("run")
            .arg("-test")
            .arg("-config")
            .arg(&config_path)
            .output()
        {
            Ok(output) => output,
            Err(error) => return self.fail(node, format!("failed to test Xray config: {error}")),
        };
        if !test.status.success() {
            let error = String::from_utf8_lossy(&test.stderr).trim().to_string();
            return self.fail(
                node,
                if error.is_empty() {
                    "Xray rejected the generated config".into()
                } else {
                    error
                },
            );
        }

        if self.is_cancelled() {
            return Ok(self.cancelled());
        }

        self.publish(EngineSnapshot {
            phase: EnginePhase::Starting,
            node_name: Some(node.name.clone()),
            message: Some("starting Windows TUN".into()),
        });

        let mut child = match command(&xray, &self.work_dir)
            .arg("run")
            .arg("-config")
            .arg(&config_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
        {
            Ok(child) => child,
            Err(error) => return self.fail(node, format!("failed to start Xray: {error}")),
        };

        let job = match ProcessJob::kill_on_close() {
            Ok(job) => job,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return self.fail(node, error);
            }
        };
        if let Err(error) = job.assign(&child) {
            drop(job);
            let _ = child.kill();
            let _ = child.wait();
            return self.fail(node, error);
        }

        if let Err(error) = self.journal.write(child.id(), &node.id, &node.name, &xray) {
            drop(job);
            let _ = child.wait();
            return self.fail(node, error);
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
                    return self.fail(node, format!("Xray exited during startup ({status})"));
                }
                Ok(None) => {}
                Err(error) => {
                    terminate_local(job, &mut child);
                    let _ = self.journal.clear();
                    return self.fail(node, format!("failed to inspect Xray: {error}"));
                }
            }
        }

        self.publish(EngineSnapshot {
            phase: EnginePhase::Starting,
            node_name: Some(node.name.clone()),
            message: Some("checking connectivity".into()),
        });

        match connectivity_probe(&self.cancel) {
            Ok(ProbeOutcome::Connected) => {}
            Ok(ProbeOutcome::Cancelled) => {
                terminate_local(job, &mut child);
                let _ = self.journal.clear();
                return Ok(self.cancelled());
            }
            Err(error) => {
                terminate_local(job, &mut child);
                let _ = self.journal.clear();
                return self.fail(node, error);
            }
        }

        if self.is_cancelled() {
            terminate_local(job, &mut child);
            let _ = self.journal.clear();
            return Ok(self.cancelled());
        }

        self.job = Some(job);
        self.child = Some(child);
        let connected = EngineSnapshot {
            phase: EnginePhase::Connected,
            node_name: Some(node.name.clone()),
            message: Some("connected".into()),
        };
        self.publish(connected.clone());
        Ok(connected)
    }

    pub fn stop(&mut self) -> EngineSnapshot {
        self.cancel.store(true, Ordering::SeqCst);
        self.publish(EngineSnapshot {
            phase: EnginePhase::Stopping,
            node_name: self.snapshot().node_name,
            message: Some("disconnecting".into()),
        });
        self.stop_internal();
        let offline = EngineSnapshot::default();
        self.publish(offline.clone());
        offline
    }

    pub fn poll(&mut self) {
        let Some(child) = self.child.as_mut() else {
            return;
        };
        match child.try_wait() {
            Ok(Some(status)) => {
                self.child = None;
                self.job = None;
                let _ = self.journal.clear();
                let previous = self.snapshot();
                self.publish(EngineSnapshot {
                    phase: EnginePhase::Error,
                    node_name: previous.node_name,
                    message: Some(format!("Xray stopped unexpectedly ({status})")),
                });
            }
            Ok(None) => {}
            Err(error) => {
                let previous = self.snapshot();
                self.publish(EngineSnapshot {
                    phase: EnginePhase::Error,
                    node_name: previous.node_name,
                    message: Some(format!("failed to inspect Xray process: {error}")),
                });
            }
        }
    }

    fn prepare_runtime(&self) -> Result<(PathBuf, PathBuf), String> {
        let source_xray = self.runtime_source.join("xray.exe");
        let source_wintun = self.runtime_source.join("wintun.dll");
        if !source_xray.exists() || !source_wintun.exists() {
            return Err(format!(
                "Xray runtime is incomplete at {}",
                self.runtime_source.display()
            ));
        }

        let runtime = self.work_dir.join("runtime");
        fs::create_dir_all(&runtime)
            .map_err(|e| format!("failed to create runtime directory: {e}"))?;
        let xray = runtime.join("xray.exe");
        let wintun = runtime.join("wintun.dll");
        copy_if_changed(&source_xray, &xray)?;
        copy_if_changed(&source_wintun, &wintun)?;
        Ok((xray, wintun))
    }

    fn stop_internal(&mut self) {
        // Closing the Job Object is the primary stop mechanism because it also
        // terminates any process Xray may have created. Child::kill remains a
        // fallback for a process that was never successfully assigned.
        if let Some(job) = self.job.take() {
            drop(job);
        }
        if let Some(mut child) = self.child.take() {
            if child.try_wait().ok().flatten().is_none() {
                let _ = child.kill();
            }
            let _ = child.wait();
        }
        let _ = self.journal.clear();
    }

    fn fail(&self, node: &VlessNode, error: String) -> Result<EngineSnapshot, String> {
        self.publish(EngineSnapshot {
            phase: EnginePhase::Error,
            node_name: Some(node.name.clone()),
            message: Some(error.clone()),
        });
        Err(error)
    }

    fn cancelled(&self) -> EngineSnapshot {
        let offline = EngineSnapshot::default();
        self.publish(offline.clone());
        offline
    }

    fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst)
    }

    fn publish(&self, snapshot: EngineSnapshot) {
        match self.shared_snapshot.write() {
            Ok(mut shared) => *shared = snapshot,
            Err(poisoned) => *poisoned.into_inner() = snapshot,
        }
    }

    fn snapshot(&self) -> EngineSnapshot {
        match self.shared_snapshot.read() {
            Ok(shared) => shared.clone(),
            Err(poisoned) => poisoned.into_inner().clone(),
        }
    }
}

impl Drop for VpnEngine {
    fn drop(&mut self) {
        self.stop_internal();
    }
}

pub fn spawn_watchdog(engine: &Arc<Mutex<VpnEngine>>) {
    let weak: Weak<Mutex<VpnEngine>> = Arc::downgrade(engine);
    thread::spawn(move || loop {
        thread::sleep(Duration::from_secs(1));
        let Some(engine) = weak.upgrade() else {
            break;
        };
        if let Ok(mut engine) = engine.try_lock() {
            engine.poll();
        }
    });
}

fn terminate_local(job: ProcessJob, child: &mut Child) {
    drop(job);
    if child.try_wait().ok().flatten().is_none() {
        let _ = child.kill();
    }
    let _ = child.wait();
}

fn copy_if_changed(source: &Path, target: &Path) -> Result<(), String> {
    let same_len = source
        .metadata()
        .ok()
        .zip(target.metadata().ok())
        .map(|(a, b)| a.len() == b.len())
        .unwrap_or(false);
    if !same_len {
        fs::copy(source, target)
            .map_err(|e| format!("failed to prepare {}: {e}", target.display()))?;
    }
    Ok(())
}

enum ProbeOutcome {
    Connected,
    Cancelled,
}

fn connectivity_probe(cancel: &AtomicBool) -> Result<ProbeOutcome, String> {
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
        if cancel.load(Ordering::SeqCst) {
            return Ok(ProbeOutcome::Cancelled);
        }
        match client.get(url).send() {
            Ok(response) if response.status().is_success() || response.status().as_u16() == 204 => {
                return Ok(ProbeOutcome::Connected)
            }
            Ok(response) => {
                last_error = format!("connectivity check returned HTTP {}", response.status().as_u16())
            }
            Err(error) => last_error = error.to_string(),
        }
    }
    if cancel.load(Ordering::SeqCst) {
        return Ok(ProbeOutcome::Cancelled);
    }
    Err(format!(
        "VPN started but connectivity check failed: {last_error}"
    ))
}

fn command(xray: &Path, current_dir: &Path) -> Command {
    let mut command = Command::new(xray);
    command.current_dir(current_dir);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    command
}
