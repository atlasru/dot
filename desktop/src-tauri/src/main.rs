mod commands;
mod config;
#[cfg(test)]
mod config_smoke;
mod engine;
#[cfg(windows)]
mod job;
mod model;
mod session;
mod storage;
mod subscription;
mod vless;

use std::{
    path::PathBuf,
    sync::{
        atomic::AtomicBool,
        Arc, Mutex, RwLock,
    },
};

use commands::SharedState;
use engine::{spawn_watchdog, VpnEngine};
use model::EngineSnapshot;
use storage::Store;
use tauri::Manager;

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let resource_dir = app.path().resource_dir()?;
            let runtime_source = discover_runtime_source(&resource_dir);
            let store = Store::open(data_dir.join("state.json"))
                .map_err(std::io::Error::other)?;

            let snapshot = Arc::new(RwLock::new(EngineSnapshot::default()));
            let cancel = Arc::new(AtomicBool::new(false));
            let engine = Arc::new(Mutex::new(
                VpnEngine::new(
                    runtime_source,
                    data_dir.join("vpn"),
                    Arc::clone(&snapshot),
                    Arc::clone(&cancel),
                )
                .map_err(std::io::Error::other)?,
            ));
            spawn_watchdog(&engine);

            app.manage(SharedState {
                store,
                engine,
                snapshot,
                cancel,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_groups,
            commands::add_subscription,
            commands::refresh_subscription,
            commands::connect,
            commands::disconnect,
            commands::vpn_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running dot. Desktop");
}

fn discover_runtime_source(resource_dir: &std::path::Path) -> PathBuf {
    let bundled = resource_dir.join("runtime");
    if bundled.join("xray.exe").exists() {
        return bundled;
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("runtime")
}
