use std::sync::atomic::Ordering;

use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    App, Manager,
};

use crate::{commands::{request_disconnect, start_selected_background, SharedState}, model::EnginePhase};

pub fn setup(app: &mut App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open dot.", true, None::<&str>)?;
    let toggle = MenuItem::with_id(app, "toggle", "Connect / Disconnect", true, None::<&str>)?;
    let separator = PredefinedMenuItem::separator(app)?;
    let quit = MenuItem::with_id(app, "quit", "Exit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &toggle, &separator, &quit])?;

    TrayIconBuilder::new()
        .icon(app.default_window_icon().expect("dot. has an app icon").clone())
        .tooltip("dot.")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "toggle" => {
                let state = app.state::<SharedState>();
                let phase = crate::commands::read_snapshot(&state.snapshot).phase;
                if matches!(phase, EnginePhase::Connected | EnginePhase::Starting | EnginePhase::Stopping) {
                    request_disconnect(&state);
                } else {
                    let _ = start_selected_background(&state);
                }
            }
            "quit" => {
                let state = app.state::<SharedState>();
                state.exiting.store(true, Ordering::SeqCst);
                state.cancel.store(true, Ordering::SeqCst);
                if let Ok(mut engine) = state.engine.try_lock() { engine.stop(); }
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn show_main<R: tauri::Runtime, M: Manager<R>>(manager: &M) {
    if let Some(window) = manager.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}
