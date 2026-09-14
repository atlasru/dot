use std::{collections::HashMap, sync::{Arc, Mutex, RwLock}, thread, time::{Duration, SystemTime, UNIX_EPOCH}};
use serde::Serialize;
use crate::{model::SubscriptionRefreshResult, storage::Store, subscription::SubscriptionClient};

#[derive(Debug, Clone, Serialize)]
pub struct RefreshNotice {
    pub group_id: String,
    pub attempted_at_ms: u64,
    pub error: Option<String>,
    pub result: Option<SubscriptionRefreshResult>,
}

pub struct RefreshService {
    pub gate: Mutex<()>,
    notices: RwLock<HashMap<String, RefreshNotice>>,
}

impl RefreshService {
    pub fn new() -> Self { Self { gate: Mutex::new(()), notices: RwLock::new(HashMap::new()) } }

    pub fn notices(&self, store: &Store) -> Vec<RefreshNotice> {
        let groups = store.groups();
        self.notices.read().unwrap_or_else(|p| p.into_inner()).values()
            .filter(|n| groups.iter().any(|g| g.id == n.group_id)).cloned().collect()
    }

    pub fn refresh_locked(&self, store: &Store, group_id: &str) -> Result<SubscriptionRefreshResult, String> {
        let result = (|| {
            let url = store.group_url(group_id)?;
            if url.is_empty() { return Err("local imports have no subscription URL".into()); }
            let nodes = SubscriptionClient::new()?.fetch(&url)?;
            store.apply_refresh(group_id, nodes, now_ms())
        })();
        self.record(store, group_id, &result);
        result
    }

    pub fn record(&self, store: &Store, group_id: &str, result: &Result<SubscriptionRefreshResult, String>) {
        let notice = RefreshNotice { group_id: group_id.into(), attempted_at_ms: now_ms(), error: result.as_ref().err().cloned(), result: result.as_ref().ok().cloned() };
        let mut notices = self.notices.write().unwrap_or_else(|p| p.into_inner());
        let groups = store.groups();
        notices.retain(|id, _| groups.iter().any(|g| &g.id == id));
        notices.insert(group_id.into(), notice);
    }
}

pub fn due(now: u64, last_success: u64, last_attempt: Option<u64>, hours: u32, startup: bool) -> bool {
    if let Some(attempt) = last_attempt {
        // Failed servers retry no more often than every five minutes.
        if now.saturating_sub(attempt) < 300_000 { return false; }
    }
    startup || (hours > 0 && now.saturating_sub(last_success) >= u64::from(hours) * 3_600_000)
}

pub fn spawn(store: Arc<Store>, service: Arc<RefreshService>) {
    thread::spawn(move || {
        let mut startup = true;
        loop {
            let policy = store.preferences();
            let initial = startup && policy.refresh_on_start;
            startup = false;
            for group in store.groups().into_iter().filter(|g| g.remote) {
                // UI edits and all network refreshes share this gate. Re-read after acquiring it.
                let Ok(_guard) = service.gate.lock() else { return; };
                let Some(current) = store.groups().into_iter().find(|g| g.id == group.id && g.remote) else { continue; };
                let policy = store.preferences();
                let last_attempt = service.notices.read().unwrap_or_else(|p| p.into_inner()).get(&group.id).map(|n| n.attempted_at_ms);
                if due(now_ms(), current.updated_at_ms, last_attempt, policy.refresh_interval_hours, initial && policy.refresh_on_start) {
                    let _ = service.refresh_locked(&store, &group.id);
                }
            }
            thread::sleep(Duration::from_secs(30));
        }
    });
}

pub fn now_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn schedule_handles_disabled_startup_retry_and_clock_rollback() {
        assert!(!due(10_000_000, 0, None, 0, false));
        assert!(due(100, 100, None, 0, true));
        assert!(due(3_600_000, 0, None, 1, false));
        assert!(!due(3_600_000, 0, Some(3_500_000), 1, false));
        assert!(due(3_900_000, 0, Some(3_500_000), 1, false));
        assert!(!due(100, 200, None, 1, false));
    }
}

#[cfg(test)]
mod network_tests {
    use super::*;
    use std::{io::{Read, Write}, net::TcpListener};
    use crate::{model::SubscriptionGroup, vless::parse_vless};
    #[test]
    fn failed_refresh_keeps_working_group_and_success_publishes_diff() {
        let server = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/private-token", server.local_addr().unwrap());
        let old = parse_vless("vless://test@example.com:443?security=tls#old").unwrap();
        let fresh = "vless://test@example.com:443?security=tls#renamed";
        let dir = std::env::temp_dir().join(format!("dot-refresh-{}-{}", std::process::id(), now_ms()));
        let store = Store::open(dir.join("state.json")).unwrap();
        store.upsert_group(SubscriptionGroup { id: "test".into(), name: "test".into(), url, updated_at_ms: 10, nodes: vec![old.clone()] }).unwrap();
        let worker = thread::spawn(move || {
            for (status, body) in [("500 Internal Server Error", "failed"), ("200 OK", fresh)] {
                let (mut socket, _) = server.accept().unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let mut request = [0; 4096]; let _ = socket.read(&mut request).unwrap();
                write!(socket, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        let service = RefreshService::new();
        let _guard = service.gate.lock().unwrap();
        let error = service.refresh_locked(&store, "test").unwrap_err();
        assert!(!error.contains("private-token"));
        assert_eq!(store.groups()[0].nodes[0].id, old.id);
        assert_eq!(store.groups()[0].updated_at_ms, 10);
        assert!(service.notices(&store)[0].error.is_some());
        let result = service.refresh_locked(&store, "test").unwrap();
        assert_eq!(result.edited.len(), 1);
        assert_eq!(store.groups()[0].nodes[0].name, "renamed");
        assert!(service.notices(&store)[0].error.is_none());
        worker.join().unwrap();
        let _ = std::fs::remove_dir_all(dir);
    }
}
