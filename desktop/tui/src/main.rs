// SPDX-License-Identifier: GPL-3.0-or-later
#![cfg(windows)]

#[path = "../../src-tauri/src/config.rs"] mod config;
#[path = "../../src-tauri/src/engine.rs"] mod engine;
#[path = "../../src-tauri/src/hysteria2.rs"] mod hysteria2;
#[path = "../../src-tauri/src/job.rs"] mod job;
#[path = "../../src-tauri/src/model.rs"] mod model;
#[path = "../../src-tauri/src/node_sort.rs"] mod node_sort;
#[path = "../../src-tauri/src/session.rs"] mod session;
#[path = "../../src-tauri/src/storage.rs"] mod storage;
#[path = "../../src-tauri/src/subscription.rs"] mod subscription;
#[path = "../../src-tauri/src/subscription_diff.rs"] mod subscription_diff;
#[path = "../../src-tauri/src/subscription_identity.rs"] mod subscription_identity;
#[path = "../../src-tauri/src/url_test.rs"] mod url_test;
#[path = "../../src-tauri/src/vless.rs"] mod vless;

use std::{
    env, io, path::PathBuf,
    sync::{atomic::{AtomicBool, Ordering}, mpsc::{self, Receiver, Sender}, Arc, Mutex, RwLock},
    thread, time::Duration,
};
use crossterm::{
    event::{self, Event, KeyCode, KeyEventKind, KeyModifiers},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use ratatui::{
    backend::CrosstermBackend, layout::{Constraint, Direction, Layout},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap},
    Terminal,
};
use model::{EnginePhase, EngineSnapshot, GroupView, NodeSortMode, SubscriptionGroup};
use storage::Store;
use subscription::SubscriptionClient;
use subscription_identity::SubscriptionIdentity;

struct App {
    store: Arc<Store>,
    engine: Arc<Mutex<engine::VpnEngine>>,
    snapshot: Arc<RwLock<EngineSnapshot>>,
    cancel: Arc<AtomicBool>,
    busy: Arc<AtomicBool>,
    tx: Sender<String>,
    rx: Receiver<String>,
    group: usize,
    row: usize,
    input: Option<String>,
    message: String,
    runtime: PathBuf,
    work_dir: PathBuf,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let data_dir = match env::var_os("DOT_DATA_DIR") {
        Some(value) => PathBuf::from(value),
        None => PathBuf::from(env::var_os("APPDATA").ok_or("APPDATA is not defined")?)
            .join("dev.dotclient.desktop"),
    };
    let runtime = match env::var_os("DOT_RUNTIME") {
        Some(value) => PathBuf::from(value),
        None => env::current_exe()?.parent().ok_or("missing exe directory")?.join("runtime"),
    };
    let store = Arc::new(Store::open(data_dir.join("state.json"))?);
    let snapshot = Arc::new(RwLock::new(EngineSnapshot::default()));
    let cancel = Arc::new(AtomicBool::new(false));
    let engine = Arc::new(Mutex::new(engine::VpnEngine::new(
        runtime.clone(), data_dir.join("vpn"),
        Arc::clone(&snapshot), Arc::clone(&cancel),
    )?));
    engine::spawn_watchdog(&engine);
    let (tx, rx) = mpsc::channel();
    let mut app = App {
        store, engine, snapshot, cancel,
        busy: Arc::new(AtomicBool::new(false)),
        tx, rx, group: 0, row: 0, input: None,
        message: "Ready. Run elevated to connect.".into(),
        runtime, work_dir: data_dir.join("url-test"),
    };
    let selection = app.store.selection();
    if let Some(gid) = selection.group_id {
        if let Some(index) = app.store.groups().iter().position(|g| g.id == gid) {
            app.group = index;
            if let Some(nid) = selection.node_id {
                if let Some(index) = app.store.groups()[app.group].nodes.iter().position(|n| n.id == nid) {
                    app.row = index;
                }
            }
        }
    }

