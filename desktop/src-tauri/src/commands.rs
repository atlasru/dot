use std::{path::PathBuf, sync::{atomic::{AtomicBool, Ordering}, Arc, Mutex, RwLock}, time::{SystemTime, UNIX_EPOCH}};
use tauri::State;
use uuid::Uuid;

use crate::{
    engine::VpnEngine,
    model::{
        AppPreferences, AppTheme, EnginePhase, EngineSnapshot, GroupUrlTestResult, GroupView,
        NodeSortMode, SelectionView, SubscriptionGroup, SubscriptionRefreshResult, TrafficSnapshot,
        UrlTestResult,
    },
    storage::Store,
    subscription::SubscriptionClient,
    url_test,
};

pub struct SharedState {
    pub store: Arc<Store>,
    pub operation: Arc<AtomicBool>,
    pub refresh: Arc<crate::refresh::RefreshService>,
    pub engine: Arc<Mutex<VpnEngine>>,
    pub snapshot: Arc<RwLock<EngineSnapshot>>,
    pub traffic: Arc<RwLock<TrafficSnapshot>>,
    pub cancel: Arc<AtomicBool>,
    pub exiting: Arc<AtomicBool>,
    pub runtime_source: PathBuf,
    pub url_test_dir: PathBuf,
}

#[tauri::command]
pub fn list_groups(state: State<'_, SharedState>) -> Vec<GroupView> { state.store.groups() }
#[tauri::command]
pub fn selection(state: State<'_, SharedState>) -> SelectionView { state.store.selection() }
#[tauri::command]
pub fn preferences(state: State<'_, SharedState>) -> AppPreferences { state.store.preferences() }
#[tauri::command]
pub fn traffic_status(state: State<'_, SharedState>) -> TrafficSnapshot { read_traffic(&state.traffic) }

#[tauri::command]
pub fn select_node(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<SelectionView, String> {
    state.store.set_selection(&group_id, &node_id)
}

#[tauri::command]
pub async fn switch_node(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let _operation = crate::operation::Operation::acquire(&state.operation)?;
    let node = state.store.node(&group_id, &node_id)?;
    state.store.set_selection(&group_id, &node_id)?;

    let current = read_snapshot(&state.snapshot);
    if current.phase == EnginePhase::Offline || current.phase == EnginePhase::Error {
        return Ok(current);
    }

    state.cancel.store(true, Ordering::SeqCst);
    write_snapshot(&state.snapshot, EngineSnapshot {
        phase: EnginePhase::Stopping,
        node_name: current.node_name, node_id: current.node_id,
        message: Some("switching node".into()),
    });

    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || {
        let mut engine = engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?;
        engine.stop();
        engine.start(&node)
    })
    .await
    .map_err(|e| format!("VPN node switch task failed: {e}"))?
}

#[tauri::command]
pub fn set_theme(theme: String, state: State<'_, SharedState>) -> Result<AppPreferences, String> {
    let theme = match theme.as_str() {
        "amoled" => AppTheme::Amoled,
        "graphite" => AppTheme::Graphite,
        "matrix" => AppTheme::Matrix,
        _ => return Err("unknown theme".into()),
    };
    state.store.set_theme(theme)
}

#[tauri::command]
pub fn set_close_to_tray(enabled: bool, state: State<'_, SharedState>) -> Result<AppPreferences, String> {
    state.store.set_close_to_tray(enabled)
}

#[tauri::command]
pub fn set_node_sort(group_id: String, mode: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let mode = match mode.as_str() {
        "origin" => NodeSortMode::Origin,
        "delay" => NodeSortMode::Delay,
        "name" => NodeSortMode::Name,
        _ => return Err("unknown node sort mode".into()),
    };
    state.store.set_sort_mode(&group_id, mode)
}

#[tauri::command]
pub async fn add_subscription(name: String, url: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let url = validate_url(&url)?;
    let name = name.trim().to_string();
    if name.is_empty() { return Err("subscription name is empty".into()); }
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        if store.groups().iter().any(|g| store.group_url(&g.id).ok().as_deref() == Some(&url)) {
            return Err("this subscription URL already exists".into());
        }
        let nodes = SubscriptionClient::new()?.fetch(&url)?;
        let id = Uuid::new_v5(&Uuid::NAMESPACE_URL, format!("{url}:{}", now_ms()).as_bytes()).to_string();
        store.upsert_group(SubscriptionGroup { id, name, url, updated_at_ms: now_ms(), nodes })
    }).await.map_err(|_| "subscription task failed".to_string())?
}

#[tauri::command]
pub async fn refresh_subscription(group_id: String, state: State<'_, SharedState>) -> Result<SubscriptionRefreshResult, String> {
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        service.refresh_locked(&store, &group_id)
    }).await.map_err(|_| "subscription task failed".to_string())?
}

#[tauri::command]
pub fn refresh_notices(state: State<'_, SharedState>) -> Vec<crate::refresh::RefreshNotice> { state.refresh.notices(&state.store) }

#[tauri::command]
pub fn set_refresh_policy(on_start: bool, hours: u32, state: State<'_, SharedState>) -> Result<AppPreferences, String> {
    state.store.set_refresh_policy(on_start, hours)
}

#[tauri::command]
pub fn set_favorite(node_id: String, enabled: bool, state: State<'_, SharedState>) -> Result<(), String> { state.store.set_favorite(&node_id, enabled) }

#[tauri::command]
pub fn subscription_url(group_id: String, state: State<'_, SharedState>) -> Result<String, String> { state.store.group_url(&group_id) }

#[tauri::command]
pub async fn edit_subscription(group_id: String, name: String, url: String, state: State<'_, SharedState>) -> Result<(), String> {
    let name = name.trim().to_string();
    if name.is_empty() { return Err("subscription name is empty".into()); }
    let url = if url.trim().is_empty() { String::new() } else { validate_url(&url)? };
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        // A changed URL is fetched and parsed before any persisted state is replaced.
        let old_url = store.group_url(&group_id)?;
        if old_url != url && !url.is_empty() {
            let nodes = SubscriptionClient::new()?.fetch(&url)?;
            store.apply_source_refresh(&group_id, nodes, now_ms(), name, url)?;
            Ok(())
        } else {
            store.edit_group(&group_id, name, url)
        }
    }).await.map_err(|_| "subscription task failed".to_string())?
}

#[tauri::command]
pub async fn remove_subscription(group_id: String, state: State<'_, SharedState>) -> Result<(), String> {
    let operation = crate::operation::Operation::acquire(&state.operation)?;
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    let engine = Arc::clone(&state.engine);
    let snapshot = Arc::clone(&state.snapshot);
    tauri::async_runtime::spawn_blocking(move || {
        let _operation = operation;
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        let _engine = engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?;
        let phase = read_snapshot(&snapshot).phase;
        if matches!(phase, EnginePhase::Starting | EnginePhase::Connected | EnginePhase::Stopping) {
            return Err("disconnect before deleting a subscription".into());
        }
        store.remove_group(&group_id)
    }).await.map_err(|_| "subscription task failed".to_string())?
}

#[tauri::command]
pub async fn import_nodes(name: String, text: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let name = name.trim().to_string();
    if name.is_empty() { return Err("group name is empty".into()); }
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        let nodes = crate::subscription::decode_subscription(&text)?;
        let id = Uuid::new_v5(&Uuid::NAMESPACE_URL, format!("local:{name}:{}", now_ms()).as_bytes()).to_string();
        store.upsert_group(SubscriptionGroup { id, name, url: String::new(), updated_at_ms: now_ms(), nodes })
    }).await.map_err(|_| "import task failed".to_string())?
}

fn validate_url(raw: &str) -> Result<String, String> {
    let url = url::Url::parse(raw.trim()).map_err(|_| "invalid subscription URL".to_string())?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err("subscription URL must use HTTP or HTTPS".into());
    }
    Ok(url.to_string())
}

#[tauri::command]
pub async fn connect(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let _operation = crate::operation::Operation::acquire(&state.operation)?;
    let node = state.store.node(&group_id, &node_id)?;
    state.store.set_selection(&group_id, &node_id)?;
    let current = read_snapshot(&state.snapshot);
    if matches!(current.phase, EnginePhase::Starting | EnginePhase::Connected | EnginePhase::Stopping) { return Err(format!("VPN is currently {}", phase_name(&current.phase))); }
    state.cancel.store(false, Ordering::SeqCst);
    write_snapshot(&state.snapshot, EngineSnapshot { phase: EnginePhase::Starting, node_name: Some(node.name.clone()), node_id: Some(node.id.clone()), message: Some("starting".into()) });
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?.start(&node)).await.map_err(|e| format!("VPN startup task failed: {e}"))?
}

#[tauri::command]
pub fn disconnect(state: State<'_, SharedState>) -> EngineSnapshot { request_disconnect(&state) }

#[tauri::command]
pub fn vpn_status(state: State<'_, SharedState>) -> EngineSnapshot {
    if let Ok(mut engine) = state.engine.try_lock() { engine.poll(); }
    read_snapshot(&state.snapshot)
}

#[tauri::command]
pub async fn url_test(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<UrlTestResult, String> {
    let _operation = crate::operation::Operation::acquire(&state.operation)?;
    let node = state.store.node(&group_id, &node_id)?;
    let current = read_snapshot(&state.snapshot);
    if matches!(current.phase, EnginePhase::Starting | EnginePhase::Stopping) {
        return Err("wait for the VPN state change to finish before URL testing".into());
    }

    let active_tunnel = current.phase == EnginePhase::Connected && current.node_id.as_deref() == Some(node.id.as_str());
    if current.phase == EnginePhase::Connected && !active_tunnel {
        return Err("switch to this node before URL testing while VPN is connected".into());
    }

    let tested_node_id = node.id.clone();
    let result = if active_tunnel {
        tauri::async_runtime::spawn_blocking(url_test::test_active_connection)
            .await
            .map_err(|e| format!("active URL test task failed: {e}"))?
    } else {
        let runtime_source = state.runtime_source.clone();
        let work_dir = state.url_test_dir.clone();
        tauri::async_runtime::spawn_blocking(move || url_test::test_node(&runtime_source, &work_dir, &node))
            .await
            .map_err(|e| format!("node URL test task failed: {e}"))?
    };

    match result {
        Ok(latency_ms) => {
            state.store.record_latency(&tested_node_id, Some(latency_ms), false, now_ms())?;
            Ok(UrlTestResult { node_id: tested_node_id, latency_ms, active_tunnel })
        }
        Err(error) => {
            state.store.record_latency(&tested_node_id, None, true, now_ms())?;
            Err(error)
        }
    }
}

#[tauri::command]
pub async fn url_test_group(group_id: String, state: State<'_, SharedState>) -> Result<GroupUrlTestResult, String> {
    let _operation = crate::operation::Operation::acquire(&state.operation)?;
    let current = read_snapshot(&state.snapshot);
    if matches!(current.phase, EnginePhase::Starting | EnginePhase::Stopping) {
        return Err("wait for the VPN state change to finish before testing the group".into());
    }
    if current.phase == EnginePhase::Connected {
        return Err("disconnect before testing all nodes in a subscription".into());
    }

    let nodes = state.store.group_nodes(&group_id)?;
    if nodes.is_empty() { return Err("subscription contains no nodes".into()); }
    let runtime_source = state.runtime_source.clone();
    let work_dir = state.url_test_dir.clone();

    let outcomes = tauri::async_runtime::spawn_blocking(move || {
        let mut outcomes = Vec::with_capacity(nodes.len());
        for batch in nodes.chunks(6) {
            let handles: Vec<_> = batch.iter().cloned().map(|node| {
                let runtime_source = runtime_source.clone();
                let work_dir = work_dir.clone();
                std::thread::spawn(move || {
                    let node_id = node.id.clone();
                    let result = url_test::test_node(&runtime_source, &work_dir, &node);
                    (node_id, result)
                })
            }).collect();
            for handle in handles {
                match handle.join() {
                    Ok(outcome) => outcomes.push(outcome),
                    Err(_) => outcomes.push((String::new(), Err("URL test worker panicked".into()))),
                }
            }
        }
        outcomes
    }).await.map_err(|e| format!("group URL test task failed: {e}"))?;

    let mut results = Vec::new();
    let mut failed_node_ids = Vec::new();
    for (node_id, outcome) in outcomes {
        if node_id.is_empty() { continue; }
        match outcome {
            Ok(latency_ms) => {
                state.store.record_latency(&node_id, Some(latency_ms), false, now_ms())?;
                results.push(UrlTestResult { node_id, latency_ms, active_tunnel: false });
            }
            Err(_) => {
                state.store.record_latency(&node_id, None, true, now_ms())?;
                failed_node_ids.push(node_id);
            }
        }
    }

    Ok(GroupUrlTestResult {
        group_id,
        succeeded: results.len(),
        failed: failed_node_ids.len(),
        results,
        failed_node_ids,
    })
}

pub fn request_disconnect(state: &SharedState) -> EngineSnapshot {
    let current = read_snapshot(&state.snapshot);
    if current.phase == EnginePhase::Offline { return current; }
    state.cancel.store(true, Ordering::SeqCst);
    let stopping = EngineSnapshot { phase: EnginePhase::Stopping, node_name: current.node_name, node_id: current.node_id, message: Some("disconnecting".into()) };
    write_snapshot(&state.snapshot, stopping.clone());
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || { if let Ok(mut engine) = engine.lock() { engine.stop(); } });
    stopping
}

pub fn start_selected_background(state: &SharedState) -> Result<(), String> {
    let operation = crate::operation::Operation::acquire(&state.operation)?;
    let node = state.store.selected_node()?;
    let current = read_snapshot(&state.snapshot);
    if matches!(current.phase, EnginePhase::Starting | EnginePhase::Connected | EnginePhase::Stopping) { return Ok(()); }
    state.cancel.store(false, Ordering::SeqCst);
    write_snapshot(&state.snapshot, EngineSnapshot { phase: EnginePhase::Starting, node_name: Some(node.name.clone()), node_id: Some(node.id.clone()), message: Some("starting".into()) });
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || { let _operation = operation; if let Ok(mut engine) = engine.lock() { let _ = engine.start(&node); } });
    Ok(())
}

pub fn read_snapshot(value: &RwLock<EngineSnapshot>) -> EngineSnapshot { match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() } }
fn write_snapshot(value: &RwLock<EngineSnapshot>, next: EngineSnapshot) { match value.write() { Ok(mut v) => *v = next, Err(p) => *p.into_inner() = next } }
fn read_traffic(value: &RwLock<TrafficSnapshot>) -> TrafficSnapshot { match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() } }
fn phase_name(v: &EnginePhase) -> &'static str { match v { EnginePhase::Offline => "offline", EnginePhase::Starting => "starting", EnginePhase::Connected => "connected", EnginePhase::Stopping => "stopping", EnginePhase::Error => "error" } }
fn now_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }
