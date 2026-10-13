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
            let (url, hwid) = store.subscription_source(group_id)?;
            if url.is_empty() { return Err("local imports have no subscription URL".into()); }
            let nodes = SubscriptionClient::new()?.fetch(&url, &hwid)?;
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
            refresh_due(&store, &service, startup);
            startup = false;
            thread::sleep(Duration::from_secs(30));
        }
    });
}

fn refresh_due(store: &Store, service: &RefreshService, startup: bool) {
    let initial = startup && store.preferences().refresh_on_start;
    for group in store.groups().into_iter().filter(|g| g.remote) {
        // UI edits and all network refreshes share this gate. Re-read after acquiring it.
        let Ok(_guard) = service.gate.lock() else { return; };
        let Some(current) = store.groups().into_iter().find(|g| g.id == group.id && g.remote) else { continue; };
        let policy = store.preferences();
        let last_attempt = service.notices.read().unwrap_or_else(|p| p.into_inner()).get(&group.id).map(|n| n.attempted_at_ms);
        if due(now_ms(), current.updated_at_ms, last_attempt, policy.refresh_interval_hours, initial && policy.refresh_on_start) {
            let _ = service.refresh_locked(store, &group.id);
        }
    }
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
        store.upsert_group(SubscriptionGroup { id: "test".into(), name: "test".into(), url, updated_at_ms: 10, nodes: vec![old.clone()], hwid: None }).unwrap();
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

    #[test]
    fn actual_scheduler_and_manual_refresh_use_group_identity_instead_of_selection() {
        let server = TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", server.local_addr().unwrap());
        let dir = std::env::temp_dir().join(format!("dot-background-identity-{}-{}", std::process::id(), now_ms()));
        let store = Arc::new(Store::open(dir.join("state.json")).unwrap());
        let old = parse_vless("vless://test@example.com:443?security=tls#old").unwrap();
        let a = crate::subscription_identity::SubscriptionIdentity::parse("AAAAAAAAAA-first".into()).unwrap();
        let b = crate::subscription_identity::SubscriptionIdentity::parse("BBBBBBBBBB-second".into()).unwrap();
        for (id, identity) in [("a", a.clone()), ("b", b.clone())] {
            store.upsert_group(SubscriptionGroup { id: id.into(), name: id.into(), url: format!("{origin}/{id}"), updated_at_ms: 1, nodes: vec![old.clone()], hwid: Some(identity) }).unwrap();
        }
        store.set_selection("b", &old.id).unwrap();
        store.set_refresh_policy(true, 0).unwrap();
        let worker = thread::spawn(move || {
            for (path, identity) in [("/a", a.as_str()), ("/b", b.as_str()), ("/a", "CUSTOM123456789")] {
                let (mut socket, _) = server.accept().unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let mut bytes = Vec::new();
                while !bytes.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    assert_eq!(socket.read(&mut byte).unwrap(), 1);
                    bytes.push(byte[0]);
                }
                let request = String::from_utf8(bytes).unwrap();
                assert!(request.starts_with(&format!("GET {path} HTTP/1.1\r\n")));
                assert!(request.contains(&format!("x-hwid: {identity}\r\n")));
                assert!(request.contains("x-device-os: Windows\r\n"));
                let body = "vless://test@example.com:443?security=tls#new";
                write!(socket, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        let service = Arc::new(RefreshService::new());
        let background_store = Arc::clone(&store);
        let background_service = Arc::clone(&service);
        thread::spawn(move || refresh_due(&background_store, &background_service, true)).join().unwrap();
        assert_eq!(store.selection().group_id.as_deref(), Some("b"));
        store.edit_group("a", "a".into(), store.group_url("a").unwrap(), crate::subscription_identity::SubscriptionIdentity::parse("CUSTOM123456789".into()).unwrap()).unwrap();
        let restarted = Store::open(dir.join("state.json")).unwrap();
        let _guard = service.gate.lock().unwrap();
        service.refresh_locked(&restarted, "a").unwrap();
        assert_eq!(restarted.subscription_source("b").unwrap().1.as_str(), "BBBBBBBBBB-second");
        worker.join().unwrap();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn migrated_mixed_subscriptions_keep_identity_for_startup_and_scheduled_refresh() {
        use crate::subscription::decode_subscription;
        use crate::subscription_identity::SubscriptionIdentity;
        use crate::subscription_integration::{accept, read_request};
        let server = TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", server.local_addr().unwrap());
        let mixed = "vless://test@vless.example:443?security=tls#VLESS\nhy2://auth-secret@hy2.example:8443?obfs=salamander&obfs-password=mask-secret#HY2";
        let hy2 = "hysteria2://second-secret@other.example:443#B";
        let a_nodes = decode_subscription(mixed).unwrap();
        let b_nodes = decode_subscription(hy2).unwrap();
        let b_identity = SubscriptionIdentity::parse("BBBBBBBBBB-existing==".into()).unwrap();
        let dir = std::env::temp_dir().join(format!("dot-mixed-background-{}-{}", std::process::id(), now_ms()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("state.json");
        let legacy = serde_json::json!({
            "groups": [
                {"id": "a", "name": "mixed", "url": format!("{origin}/a"), "updated_at_ms": 1, "nodes": a_nodes},
                {"id": "b", "name": "HY2", "url": format!("{origin}/b"), "updated_at_ms": 1, "nodes": b_nodes, "hwid": b_identity}
            ],
            "selected_group_id": "b", "selected_node_id": b_nodes[0].id,
            "preferences": {"refresh_on_start": true, "refresh_interval_hours": 6}
        });
        std::fs::write(&path, serde_json::to_vec(&legacy).unwrap()).unwrap();
        let store = Arc::new(Store::open(path.clone()).unwrap());
        let a_identity = store.subscription_source("a").unwrap().1;
        let regenerated = SubscriptionIdentity::generate().unwrap();
        let next_identity = regenerated.clone();
        let expected_b = b_identity.clone();
        let worker = thread::spawn(move || {
            for (route, identity, body) in [
                ("/a", a_identity, mixed),
                ("/b", expected_b, hy2),
                ("/a", next_identity, mixed),
            ] {
                let mut socket = accept(&server);
                let request = read_request(&mut socket);
                assert!(request.starts_with(&format!("GET {route} HTTP/1.1\r\n")));
                assert!(request.contains(&format!("x-hwid: {}\r\n", identity.as_str())));
                assert!(request.contains("x-device-os: Windows\r\n"));
                write!(socket, "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        let service = RefreshService::new();
        refresh_due(&store, &service, true);
        assert_eq!(store.selection().group_id.as_deref(), Some("b"));
        assert_eq!(store.groups()[0].nodes.iter().map(|n| n.protocol).collect::<Vec<_>>(), vec!["VLESS", "HY2"]);
        store.edit_group("a", "mixed".into(), store.group_url("a").unwrap(), regenerated.clone()).unwrap();
        store.apply_refresh("a", decode_subscription(mixed).unwrap(), 1).unwrap();
        store.set_refresh_policy(false, 6).unwrap();
        let restarted = Arc::new(Store::open(path).unwrap());
        let scheduled = Arc::clone(&restarted);
        thread::spawn(move || refresh_due(&scheduled, &RefreshService::new(), false)).join().unwrap();
        assert_eq!(restarted.subscription_source("a").unwrap().1, regenerated);
        assert_eq!(restarted.subscription_source("b").unwrap().1, b_identity);
        assert_eq!(restarted.groups()[1].nodes[0].protocol, "HY2");
        assert_eq!(restarted.selection().node_id.as_deref(), Some(b_nodes[0].id.as_str()));
        worker.join().unwrap();
        let _ = std::fs::remove_dir_all(dir);
    }
}
