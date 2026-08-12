mod commands;
mod config;
mod engine;
mod model;
mod storage;
mod subscription;
mod vless;

use std::path::PathBuf;

use commands::SharedState;
use engine::VpnEngine;
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
            let engine = VpnEngine::new(runtime_source, data_dir.join("vpn"))
                .map_err(std::io::Error::other)?;
            app.manage(SharedState {
                store,
                engine: std::sync::Mutex::new(engine),
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
