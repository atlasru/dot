use std::{sync::{atomic::{AtomicBool, Ordering}, Arc, Mutex, RwLock}, time::{SystemTime, UNIX_EPOCH}};
use tauri::State;
use uuid::Uuid;

use crate::{
    engine::VpnEngine,
    model::{AppPreferences, AppTheme, EnginePhase, EngineSnapshot, GroupView, SelectionView, SubscriptionGroup, TrafficSnapshot},
    storage::Store,
    subscription::SubscriptionClient,
};

pub struct SharedState {
    pub store: Store,
    pub engine: Arc<Mutex<VpnEngine>>,
    pub snapshot: Arc<RwLock<EngineSnapshot>>,
    pub traffic: Arc<RwLock<TrafficSnapshot>>,
    pub cancel: Arc<AtomicBool>,
    pub exiting: Arc<AtomicBool>,
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
pub async fn add_subscription(name: String, url: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let url = url.trim().to_string();
    if !(url.starts_with("https://") || url.starts_with("http://")) { return Err("subscription URL must use HTTP or HTTPS".into()); }
    let name = name.trim().to_string();
    if name.is_empty() { return Err("subscription name is empty".into()); }
    let fetch_url = url.clone();
    let nodes = tauri::async_runtime::spawn_blocking(move || SubscriptionClient::new()?.fetch(&fetch_url)).await.map_err(|e| format!("subscription task failed: {e}"))??;
    let first = nodes.first().map(|n| n.id.clone());
    let id = Uuid::new_v5(&Uuid::NAMESPACE_URL, url.as_bytes()).to_string();
    let group = state.store.upsert_group(SubscriptionGroup { id: id.clone(), name, url, updated_at_ms: now_ms(), nodes })?;
    if let Some(node_id) = first { let _ = state.store.set_selection(&id, &node_id); }
    Ok(group)
}

#[tauri::command]
pub async fn refresh_subscription(group_id: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let url = state.store.group_url(&group_id)?;
    let nodes = tauri::async_runtime::spawn_blocking(move || SubscriptionClient::new()?.fetch(&url)).await.map_err(|e| format!("subscription task failed: {e}"))??;
    state.store.replace_group_nodes(&group_id, nodes, now_ms())
}

#[tauri::command]
pub async fn connect(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let node = state.store.node(&group_id, &node_id)?;
    state.store.set_selection(&group_id, &node_id)?;
    let current = read_snapshot(&state.snapshot);
    if matches!(current.phase, EnginePhase::Starting | EnginePhase::Connected | EnginePhase::Stopping) { return Err(format!("VPN is currently {}", phase_name(&current.phase))); }
    state.cancel.store(false, Ordering::SeqCst);
    write_snapshot(&state.snapshot, EngineSnapshot { phase: EnginePhase::Starting, node_name: Some(node.name.clone()), message: Some("starting".into()) });
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

pub fn request_disconnect(state: &SharedState) -> EngineSnapshot {
    let current = read_snapshot(&state.snapshot);
    if current.phase == EnginePhase::Offline { return current; }
    state.cancel.store(true, Ordering::SeqCst);
    let stopping = EngineSnapshot { phase: EnginePhase::Stopping, node_name: current.node_name, message: Some("disconnecting".into()) };
    write_snapshot(&state.snapshot, stopping.clone());
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || { if let Ok(mut engine) = engine.lock() { engine.stop(); } });
    stopping
}

pub fn start_selected_background(state: &SharedState) -> Result<(), String> {
    let node = state.store.selected_node()?;
    let current = read_snapshot(&state.snapshot);
    if matches!(current.phase, EnginePhase::Starting | EnginePhase::Connected | EnginePhase::Stopping) { return Ok(()); }
    state.cancel.store(false, Ordering::SeqCst);
    write_snapshot(&state.snapshot, EngineSnapshot { phase: EnginePhase::Starting, node_name: Some(node.name.clone()), message: Some("starting".into()) });
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || { if let Ok(mut engine) = engine.lock() { let _ = engine.start(&node); } });
    Ok(())
}

pub fn read_snapshot(value: &RwLock<EngineSnapshot>) -> EngineSnapshot { match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() } }
fn write_snapshot(value: &RwLock<EngineSnapshot>, next: EngineSnapshot) { match value.write() { Ok(mut v) => *v = next, Err(p) => *p.into_inner() = next } }
fn read_traffic(value: &RwLock<TrafficSnapshot>) -> TrafficSnapshot { match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() } }
fn phase_name(v: &EnginePhase) -> &'static str { match v { EnginePhase::Offline => "offline", EnginePhase::Starting => "starting", EnginePhase::Connected => "connected", EnginePhase::Stopping => "stopping", EnginePhase::Error => "error" } }
fn now_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }
