use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, RwLock,
    },
    time::{SystemTime, UNIX_EPOCH},
};

use tauri::State;
use uuid::Uuid;

use crate::{
    engine::VpnEngine,
    model::{EnginePhase, EngineSnapshot, GroupView, SubscriptionGroup},
    storage::Store,
    subscription::SubscriptionClient,
};

pub struct SharedState {
    pub store: Store,
    pub engine: Arc<Mutex<VpnEngine>>,
    pub snapshot: Arc<RwLock<EngineSnapshot>>,
    pub cancel: Arc<AtomicBool>,
}

#[tauri::command]
pub fn list_groups(state: State<'_, SharedState>) -> Vec<GroupView> {
    state.store.groups()
}

#[tauri::command]
pub async fn add_subscription(
    name: String,
    url: String,
    state: State<'_, SharedState>,
) -> Result<GroupView, String> {
    let url = url.trim().to_string();
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err("subscription URL must use HTTP or HTTPS".into());
    }
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("subscription name is empty".into());
    }

    let fetch_url = url.clone();
    let nodes = tauri::async_runtime::spawn_blocking(move || {
        SubscriptionClient::new()?.fetch(&fetch_url)
    })
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
pub async fn refresh_subscription(
    group_id: String,
    state: State<'_, SharedState>,
) -> Result<GroupView, String> {
    let url = state.store.group_url(&group_id)?;
    let nodes = tauri::async_runtime::spawn_blocking(move || {
        SubscriptionClient::new()?.fetch(&url)
    })
    .await
    .map_err(|e| format!("subscription task failed: {e}"))??;
    state.store.replace_group_nodes(&group_id, nodes, now_ms())
}

#[tauri::command]
pub async fn connect(
    group_id: String,
    node_id: String,
    state: State<'_, SharedState>,
) -> Result<EngineSnapshot, String> {
    let node = state.store.node(&group_id, &node_id)?;
    let current = read_snapshot(&state.snapshot);
    if matches!(
        current.phase,
        EnginePhase::Starting | EnginePhase::Connected | EnginePhase::Stopping
    ) {
        return Err(format!("VPN is currently {}", phase_name(&current.phase)));
    }

    state.cancel.store(false, Ordering::SeqCst);
    write_snapshot(
        &state.snapshot,
        EngineSnapshot {
            phase: EnginePhase::Starting,
            node_name: Some(node.name.clone()),
            message: Some("starting".into()),
        },
    );

    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || {
        let mut engine = engine
            .lock()
            .map_err(|_| "VPN engine lock poisoned".to_string())?;
        engine.start(&node)
    })
    .await
    .map_err(|e| format!("VPN startup task failed: {e}"))?
}

#[tauri::command]
pub fn disconnect(state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let current = read_snapshot(&state.snapshot);
    if current.phase == EnginePhase::Offline {
        return Ok(current);
    }

    state.cancel.store(true, Ordering::SeqCst);
    let stopping = EngineSnapshot {
        phase: EnginePhase::Stopping,
        node_name: current.node_name,
        message: Some("disconnecting".into()),
    };
    write_snapshot(&state.snapshot, stopping.clone());

    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || {
        if let Ok(mut engine) = engine.lock() {
            engine.stop();
        }
    });
    Ok(stopping)
}

#[tauri::command]
pub fn vpn_status(state: State<'_, SharedState>) -> EngineSnapshot {
    if let Ok(mut engine) = state.engine.try_lock() {
        engine.poll();
    }
    read_snapshot(&state.snapshot)
}

pub fn read_snapshot(snapshot: &RwLock<EngineSnapshot>) -> EngineSnapshot {
    match snapshot.read() {
        Ok(value) => value.clone(),
        Err(poisoned) => poisoned.into_inner().clone(),
    }
}

fn write_snapshot(snapshot: &RwLock<EngineSnapshot>, value: EngineSnapshot) {
    match snapshot.write() {
        Ok(mut current) => *current = value,
        Err(poisoned) => *poisoned.into_inner() = value,
    }
}

fn phase_name(phase: &EnginePhase) -> &'static str {
    match phase {
        EnginePhase::Offline => "offline",
        EnginePhase::Starting => "starting",
        EnginePhase::Connected => "connected",
        EnginePhase::Stopping => "stopping",
        EnginePhase::Error => "error",
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
