use crate::{
    model::{ProxyConfig, ProxyNode},
    settings::{aggregate, PingMethod, PingSettings},
};
use serde::{Deserialize, Serialize};
use std::{
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
    pub fn unavailable(&self) -> bool {
        self.error.as_deref().is_some_and(|error| {
            error.starts_with("HEAD unsupported") || error.starts_with("ICMP unavailable")
                || error.starts_with("TCP does not test Hysteria2")
        })
    }

    pub fn compatible(&self, settings: &PingSettings) -> bool {
        !self.active_tunnel && &self.settings == settings
    }
    pub fn verified(&self, settings: &PingSettings) -> bool {
        self.success
            && self.method.proxy()
            && self.compatible(settings)
            && now_ms()
                .checked_sub(self.tested_at_ms)
                .is_some_and(|age| age < 900_000)
    }
}
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Work slots are replenished as individual nodes finish.
pub fn run_bounded<T: Sync, R: Send>(
    items: &[T],
    parallelism: usize,
    cancel: &AtomicBool,
    worker: impl Fn(&T) -> R + Sync,
) -> Result<Vec<R>, String> {
    let next = std::sync::atomic::AtomicUsize::new(0);
    let workers = parallelism.clamp(1, 8).min(items.len());
    let mut results = std::thread::scope(|scope| {
        let handles: Vec<_> = (0..workers).map(|_| {
            let next = &next;
            let worker = &worker;
            scope.spawn(move || {
                let mut results = Vec::new();
                while !cancel.load(Ordering::SeqCst) {
                    let index = next.fetch_add(1, Ordering::Relaxed);
                    let Some(item) = items.get(index) else { break };
                    results.push((index, worker(item)));
                }
                results
            })
        }).collect();
        let mut results = Vec::new();
        for handle in handles {
            results.extend(handle.join().map_err(|_| "diagnostics worker failed")?);
        }
        Ok::<_, String>(results)
    })?;
    results.sort_unstable_by_key(|(index, _)| *index);
    Ok(results.into_iter().map(|(_, result)| result).collect())
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
#[path = "diagnostic_network.rs"]
pub(crate) mod network;

pub fn direct(node: &ProxyNode, settings: &PingSettings, cancel: &AtomicBool) -> PingResult {
    measure(settings, false, cancel, || {
        if settings.method == PingMethod::Tcp && matches!(node.proxy_config, ProxyConfig::Hysteria2(_)) {
            return Err("TCP does not test Hysteria2 UDP/QUIC; use Via Proxy".into());
        }
        let deadline = Instant::now() + Duration::from_secs(settings.timeout_seconds);
        tauri::async_runtime::block_on(network::within(deadline, cancel, "Diagnostics attempt timed out", async {
            let addresses = network::resolve(&node.host, node.port).await?;
            match settings.method {
                PingMethod::Tcp => {
                    let start = Instant::now();
                    let mut error = "TCP connect failed or timed out".to_owned();
                    for (index, address) in addresses.iter().copied().enumerate() {
                        let remaining = deadline.saturating_duration_since(Instant::now());
                        let address_deadline = Instant::now() + remaining / (addresses.len() - index) as u32;
                        let interface = match network::egress(address) {
                            Ok(interface) => interface,
                            Err(failure) => { error = failure; continue; }
                        };
                        match network::within(address_deadline, cancel, "TCP connect timed out",
                            network::tcp(address, interface.as_ref())).await {
                            Ok(()) => return Ok(start.elapsed().as_millis().max(1) as u64),
                            Err(failure) => error = failure,
                        }
                    }
                    Err(error)
                }
                PingMethod::Icmp => {
                    let address = addresses[0];
                    let interface = network::egress(address)?;
                    network::icmp(address, interface, deadline).await
                }
                _ => Err("Proxy request required".into()),
            }
        }))
    })
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
        let mut future = r.clone();
        future.tested_at_ms = now_ms() + 60_000;
        assert!(!future.verified(&s));
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

#[cfg(test)]
mod worker_tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    #[test]
    #[cfg(windows)]
    fn system_icmp_loopback_is_an_actual_icmp_probe() {
        for address in ["127.0.0.1:0", "[::1]:0"] {
            let latency = tauri::async_runtime::block_on(network::icmp(
                address.parse().unwrap(),
                None,
                Instant::now() + Duration::from_secs(3),
            ))
            .unwrap();
            assert!(latency >= 1);
        }
    }
    #[test]
    fn worker_pool_is_bounded_and_cancellation_stops_new_work() {
        let active = AtomicUsize::new(0);
        let peak = AtomicUsize::new(0);
        let cancel = AtomicBool::new(false);
        let items: Vec<_> = (0..12).collect();
        let results = run_bounded(&items, 2, &cancel, |n| {
            let count = active.fetch_add(1, Ordering::SeqCst) + 1;
            peak.fetch_max(count, Ordering::SeqCst);
            std::thread::sleep(Duration::from_millis(10));
            active.fetch_sub(1, Ordering::SeqCst);
            *n
        })
        .unwrap();
        assert_eq!(results, items);
        assert!(peak.load(Ordering::SeqCst) <= 2);
        let results = run_bounded(&items, 1, &cancel, |n| {
            cancel.store(true, Ordering::SeqCst);
            *n
        })
        .unwrap();
        assert_eq!(results, vec![0]);
    }
}
