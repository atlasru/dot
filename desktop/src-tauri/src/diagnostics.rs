use crate::{
    model::{ProxyConfig, ProxyNode},
    settings::{aggregate, PingMethod, PingSettings},
};
use serde::{Deserialize, Serialize};
use std::{
    net::{TcpStream, ToSocketAddrs},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PingResult {
    pub method: PingMethod,
    pub latency_ms: Option<u64>,
    pub tested_at_ms: u64,
    pub success: bool,
    pub error: Option<String>,
    pub url: Option<String>,
    pub samples: Vec<u64>,
    pub attempts_succeeded: usize,
    pub active_tunnel: bool,
    pub settings: PingSettings,
}
impl PingResult {
    pub fn compatible(&self, settings: &PingSettings) -> bool {
        &self.settings == settings
    }
    pub fn verified(&self, settings: &PingSettings) -> bool {
        self.success
            && self.method.proxy()
            && self.compatible(settings)
            && now_ms().saturating_sub(self.tested_at_ms) < 900_000
    }
}
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub fn cancelled(cancel: &AtomicBool) -> Result<(), String> {
    if cancel.load(Ordering::SeqCst) {
        Err("diagnostics cancelled".into())
    } else {
        Ok(())
    }
}
pub fn measure(
    settings: &PingSettings,
    active: bool,
    cancel: &AtomicBool,
    mut attempt: impl FnMut() -> Result<u64, String>,
) -> PingResult {
    let mut samples = Vec::new();
    let mut error = None;
    for _ in 0..settings.attempts {
        if let Err(e) = cancelled(cancel) {
            error = Some(e);
            break;
        }
        match attempt() {
            Ok(ms) => samples.push(ms),
            Err(e) => error = Some(e),
        }
    }
    let latency = aggregate(&mut samples.clone(), settings.aggregation);
    // A partial measurement retains samples but never confirms proxy health.
    let success = samples.len() == settings.attempts && !cancel.load(Ordering::SeqCst);
    PingResult {
        method: settings.method,
        latency_ms: if success { latency } else { None },
        tested_at_ms: now_ms(),
        success,
        error,
        url: settings.method.proxy().then(|| settings.url.clone()),
        attempts_succeeded: samples.len(),
        samples,
        active_tunnel: active,
        settings: settings.clone(),
    }
}
pub fn direct(node: &ProxyNode, settings: &PingSettings, cancel: &AtomicBool) -> PingResult {
    measure(settings, false, cancel, || {
        if settings.method == PingMethod::Tcp
            && matches!(node.proxy_config, ProxyConfig::Hysteria2(_))
        {
            return Err("TCP does not test Hysteria2 UDP/QUIC; use Via Proxy".into());
        }
        // DNS lookup is outside the TCP/ICMP timer; system resolver has its own timeout.
        let addresses: Vec<_> = (node.host.as_str(), node.port)
            .to_socket_addrs()
            .map_err(|_| "server DNS lookup failed")?
            .collect();
        let address = addresses.first().ok_or("server has no IP address")?;
        match settings.method {
            PingMethod::Tcp => {
                let start = Instant::now();
                TcpStream::connect_timeout(address, Duration::from_secs(settings.timeout_seconds))
                    .map_err(|_| "TCP connect failed or timed out")?;
                Ok(start.elapsed().as_millis().max(1) as u64)
            }
            PingMethod::Icmp => icmp(address.ip(), settings.timeout_seconds, cancel),
            _ => Err("proxy request required".into()),
        }
    })
}
fn icmp(address: std::net::IpAddr, timeout: u64, cancel: &AtomicBool) -> Result<u64, String> {
    // .NET Ping uses the Windows ICMP API and returns round-trip time independently
    // of process startup, with no parsing of localized ping.exe output.
    let script=format!("$p=[System.Net.NetworkInformation.Ping]::new(); try {{ $r=$p.Send('{}',{}); if ($r.Status -eq 'Success') {{ $r.RoundtripTime }} else {{ exit 2 }} }} finally {{ $p.Dispose() }}",address,timeout*1000);
    let mut cmd = std::process::Command::new("powershell.exe");
    cmd.args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    let mut child = cmd
        .spawn()
        .map_err(|_| "ICMP unavailable: Windows PowerShell / ICMP API required")?;
    let start = Instant::now();
    loop {
        if cancel.load(Ordering::SeqCst) || start.elapsed() > Duration::from_secs(timeout + 5) {
            let _ = child.kill();
            let _ = child.wait();
            return Err("ICMP cancelled or timed out".into());
        }
        if child.try_wait().map_err(|_| "ICMP unavailable")?.is_some() {
            break;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    let output = child.wait_with_output().map_err(|_| "ICMP unavailable")?;
    if !output.status.success() {
        return Err("ICMP blocked, unavailable or timed out; proxy health is unknown".into());
    }
    String::from_utf8_lossy(&output.stdout)
        .trim()
        .parse::<u64>()
        .map(|ms| ms.max(1))
        .map_err(|_| "ICMP unavailable".into())
}

pub fn spawn_scheduler(
    store: std::sync::Arc<crate::storage::Store>,
    operation: std::sync::Arc<AtomicBool>,
    snapshot: std::sync::Arc<std::sync::RwLock<crate::model::EngineSnapshot>>,
    cancel: std::sync::Arc<AtomicBool>,
    exiting: std::sync::Arc<AtomicBool>,
    runtime: std::path::PathBuf,
    dir: std::path::PathBuf,
) {
    std::thread::spawn(move || {
        let mut startup = true;
        let mut previous = Instant::now();
        while !exiting.load(Ordering::SeqCst) {
            let settings = store.preferences().advanced.ping;
            let due = (startup && settings.on_start)
                || (settings.repeat_minutes > 0
                    && previous.elapsed()
                        >= Duration::from_secs(settings.repeat_minutes as u64 * 60));
            startup = false;
            if due {
                previous = Instant::now();
                if let Ok(_guard) = crate::operation::Operation::acquire(&operation) {
                    if matches!(
                        crate::commands::read_snapshot(&snapshot).phase,
                        crate::model::EnginePhase::Offline | crate::model::EnginePhase::Error
                    ) {
                        cancel.store(false, Ordering::SeqCst);
                        let selection = store.selection();
                        let nodes = if settings.selected_only {
                            store.selected_node().map(|n| vec![n])
                        } else {
                            selection
                                .group_id
                                .as_deref()
                                .ok_or("no selected subscription".into())
                                .and_then(|id| store.group_nodes(id))
                        };
                        if let Ok(nodes) = nodes {
                            for batch in nodes.chunks(settings.parallelism) {
                                if cancel.load(Ordering::SeqCst) || exiting.load(Ordering::SeqCst) {
                                    break;
                                }
                                std::thread::scope(|scope| {
                                    let handles: Vec<_> = batch
                                        .iter()
                                        .map(|node| {
                                            let runtime = &runtime;
                                            let dir = &dir;
                                            let settings = &settings;
                                            let cancel = &cancel;
                                            scope.spawn(move || {
                                                (
                                                    node.id.clone(),
                                                    crate::url_test::test_node(
                                                        runtime, dir, node, settings, cancel,
                                                    ),
                                                )
                                            })
                                        })
                                        .collect();
                                    for handle in handles {
                                        if let Ok((id, result)) = handle.join() {
                                            if !cancel.load(Ordering::SeqCst) {
                                                let _ = store.record_diagnostic(&id, result);
                                            }
                                        }
                                    }
                                });
                            }
                        }
                    }
                }
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn partial_and_cancelled_never_confirm_health() {
        let mut s = PingSettings::default();
        s.attempts = 2;
        let c = AtomicBool::new(false);
        let mut i = 0;
        let r = measure(&s, false, &c, || {
            i += 1;
            if i == 1 {
                Ok(12)
            } else {
                Err("timeout".into())
            }
        });
        assert!(!r.success);
        assert_eq!(r.samples, vec![12]);
        assert_eq!(r.latency_ms, None);
        c.store(true, Ordering::SeqCst);
        assert!(!measure(&s, false, &c, || panic!()).success);
    }
    #[test]
    fn method_and_url_invalidate_cache_and_tcp_is_not_health() {
        let c = AtomicBool::new(false);
        let mut s = PingSettings::default();
        let r = measure(&s, false, &c, || Ok(12));
        assert!(r.verified(&s));
        s.method = PingMethod::Tcp;
        assert!(!r.compatible(&s));
        let r = measure(&s, false, &c, || Ok(1));
        assert!(!r.verified(&s));
    }
    #[test]
    fn tcp_loopback_and_hy2_guard() {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let n = crate::vless::parse_vless(&format!(
            "vless://test@127.0.0.1:{}",
            l.local_addr().unwrap().port()
        ))
        .unwrap();
        let mut s = PingSettings::default();
        s.method = PingMethod::Tcp;
        s.attempts = 1;
        assert!(direct(&n, &s, &AtomicBool::new(false)).success);
        let h = crate::hysteria2::parse_hysteria2("hy2://secret@127.0.0.1:443").unwrap();
        assert!(!direct(&h, &s, &AtomicBool::new(false)).success);
    }
}