    enable_raw_mode()?;
    let mut stdout = io::stdout();
    if let Err(e) = execute!(stdout, EnterAlternateScreen) {
        let _ = disable_raw_mode();
        return Err(e.into());
    }
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout))?;
    let result = run(&mut terminal, &mut app);
    let _ = disable_raw_mode();
    let _ = execute!(terminal.backend_mut(), LeaveAlternateScreen);
    let _ = terminal.show_cursor();

    app.cancel.store(true, Ordering::SeqCst);
    if let Ok(mut engine) = app.engine.lock() { engine.stop(); }
    result.map_err(Into::into)
}

fn run(terminal: &mut Terminal<CrosstermBackend<io::Stdout>>, app: &mut App) -> io::Result<()> {
    loop {
        while let Ok(message) = app.rx.try_recv() { app.message = message; }
        terminal.draw(|frame| draw(frame, app))?;
        if !event::poll(Duration::from_millis(150))? { continue; }
        let Event::Key(key) = event::read()? else { continue };
        if key.kind != KeyEventKind::Press { continue; }
        if let Some(input) = &mut app.input {
            match key.code {
                KeyCode::Esc => { app.input = None; app.message = "Import cancelled".into(); }
                KeyCode::Enter => {
                    let url = app.input.take().unwrap_or_default();
                    if !url.trim().is_empty() { add_subscription(app, url); }
                }
                KeyCode::Backspace => { input.pop(); }
                KeyCode::Char(c) if !key.modifiers.contains(KeyModifiers::CONTROL) => { input.push(c); }
                _ => {}
            }
            continue;
        }
        let groups = app.store.groups();
        let len = groups.len();
        match key.code {
            KeyCode::Char('q') => break,
            KeyCode::Up | KeyCode::Char('k') => { app.row = app.row.saturating_sub(1); }
            KeyCode::Down | KeyCode::Char('j') => {
                let count = groups.get(app.group).map_or(0, |g| g.nodes.len());
                if count > 0 { app.row = (app.row + 1).min(count - 1); }
            }
            KeyCode::Tab | KeyCode::Right => {
                if len > 0 { app.group = (app.group + 1) % len; app.row = 0; }
            }
            KeyCode::BackTab | KeyCode::Left => {
                if len > 0 { app.group = (app.group + len - 1) % len; app.row = 0; }
            }
            KeyCode::Enter => { connect(app); }
            KeyCode::Char('d') => { disconnect(app); }
            KeyCode::Char('a') => { app.input = Some(String::new()); }
            KeyCode::Char('r') => { refresh(app); }
            KeyCode::Char('t') => { test_selected(app); }
            KeyCode::Char('s') => {
                if let Some(g) = groups.get(app.group) {
                    let next = match g.sort_mode {
                        NodeSortMode::Origin => NodeSortMode::Name,
                        NodeSortMode::Name => NodeSortMode::Delay,
                        NodeSortMode::Delay => NodeSortMode::Origin,
                    };
                    match app.store.set_sort_mode(&g.id, next) {
                        Ok(_) => { app.row = 0; app.message = format!("Sort: {next:?}"); }
                        Err(error) => { app.message = error; }
                    }
                }
            }
            _ => {}
        }
    }
    Ok(())
}

fn task(app: &mut App, name: &str, f: impl FnOnce() -> Result<String, String> + Send + 'static) {
    if app.busy.swap(true, Ordering::SeqCst) {
        app.message = "Another operation is running".into();
        return;
    }
    app.message = format!("{name}...");
    let tx = app.tx.clone();
    let busy = Arc::clone(&app.busy);
    thread::spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
        let message = match result {
            Ok(Ok(value)) => value,
            Ok(Err(error)) => format!("Error: {error}"),
            Err(_) => "Error: background operation panicked".into(),
        };
        let _ = tx.send(message);
        busy.store(false, Ordering::SeqCst);
    });
}

fn selected(app: &App) -> Option<(String, String)> {
    app.store.groups().get(app.group).and_then(|g| {
        g.nodes.get(app.row).map(|n| (g.id.clone(), n.id.clone()))
    })
}

fn connect(app: &mut App) {
    let Some((gid, nid)) = selected(app) else { app.message = "No node selected".into(); return };
    let store = Arc::clone(&app.store);
    let engine = Arc::clone(&app.engine);
    let cancel = Arc::clone(&app.cancel);
    task(app, "Connecting", move || {
        let node = store.node(&gid, &nid)?;
        store.set_selection(&gid, &nid)?;
        let mut engine = engine.lock().map_err(|_| "VPN engine lock poisoned")?;
        cancel.store(false, Ordering::SeqCst);
        engine.start(&node)?;
        Ok(format!("Connected: {}", node.name))
    });
}

fn disconnect(app: &mut App) {
    app.cancel.store(true, Ordering::SeqCst);
    let engine = Arc::clone(&app.engine);
    // If startup is running, its cancellation flag is already set.
    let tx = app.tx.clone();
    thread::spawn(move || {
        let message = match engine.lock() {
            Ok(mut engine) => { engine.stop(); "Disconnected".to_string() }
            Err(_) => "Error: VPN engine lock poisoned".into(),
        };
        let _ = tx.send(message);
    });
    app.message = "Disconnecting...".into();
}

fn add_subscription(app: &mut App, raw: String) {
    let url = raw.trim().to_string();
    let parsed = match url::Url::parse(&url) {
        Ok(value) if value.scheme() == "https" || value.scheme() == "http" => value,
        _ => { app.message = "Invalid HTTP(S) subscription URL".into(); return; }
    };
    if parsed.host_str().is_none() { app.message = "Subscription URL missing host".into(); return; }
    let store = Arc::clone(&app.store);
    task(app, "Importing subscription", move || {
        let hwid = SubscriptionIdentity::generate()?;
        let nodes = SubscriptionClient::new()?.fetch(parsed.as_str(), &hwid)?;
        let id = uuid::Uuid::new_v5(&uuid::Uuid::NAMESPACE_URL, format!("{}:{}", url, now_ms()).as_bytes()).to_string();
        let name = parsed.host_str().unwrap_or("Subscription").to_string();
        let count = nodes.len();
        store.upsert_group(SubscriptionGroup {
            id, name, url: parsed.to_string(), updated_at_ms: now_ms(), nodes, hwid: Some(hwid),
        })?;
        Ok(format!("Imported {count} nodes"))
    });
}

fn refresh(app: &mut App) {
    let Some(gid) = app.store.groups().get(app.group).map(|g| g.id.clone()) else { return };
    let store = Arc::clone(&app.store);
    task(app, "Refreshing subscription", move || {
        let (url, hwid) = store.subscription_source(&gid)?;
        if url.is_empty() { return Err("This is a local group".into()); }
        let nodes = SubscriptionClient::new()?.fetch(&url, &hwid)?;
        let result = store.apply_refresh(&gid, nodes, now_ms())?;
        Ok(format!("Refreshed: +{} / -{} / ~{}", result.added.len(), result.deleted.len(), result.edited.len()))
    });
}

fn test_selected(app: &mut App) {
    let Some((gid, nid)) = selected(app) else { return };
    let phase = app.snapshot.read().map(|s| s.phase.clone()).unwrap_or(EnginePhase::Error);
    if phase != EnginePhase::Offline && phase != EnginePhase::Error {
        app.message = "Disconnect before testing nodes".into();
        return;
    }
    let store = Arc::clone(&app.store);
    let runtime = app.runtime.clone();
    let dir = app.work_dir.clone();
    task(app, "Testing node", move || {
        let node = store.node(&gid, &nid)?;
        match url_test::test_node(&runtime, &dir, &node) {
            Ok(ms) => {
                store.record_latency(&nid, Some(ms), false, now_ms())?;
                Ok(format!("{}: {ms} ms", node.name))
            }
            Err(error) => {
                store.record_latency(&nid, None, true, now_ms())?;
                Err(error)
            }
        }
    });
}

fn now_ms() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

fn draw(frame: &mut ratatui::Frame<'_>, app: &App) {
    let area = frame.area();
    let sections = Layout::default().direction(Direction::Vertical)
        .constraints([Constraint::Length(3), Constraint::Min(6), Constraint::Length(4), Constraint::Length(3)])
        .split(area);
    let snapshot = app.snapshot.read().map(|v| v.clone()).unwrap_or_default();
    let (phase, color) = match snapshot.phase {
        EnginePhase::Connected => ("CONNECTED", Color::Green),
        EnginePhase::Starting => ("CONNECTING", Color::Yellow),
        EnginePhase::Stopping => ("STOPPING", Color::Yellow),
        EnginePhase::Error => ("ERROR", Color::Red),
        EnginePhase::Offline => ("OFFLINE", Color::DarkGray),
    };
    let title = Paragraph::new(Line::from(vec![
        Span::styled(" dot. ", Style::default().fg(Color::Red).add_modifier(Modifier::BOLD)),
        Span::raw(" TUI  |  "),
        Span::styled(phase, Style::default().fg(color).add_modifier(Modifier::BOLD)),
        Span::raw(format!("  |  {}", snapshot.node_name.unwrap_or_else(|| "No active node".into()))),
    ])).block(Block::default().borders(Borders::ALL));
    frame.render_widget(title, sections[0]);

    let groups: Vec<GroupView> = app.store.groups();
    let group = groups.get(app.group);
    let group_label = group.map(|g| format!(" {}/{}  {}  [{} nodes]  sort: {:?} ",
        app.group + 1, groups.len(), g.name, g.nodes.len(), g.sort_mode))
        .unwrap_or_else(|| " No subscriptions  |  press a to import ".into());
    let items: Vec<ListItem> = group.map(|g| g.nodes.iter().map(|n| {
        let latency = match n.latency_ms {
            Some(ms) => format!("{ms} ms"),
            None if n.latency_failed => "failed".into(),
            None => "—".into(),
        };
        let favorite = if n.favorite { "★ " } else { "  " };
        ListItem::new(format!("{favorite}{}  |  {}  |  {}  |  {}",
            n.name, n.protocol, n.host, latency))
    }).collect()).unwrap_or_default();
    let list = List::new(items)
        .block(Block::default().title(group_label).borders(Borders::ALL))
        .highlight_style(Style::default().fg(Color::Black).bg(Color::White).add_modifier(Modifier::BOLD))
        .highlight_symbol("› ");
    let mut selected = ListState::default();
    if group.is_some_and(|g| !g.nodes.is_empty()) {
        selected.select(Some(app.row.min(group.unwrap().nodes.len() - 1)));
    }
    frame.render_stateful_widget(list, sections[1], &mut selected);

    let msg = if let Some(input) = &app.input {
        // Avoid echoing secret-bearing subscription URLs into terminal scrollback.
        format!("Subscription URL: {} characters entered  | Enter submit | Esc cancel", input.chars().count())
    } else {
        app.message.clone()
    };
    let status = Paragraph::new(vec![
        Line::from(msg),
        Line::from(snapshot.message.unwrap_or_default()),
    ]).wrap(Wrap { trim: true }).block(Block::default().title(" Status ").borders(Borders::ALL));
    frame.render_widget(status, sections[2]);
    frame.render_widget(
        Paragraph::new("↑↓/jk nodes  Tab group  Enter connect/switch  d disconnect  a add  r refresh  t test  s sort  q quit")
            .block(Block::default().borders(Borders::ALL)),
        sections[3],
    );
}
