use std::{path::PathBuf, sync::{atomic::{AtomicBool, Ordering}, Arc, Mutex, RwLock}, time::{SystemTime, UNIX_EPOCH}};
use tauri::State;
use uuid::Uuid;

use crate::{
    diagnostic_batch::{BatchStatus, Diagnostics},
    engine::VpnEngine,
    model::{
        AppPreferences, AppTheme, EnginePhase, EngineSnapshot, GroupView, ProxyNode,
        NodeSortMode, SelectionView, SubscriptionGroup, SubscriptionRefreshResult, TrafficSnapshot,
        UrlTestResult,
    },
    storage::Store,
    subscription::SubscriptionClient,
    subscription_identity::SubscriptionIdentity,
    url_test,
};

pub struct SharedState {
    pub store: Arc<Store>,
    pub diagnostics: Arc<Diagnostics>,
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
pub fn list_groups(state: State<'_, SharedState>) -> Vec<GroupView> {
    state.store.groups_with_results(&state.diagnostics.live_results())
}
#[tauri::command]
pub fn selection(state: State<'_, SharedState>) -> SelectionView { state.store.selection() }
#[tauri::command]
pub fn preferences(state: State<'_, SharedState>) -> AppPreferences { state.store.preferences() }
#[tauri::command]
pub fn traffic_status(state: State<'_, SharedState>) -> TrafficSnapshot { read_traffic(&state.traffic) }

#[tauri::command]
pub fn select_node(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<SelectionView, String> {
    let _operation=crate::operation::Operation::acquire(&state.operation)?;
    state.store.set_manual_selection(&group_id, &node_id)
}

#[tauri::command]
pub async fn switch_node(group_id: String, node_id: String, state: State<'_, SharedState>) -> Result<EngineSnapshot, String> {
    let operation = crate::operation::Operation::acquire(&state.operation)?;
    let node = state.store.node(&group_id, &node_id)?;
    state.store.set_manual_selection(&group_id, &node_id)?;

    {
        let mut current = state.snapshot.write().unwrap_or_else(|error| error.into_inner());
        match current.phase {
            EnginePhase::Offline | EnginePhase::Error => return Ok(current.clone()),
            EnginePhase::Connected => *current = queued_start(&node),
            _ => return Err("wait for the VPN to finish changing state".into()),
        }
    }

    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || {
        let _operation = operation;
        engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?.start(&node)
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
pub async fn add_subscription(name: String, url: String, hwid: Option<String>, state: State<'_, SharedState>) -> Result<GroupView, String> {
    let url = validate_url(&url)?;
    let name = name.trim().to_string();
    if name.is_empty() { return Err("subscription name is empty".into()); }
    let hwid = hwid.map(SubscriptionIdentity::parse).unwrap_or_else(SubscriptionIdentity::generate)?;
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        if store.groups().iter().any(|g| store.group_url(&g.id).ok().as_deref() == Some(&url)) {
            return Err("this subscription URL already exists".into());
        }
        let nodes = SubscriptionClient::with_settings(&store.preferences().advanced)?.fetch(&url, &hwid)?;
        let id = Uuid::new_v5(&Uuid::NAMESPACE_URL, format!("{url}:{}", now_ms()).as_bytes()).to_string();
        store.upsert_group(SubscriptionGroup { id, name, url, updated_at_ms: now_ms(), nodes, hwid: Some(hwid) })
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
pub fn generate_subscription_hwid() -> Result<String, String> { Ok(SubscriptionIdentity::generate()?.as_str().to_string()) }

#[tauri::command]
pub async fn edit_subscription(group_id: String, name: String, url: String, hwid: Option<String>, state: State<'_, SharedState>) -> Result<(), String> {
    let name = name.trim().to_string();
    if name.is_empty() { return Err("subscription name is empty".into()); }
    let url = if url.trim().is_empty() { String::new() } else { validate_url(&url)? };
    let hwid = hwid.map(SubscriptionIdentity::parse).transpose()?;
    let store = Arc::clone(&state.store);
    let service = Arc::clone(&state.refresh);
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = service.gate.lock().map_err(|_| "subscription lock poisoned".to_string())?;
        // A changed URL is fetched and parsed before any persisted state is replaced.
        let (old_url, old_hwid) = store.subscription_source(&group_id)?;
        let hwid = hwid.unwrap_or(old_hwid);
        if old_url != url && !url.is_empty() {
            let nodes = SubscriptionClient::with_settings(&store.preferences().advanced)?.fetch(&url, &hwid)?;
            let result = store.apply_source_refresh(&group_id, nodes, now_ms(), name, url, hwid);
            service.record(&store, &group_id, &result);
            result?;
            Ok(())
        } else {
            store.edit_group(&group_id, name, url, hwid)
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
        store.upsert_group(SubscriptionGroup { id, name, url: String::new(), updated_at_ms: now_ms(), nodes, hwid: None })
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
    let operation = crate::operation::Operation::acquire(&state.operation)?;
    let node_id = if state.store.preferences().advanced.auto_node { state.store.auto_selection(&group_id)?.node_id.ok_or("AUTO found no node")? } else { node_id };
    let node = state.store.node(&group_id, &node_id)?;
    state.store.set_selection(&group_id, &node_id)?;
    prepare_start(&state, &node).map_err(|phase| format!("VPN is currently {}", phase_name(&phase)))?;
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || {
        let _operation = operation;
        engine.lock().map_err(|_| "VPN engine lock poisoned".to_string())?.start(&node)
    }).await.map_err(|e| format!("VPN startup task failed: {e}"))?
}

#[tauri::command]
pub fn disconnect(state: State<'_, SharedState>) -> EngineSnapshot { request_disconnect(&state) }

#[tauri::command]
pub fn vpn_status(state: State<'_, SharedState>) -> EngineSnapshot {
    if let Ok(mut engine) = state.engine.try_lock() { engine.poll(); }
    read_snapshot(&state.snapshot)
}

#[tauri::command]
pub fn set_advanced(settings: crate::settings::AdvancedSettings, base: Option<crate::settings::AdvancedSettings>, state: State<'_, SharedState>) -> Result<AppPreferences,String> {
    let _operation=crate::operation::Operation::acquire(&state.operation)?;
    state.diagnostics.update_cache(|| state.store.patch_advanced(base.as_ref(),settings))
}
#[tauri::command]
pub fn clear_diagnostics(state: State<'_, SharedState>) -> Result<(),String> {
    state.diagnostics.update_cache(|| state.store.clear_diagnostics())
}
#[tauri::command]
pub fn cancel_diagnostics(state: State<'_, SharedState>) { state.diagnostics.cancel(false); }
#[tauri::command]
pub fn diagnostics_status(state: State<'_, SharedState>) -> Option<BatchStatus> { state.diagnostics.status() }
#[tauri::command]
pub fn auto_select(group_id:String, state:State<'_,SharedState>) -> Result<SelectionView,String> {
    let _operation=crate::operation::Operation::acquire(&state.operation)?;
    if !matches!(read_snapshot(&state.snapshot).phase,EnginePhase::Offline|EnginePhase::Error) { return Err("disconnect before AUTO selection".into()); }
    let selected=state.store.auto_selection(&group_id)?;
    state.store.set_auto_node(true)?;
    Ok(selected)
}
#[tauri::command]
pub async fn url_test(group_id: String, node_id: String, connection: Option<bool>, state: State<'_, SharedState>) -> Result<UrlTestResult, String> {
    let node = state.store.node(&group_id, &node_id)?;
    let operation = if connection.unwrap_or(false) {
        Some(crate::operation::Operation::acquire(&state.operation)?)
    } else { None };
    let current = read_snapshot(&state.snapshot);
    let active = operation.is_some() && current.phase == EnginePhase::Connected;
    let run = state.diagnostics.begin(active)?;
    let settings = state.store.preferences().advanced.ping;
    if active && (current.node_id.as_deref() != Some(node.id.as_str()) || !settings.method.proxy()
        || settings.url != crate::settings::PingSettings::default().url) {
        return Err("active tunnel diagnostics require the connected node and the default Via Proxy endpoint".into());
    }
    let store = Arc::clone(&state.store);
    let runtime = state.runtime_source.clone();
    let dir = state.url_test_dir.clone();
    tauri::async_runtime::spawn_blocking(move || {
        // Only a measurement through the active tunnel leases the connection operation.
        let _operation = operation.filter(|_| active);
        let cancel = run.cancellation();
        let result = if active { url_test::test_active_connection(&settings, cancel) }
            else { url_test::test_node(&runtime, &dir, &node, &settings, cancel) };
        crate::diagnostics::cancelled(cancel)?;
        if !active { store.record_diagnostic(&node.id, result.clone())?; }
        match result.latency_ms {
            Some(latency_ms) => Ok(UrlTestResult { node_id: node.id, latency_ms, active_tunnel: active }),
            None => Err(result.error.unwrap_or("diagnostics failed".into())),
        }
    }).await.map_err(|_| "diagnostics worker failed")?
}
#[tauri::command]
pub async fn url_test_group(group_id: String, only_missing: Option<bool>, state: State<'_, SharedState>) -> Result<(), String> {
    let run = state.diagnostics.begin(false)?;
    let nodes = if only_missing.unwrap_or(false) {
        state.store.unmeasured_nodes(&group_id)?
    } else {
        state.store.group_nodes(&group_id)?
    };
    if nodes.is_empty() { return Ok(()); }
    let store = Arc::clone(&state.store);
    let runtime = state.runtime_source.clone();
    let dir = state.url_test_dir.clone();
    let settings = store.preferences().advanced.ping;
    tauri::async_runtime::spawn_blocking(move || {
        run.run_batch(&store, group_id, &nodes, &settings, |node| {
            url_test::test_node(&runtime, &dir, node, &settings, run.cancellation())
        })
    }).await.map_err(|_| "diagnostics task failed")?
}

pub fn request_disconnect(state: &SharedState) -> EngineSnapshot {
    state.diagnostics.cancel(true);
    let stopping = {
        let mut current = state.snapshot.write().unwrap_or_else(|error| error.into_inner());
        if matches!(current.phase, EnginePhase::Offline | EnginePhase::Stopping) { return current.clone(); }
        state.cancel.store(true, Ordering::SeqCst);
        current.phase = EnginePhase::Stopping;
        current.message = Some("disconnecting".into());
        current.clone()
    };
    let engine = Arc::clone(&state.engine);
    let snapshot = Arc::clone(&state.snapshot);
    let cancel = Arc::clone(&state.cancel);
    tauri::async_runtime::spawn_blocking(move || {
        let Ok(mut engine) = engine.lock() else { return };
        {
            let mut current = snapshot.write().unwrap_or_else(|error| error.into_inner());
            // Cancelled startup may already have shut down. A later start owns a fresh request.
            if current.phase == EnginePhase::Offline || !cancel.load(Ordering::SeqCst) { return; }
            current.phase = EnginePhase::Stopping;
        }
        engine.stop();
    });
    stopping
}

pub fn start_selected_background(state: &SharedState) -> Result<(), String> {
    let operation = crate::operation::Operation::acquire(&state.operation)?;
    if state.store.preferences().advanced.auto_node { let selected=state.store.selection(); state.store.auto_selection(selected.group_id.as_deref().ok_or("select a subscription first")?)?; }
    let node = state.store.selected_node()?;
    if prepare_start(state, &node).is_err() { return Ok(()); }
    let engine = Arc::clone(&state.engine);
    tauri::async_runtime::spawn_blocking(move || { let _operation = operation; if let Ok(mut engine) = engine.lock() { let _ = engine.start(&node); } });
    Ok(())
}

fn prepare_start(state: &SharedState, node: &ProxyNode) -> Result<(), EnginePhase> {
    let mut current = state.snapshot.write().unwrap_or_else(|error| error.into_inner());
    if !matches!(current.phase, EnginePhase::Offline | EnginePhase::Error) { return Err(current.phase.clone()); }
    state.cancel.store(false, Ordering::SeqCst);
    *current = queued_start(node);
    Ok(())
}

fn queued_start(node: &ProxyNode) -> EngineSnapshot {
    EngineSnapshot { phase: EnginePhase::Starting, node_name: Some(node.name.clone()), node_id: Some(node.id.clone()), message: Some("starting".into()) }
}

pub fn read_snapshot(value: &RwLock<EngineSnapshot>) -> EngineSnapshot { match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() } }
fn read_traffic(value: &RwLock<TrafficSnapshot>) -> TrafficSnapshot { match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() } }
fn phase_name(v: &EnginePhase) -> &'static str { match v { EnginePhase::Offline => "offline", EnginePhase::Starting => "starting", EnginePhase::Connected => "connected", EnginePhase::Stopping => "stopping", EnginePhase::Error => "error" } }
fn now_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }
