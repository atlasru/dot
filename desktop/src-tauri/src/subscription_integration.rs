use std::{
    fs,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    path::PathBuf,
    sync::atomic::{AtomicUsize, Ordering},
    thread,
    time::{Duration, Instant},
};

use base64::{engine::general_purpose::STANDARD, Engine as _};

use crate::{
    model::{AppTheme, NodeLatency, NodeSortMode, PersistedState, ProxyConfig, SubscriptionGroup},
    storage::Store,
    subscription::{decode_subscription, SubscriptionClient},
    subscription_identity::SubscriptionIdentity,
};

const VLESS: &str = "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls&type=ws&path=%2Fprivate#VLESS";
const HY2: &str = "hysteria2://user%3Asecret@hy2.example:8443?sni=tls.example&obfs=salamander&obfs-password=mask-secret&packetSize=512-1200#HY2";
const CUSTOM: &str = "ABC123-existing-Value==";

struct Fixture(PathBuf);

impl Fixture {
    fn new() -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "dot-integrated-state-{}-{}-{}",
            std::process::id(),
            crate::refresh::now_ms(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> PathBuf {
        self.0.join("state.json")
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn group(id: &str, body: &str, hwid: Option<SubscriptionIdentity>) -> SubscriptionGroup {
    SubscriptionGroup {
        id: id.into(),
        name: id.into(),
        url: format!("https://provider.example/sub/{id}?token=secret"),
        updated_at_ms: 1234,
        nodes: decode_subscription(body).unwrap(),
        hwid,
    }
}

#[test]
fn v040_mixed_migration_preserves_credentials_metadata_selection_and_existing_hwid() {
    let f = Fixture::new();
    let mixed = format!("{VLESS}\n{HY2}");
    let mut state = PersistedState::default();
    state.groups = vec![
        group("legacy", &mixed, None),
        group(
            "existing",
            HY2,
            Some(SubscriptionIdentity::parse(CUSTOM.into()).unwrap()),
        ),
    ];
    let selected = state.groups[1].nodes[0].id.clone();
    state.selected_group_id = Some("existing".into());
    state.selected_node_id = Some(selected.clone());
    state.favorites.insert(selected.clone());
    state.latencies.insert(
        selected.clone(),
        NodeLatency {
            node_id: selected.clone(),
            latency_ms: Some(42),
            failed: false,
            tested_at_ms: 12,
        },
    );
    state
        .sort_modes
        .insert("legacy".into(), NodeSortMode::Delay);
    state.preferences.theme = AppTheme::Matrix;
    state.preferences.close_to_tray = false;
    state.preferences.refresh_on_start = true;
    state.preferences.refresh_interval_hours = 6;
    let mut original = serde_json::to_value(&state).unwrap();
    original["groups"][0]
        .as_object_mut()
        .unwrap()
        .remove("hwid");
    fs::write(f.path(), serde_json::to_vec(&original).unwrap()).unwrap();

    let store = Store::open(f.path()).unwrap();
    let generated = store.subscription_source("legacy").unwrap().1;
    generated.validate().unwrap();
    assert_eq!(
        store.subscription_source("existing").unwrap().1.as_str(),
        CUSTOM
    );
    assert_ne!(generated.as_str(), CUSTOM);
    let nodes = store.group_nodes("legacy").unwrap();
    assert_eq!(
        nodes.iter().map(|n| n.protocol()).collect::<Vec<_>>(),
        vec!["VLESS", "HY2"]
    );
    match &nodes[1].proxy_config {
        ProxyConfig::Hysteria2(config) => {
            assert_eq!(config.auth, "user:secret");
            assert_eq!(config.salamander_password.as_deref(), Some("mask-secret"));
        }
        _ => panic!("HY2 node was lost"),
    }
    let bytes = fs::read(f.path()).unwrap();
    let mut migrated: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    migrated["groups"][0]
        .as_object_mut()
        .unwrap()
        .remove("hwid");
    assert_eq!(migrated, original);
    let restarted = Store::open(f.path()).unwrap();
    assert_eq!(
        restarted.subscription_source("legacy").unwrap().1,
        generated
    );
    assert_eq!(
        restarted
            .subscription_source("existing")
            .unwrap()
            .1
            .as_str(),
        CUSTOM
    );
    assert_eq!(
        restarted.selection().node_id.as_deref(),
        Some(selected.as_str())
    );
    assert_eq!(fs::read(f.path()).unwrap(), bytes);
}

#[test]
fn v030_and_hwid_v030_states_default_to_vless_without_rotating_existing_identity() {
    for existing in [false, true] {
        let f = Fixture::new();
        let identity = existing.then(|| SubscriptionIdentity::parse(CUSTOM.into()).unwrap());
        let mut state = PersistedState::default();
        state.groups.push(group("old", VLESS, identity));
        let id = state.groups[0].nodes[0].id.clone();
        state.selected_group_id = Some("old".into());
        state.selected_node_id = Some(id.clone());
        let mut legacy = serde_json::to_value(&state).unwrap();
        legacy["groups"][0]["nodes"][0]
            .as_object_mut()
            .unwrap()
            .remove("proxy_config");
        if !existing {
            legacy["groups"][0].as_object_mut().unwrap().remove("hwid");
        }
        fs::write(f.path(), serde_json::to_vec(&legacy).unwrap()).unwrap();
        let store = Store::open(f.path()).unwrap();
        let hwid = store.subscription_source("old").unwrap().1;
        if existing {
            assert_eq!(hwid.as_str(), CUSTOM);
        }
        let nodes = store.group_nodes("old").unwrap();
        assert_eq!(nodes[0].protocol(), "VLESS");
        assert_eq!(nodes[0].raw_uri, VLESS);
        assert_eq!(nodes[0].user_id, "11111111-1111-4111-8111-111111111111");
        assert_eq!(store.selection().node_id, Some(id));
        assert_eq!(store.group_url("old").unwrap(), state.groups[0].url);
        assert_eq!(
            Store::open(f.path())
                .unwrap()
                .subscription_source("old")
                .unwrap()
                .1,
            hwid
        );
    }
}

#[test]
fn mixed_groups_keep_custom_and_regenerated_identity_independent_after_restart() {
    let f = Fixture::new();
    let store = Store::open(f.path()).unwrap();
    let mixed = format!("{VLESS}\n{HY2}");
    let a = store.upsert_group(group("a", &mixed, None)).unwrap();
    let b = store.upsert_group(group("b", &mixed, None)).unwrap();
    assert_ne!(a.hwid, b.hwid);
    store.set_selection("b", &b.nodes[1].id).unwrap();
    store
        .edit_group(
            "a",
            "a".into(),
            store.group_url("a").unwrap(),
            SubscriptionIdentity::parse(CUSTOM.into()).unwrap(),
        )
        .unwrap();
    assert_eq!(
        Store::open(f.path())
            .unwrap()
            .subscription_source("a")
            .unwrap()
            .1
            .as_str(),
        CUSTOM
    );
    let regenerated = SubscriptionIdentity::generate().unwrap();
    store
        .edit_group(
            "a",
            "a".into(),
            store.group_url("a").unwrap(),
            regenerated.clone(),
        )
        .unwrap();
    store
        .apply_refresh("a", decode_subscription(&mixed).unwrap(), 5000)
        .unwrap();
    let restarted = Store::open(f.path()).unwrap();
    assert_eq!(restarted.subscription_source("a").unwrap().1, regenerated);
    assert_eq!(restarted.groups()[1].hwid, b.hwid);
    assert_eq!(restarted.selection().group_id.as_deref(), Some("b"));
    assert_eq!(
        restarted
            .group_nodes("a")
            .unwrap()
            .iter()
            .map(|n| n.protocol())
            .collect::<Vec<_>>(),
        vec!["VLESS", "HY2"]
    );
}

pub(crate) fn accept(listener: &TcpListener) -> TcpStream {
    listener.set_nonblocking(true).unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match listener.accept() {
            Ok((socket, _)) => return socket,
            Err(error)
                if error.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < deadline =>
            {
                thread::sleep(Duration::from_millis(10));
            }
            Err(error) => panic!("subscription request did not arrive: {error}"),
        }
    }
}

pub(crate) fn read_request(socket: &mut TcpStream) -> String {
    socket
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    let mut bytes = Vec::new();
    while !bytes.ends_with(b"\r\n\r\n") {
        let mut byte = [0];
        assert_eq!(socket.read(&mut byte).unwrap(), 1);
        bytes.push(byte[0]);
    }
    String::from_utf8(bytes).unwrap()
}

#[test]
fn vless_hy2_and_mixed_downloads_keep_import_identity_across_redirects_and_reload() {
    let origin = TcpListener::bind("127.0.0.1:0").unwrap();
    let cdn = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", origin.local_addr().unwrap());
    let destination = format!("http://{}", cdn.local_addr().unwrap());
    let variants = vec![
        VLESS.to_string(),
        HY2.to_string(),
        STANDARD.encode(format!("{VLESS}\n{HY2}")),
    ];
    let first = thread::spawn(move || {
        for index in 0..3 {
            for location in [
                format!("/same/{index}"),
                format!("{destination}/body/{index}"),
            ] {
                let mut socket = accept(&origin);
                let request = read_request(&mut socket);
                assert!(request.contains(&format!("x-hwid: CUSTOM123456789-{index}\r\n")));
                assert!(request.contains("x-device-os: Windows\r\n"));
                assert!(request.contains("x-ver-os: "));
                assert!(request.contains("x-device-model: dot Windows\r\n"));
                assert!(request.contains("user-agent: dot-desktop/"));
                write!(socket, "HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").unwrap();
            }
        }
    });
    let second = thread::spawn(move || {
        for body in variants {
            let mut socket = accept(&cdn);
            let request = read_request(&mut socket);
            for header in ["x-hwid:", "x-device-os:", "x-ver-os:", "x-device-model:"] {
                assert!(!request.contains(header));
            }
            assert!(request.contains("accept: */*\r\n"));
            write!(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
        }
    });
    let f = Fixture::new();
    let store = Store::open(f.path()).unwrap();
    let client = SubscriptionClient::new().unwrap();
    for (index, expected) in [vec!["VLESS"], vec!["HY2"], vec!["VLESS", "HY2"]]
        .into_iter()
        .enumerate()
    {
        let hwid = SubscriptionIdentity::parse(format!("CUSTOM123456789-{index}")).unwrap();
        let source = format!("{url}/import/{index}");
        let nodes = client.fetch(&source, &hwid).unwrap();
        assert_eq!(
            nodes.iter().map(|n| n.protocol()).collect::<Vec<_>>(),
            expected
        );
        let id = format!("import-{index}");
        store
            .upsert_group(SubscriptionGroup {
                id: id.clone(),
                name: id.clone(),
                url: source,
                updated_at_ms: 1,
                nodes,
                hwid: Some(hwid.clone()),
            })
            .unwrap();
        let restarted = Store::open(f.path()).unwrap();
        assert_eq!(restarted.subscription_source(&id).unwrap().1, hwid);
        assert_eq!(
            restarted
                .group_nodes(&id)
                .unwrap()
                .iter()
                .map(|n| n.protocol())
                .collect::<Vec<_>>(),
            expected
        );
    }
    first.join().unwrap();
    second.join().unwrap();
}
