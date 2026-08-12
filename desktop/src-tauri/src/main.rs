#![cfg_attr(windows, windows_subsystem = "windows")]

mod commands;
mod config;
#[cfg(test)] mod config_smoke;
mod engine;
#[cfg(windows)] mod job;
mod model;
mod session;
mod storage;
mod subscription;
mod traffic;
mod tray;
mod url_test;
mod vless;

use std::{path::PathBuf, sync::{atomic::{AtomicBool, Ordering}, Arc, Mutex, RwLock}};
use commands::SharedState;
use engine::{spawn_watchdog, VpnEngine};
use model::{EngineSnapshot, TrafficSnapshot};
use storage::Store;
use tauri::Manager;

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let runtime_source = discover_runtime_source(&app.path().resource_dir()?);
            let url_test_dir = data_dir.join("url-test");
            let store = Store::open(data_dir.join("state.json")).map_err(std::io::Error::other)?;
            let snapshot = Arc::new(RwLock::new(EngineSnapshot::default()));
            let traffic = Arc::new(RwLock::new(TrafficSnapshot::default()));
            let cancel = Arc::new(AtomicBool::new(false));
            let exiting = Arc::new(AtomicBool::new(false));
            let engine = Arc::new(Mutex::new(VpnEngine::new(runtime_source.clone(), data_dir.join("vpn"), Arc::clone(&snapshot), Arc::clone(&cancel)).map_err(std::io::Error::other)?));
            spawn_watchdog(&engine);
            traffic::spawn_traffic_sampler(&snapshot, &traffic);
            app.manage(SharedState { store, engine, snapshot, traffic, cancel, exiting, runtime_source, url_test_dir });
            tray::setup(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let state = window.app_handle().state::<SharedState>();
                if !state.exiting.load(Ordering::SeqCst) && state.store.preferences().close_to_tray {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_groups,
            commands::selection,
            commands::preferences,
            commands::traffic_status,
            commands::select_node,
            commands::switch_node,
            commands::set_theme,
            commands::set_close_to_tray,
            commands::add_subscription,
            commands::refresh_subscription,
            commands::connect,
            commands::disconnect,
            commands::vpn_status,
            commands::url_test,
        ])
        .run(tauri::generate_context!())
        .expect("error while running dot. Desktop");
}

fn discover_runtime_source(resource_dir: &std::path::Path) -> PathBuf {
    let bundled = resource_dir.join("runtime");
    if runtime_complete(&bundled) { return bundled; }

    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let portable = parent.join("runtime");
            if runtime_complete(&portable) { return portable; }
        }
    }

    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("runtime")
}

fn runtime_complete(path: &std::path::Path) -> bool {
    path.join("xray.exe").exists() && path.join("wintun.dll").exists()
}
