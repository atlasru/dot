use std::{sync::Mutex, time::{SystemTime, UNIX_EPOCH}};

use tauri::State;
use uuid::Uuid;

use crate::{
    engine::VpnEngine,
    model::{EngineSnapshot, GroupView, SubscriptionGroup},
    storage::Store,
    subscription::SubscriptionClient,
};

pub struct SharedState {
    pub store: Store,
    pub engine: Mutex<VpnEngine>,
}

#[tauri::command]
pub fn list_groups(state: State<'_, SharedState>) -> Vec<GroupView> {
    state.store.groups()
}

#[tauri::command]
pub async fn add_subscription(name: String, url: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let url = url.trim().to_string();
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err("subscription URL must use HTTP or HTTPS".into());
    }
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("subscription name is empty".into());
    }

    let fetch_url = url.clone();
    let nodes = tauri::async_runtime::spawn_blocking(move || SubscriptionClient::new()?.fetch(&fetch_url))
        .await
        .map_err(|e| format!("subscription task failed: {e}"))??;

    let id = Uuid::new_v5(&Uuid::NAMESPACE_URL, url.as_bytes()).to_string();
    state.store.upsert_group(SubscriptionGroup {
        id,
        name,
        url,
        updated_at_ms: now_ms(),
        nodes,
    })
}

#[tauri::command]
pub async fn refresh_subscription(group_id: String, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let url = state.store.group_url(&group_id)?;
    let nodes = tauri::async_runtime::spawn_blocking(move || SubscriptionClient::new()?.fetch(&url))
        .await
        .map_err(|e| format!("subscription task failed: {e}"))??;
    state.store.replace_group_nodes(&group_id, nodes, now_ms())
}

#[tauri::command]
pub async fn connect(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let node = state.store.node(&group_id, &node_id)?;
    let engine = &state.engine;
    let mut engine = engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?;
    engine.start(&node)
}

#[tauri::command]
pub fn disconnect(state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let mut engine = state.engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?;
    Ok(engine.stop())
}

#[tauri::command]
pub fn vpn_status(state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let mut engine = state.engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?;
    Ok(engine.status())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
