use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, RwLock,
    },
    time::{Duration, Instant},
};

use serde::Serialize;

use crate::{
    diagnostics::{run_bounded, PingResult},
    model::{EnginePhase, EngineSnapshot, ProxyNode},
    settings::PingSettings,
    storage::Store,
    url_test,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BatchPhase {
    Running,
    Cancelling,
    Completed,
    Cancelled,
    Failed,
}

impl BatchPhase {
    fn active(self) -> bool {
        matches!(self, Self::Running | Self::Cancelling)
    }
}

#[derive(Clone, Serialize)]
pub struct BatchStatus {
    run_id: u64,
    group_id: String,
    phase: BatchPhase,
    total: usize,
    checked: usize,
    succeeded: usize,
    failed: usize,
    unavailable: usize,
    pending_node_ids: HashSet<String>,
}

struct Batch {
    status: BatchStatus,
    results: HashMap<String, PingResult>,
}

#[derive(Default)]
pub struct Diagnostics {
    state: Mutex<DiagnosticState>,
}

#[derive(Default)]
struct DiagnosticState {
    active: Option<ActiveRun>,
    batch: Option<Batch>,
    next_run_id: u64,
}

struct ActiveRun {
    cancel: Arc<AtomicBool>,
    active_tunnel: bool,
}

pub struct DiagnosticRun {
    diagnostics: Arc<Diagnostics>,
    cancel: Arc<AtomicBool>,
}

impl Diagnostics {
    pub fn begin(self: &Arc<Self>, active_tunnel: bool) -> Result<DiagnosticRun, String> {
        let mut state = self.state.lock().expect("diagnostics state poisoned");
        if state.active.is_some() {
            return Err("wait for current diagnostics to finish".into());
        }
        let cancel = Arc::new(AtomicBool::new(false));
        state.active = Some(ActiveRun {
            cancel: Arc::clone(&cancel),
            active_tunnel,
        });
        Ok(DiagnosticRun {
            diagnostics: Arc::clone(self),
            cancel,
        })
    }

    pub fn cancel(&self, active_tunnel_only: bool) {
        let mut state = self.state.lock().expect("diagnostics state poisoned");
        let Some(active) = state.active.as_ref() else {
            return;
        };
        if active_tunnel_only && !active.active_tunnel {
            return;
        }
        active.cancel.store(true, Ordering::SeqCst);
        if let Some(batch) = state
            .batch
            .as_mut()
            .filter(|batch| batch.status.phase.active())
        {
            batch.status.phase = BatchPhase::Cancelling;
        }
    }

    pub fn update_cache<T>(&self, update: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
        let mut state = self.state.lock().expect("diagnostics state poisoned");
        if state.active.is_some() {
            return Err("wait for current diagnostics to finish".into());
        }
        let result = update()?;
        state.batch = None;
        Ok(result)
    }

    pub fn status(&self) -> Option<BatchStatus> {
        self.state
            .lock()
            .expect("diagnostics state poisoned")
            .batch
            .as_ref()
            .map(|batch| batch.status.clone())
    }

    pub fn live_results(&self) -> HashMap<String, PingResult> {
        self.state
            .lock()
            .expect("diagnostics state poisoned")
            .batch
            .as_ref()
            .filter(|batch| batch.status.phase.active() || batch.status.phase == BatchPhase::Failed)
            .map(|batch| batch.results.clone())
            .unwrap_or_default()
    }
}

impl DiagnosticRun {
    pub fn cancellation(&self) -> &AtomicBool {
        &self.cancel
    }

    fn start_batch(&self, group_id: String, nodes: &[ProxyNode]) {
        let mut state = self
            .diagnostics
            .state
            .lock()
            .expect("diagnostics state poisoned");
        let pending_node_ids: HashSet<_> = nodes.iter().map(|node| node.id.clone()).collect();
        let phase = if self.cancel.load(Ordering::SeqCst) {
            BatchPhase::Cancelling
        } else {
            BatchPhase::Running
        };
        state.next_run_id += 1;
        state.batch = Some(Batch {
            status: BatchStatus {
                run_id: state.next_run_id,
                group_id,
                phase,
                total: pending_node_ids.len(),
                pending_node_ids,
                checked: 0,
                succeeded: 0,
                failed: 0,
                unavailable: 0,
            },
            results: HashMap::new(),
        });
    }

    fn publish(&self, id: &str, result: PingResult) {
        let mut state = self
            .diagnostics
            .state
            .lock()
            .expect("diagnostics state poisoned");
        let Some(batch) = state
            .batch
            .as_mut()
            .filter(|batch| batch.status.phase == BatchPhase::Running)
        else {
            return;
        };
        if !batch.status.pending_node_ids.remove(id) {
            return;
        }
        batch.status.checked += 1;
        if result.success {
            batch.status.succeeded += 1;
        } else if result.unavailable() {
            batch.status.unavailable += 1;
        } else {
            batch.status.failed += 1;
        }
        batch.results.insert(id.to_owned(), result);
    }

    pub fn run_batch(
        &self,
        store: &Store,
        group_id: String,
        nodes: &[ProxyNode],
        settings: &PingSettings,
        probe: impl Fn(&ProxyNode) -> PingResult + Sync,
    ) -> Result<(), String> {
        self.start_batch(group_id, nodes);
        let outcome = run_bounded(nodes, settings.parallelism, &self.cancel, |node| {
            self.publish(&node.id, probe(node));
        });

        // No worker can publish after joining, and the lease excludes cache mutations.
        let results = self.diagnostics.live_results();
        let saved = store.record_diagnostics(results);
        let persisted = saved.is_ok();
        let outcome = outcome.and(saved);
        let mut state = self
            .diagnostics
            .state
            .lock()
            .expect("diagnostics state poisoned");
        if let Some(batch) = state.batch.as_mut() {
            batch.status.phase = if outcome.is_err() {
                BatchPhase::Failed
            } else if self.cancel.load(Ordering::SeqCst) {
                BatchPhase::Cancelled
            } else {
                BatchPhase::Completed
            };
            eprintln!(
                "diagnostics {:?} {:?} {}/{}",
                settings.method, batch.status.phase, batch.status.checked, batch.status.total
            );
            if persisted {
                batch.results.clear();
            }
        }
        outcome
    }
}

impl Drop for DiagnosticRun {
    fn drop(&mut self) {
        let mut state = self
            .diagnostics
            .state
            .lock()
            .expect("diagnostics state poisoned");
        if let Some(batch) = state
            .batch
            .as_mut()
            .filter(|batch| batch.status.phase.active())
        {
            batch.status.phase = BatchPhase::Failed;
            batch.results.clear();
        }
        state.active = None;
    }
}

pub fn spawn_scheduler(
    store: Arc<Store>,
    diagnostics: Arc<Diagnostics>,
    snapshot: Arc<RwLock<EngineSnapshot>>,
    exiting: Arc<AtomicBool>,
    runtime: PathBuf,
    dir: PathBuf,
) {
    std::thread::spawn(move || {
        let mut startup = true;
        let mut previous = Instant::now();
        while !exiting.load(Ordering::SeqCst) {
            let settings = store.preferences().advanced.ping;
            let due = (startup && settings.on_start)
                || (settings.repeat_minutes > 0
                    && previous.elapsed()
                        >= Duration::from_secs(settings.repeat_minutes as u64 * 60));
            startup = false;
            if due {
                previous = Instant::now();
                if matches!(
                    crate::commands::read_snapshot(&snapshot).phase,
                    EnginePhase::Offline | EnginePhase::Error
                ) {
                    if let Ok(run) = diagnostics.begin(false) {
                        let settings = store.preferences().advanced.ping;
                        let selection = store.selection();
                        if let Some(group_id) = selection.group_id {
                            let nodes = if settings.selected_only {
                                selection
                                    .node_id
                                    .as_deref()
                                    .ok_or("no selected node".into())
                                    .and_then(|id| store.node(&group_id, id))
                                    .map(|node| vec![node])
                            } else {
                                store.group_nodes(&group_id)
                            };
                            if let Ok(nodes) = nodes {
                                let _ =
                                    run.run_batch(&store, group_id, &nodes, &settings, |node| {
                                        url_test::test_node(
                                            &runtime,
                                            &dir,
                                            node,
                                            &settings,
                                            run.cancellation(),
                                        )
                                    });
                            }
                        }
                    }
                }
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        diagnostics::measure,
        model::{NodeSortMode, SubscriptionGroup},
        operation::Operation,
        vless::parse_vless,
    };

    #[test]
    fn isolated_batch_can_overlap_connection_and_preserves_partial_progress_on_cancel() {
        let diagnostics = Arc::new(Diagnostics::default());
        let run = diagnostics.begin(false).unwrap();
        let first = parse_vless("vless://first@127.0.0.1:443").unwrap();
        let second = parse_vless("vless://second@127.0.0.1:443").unwrap();
        run.start_batch("group".into(), &[first.clone(), second.clone()]);

        let connection_gate = Arc::new(AtomicBool::new(false));
        let _connection = Operation::acquire(&connection_gate).unwrap();
        diagnostics.cancel(true);
        assert!(!run.cancellation().load(Ordering::SeqCst));
        assert!(diagnostics.begin(false).is_err());
        assert!(diagnostics.update_cache(|| Ok(())).is_err());

        let result = measure(&PingSettings::default(), false, run.cancellation(), || {
            Ok(10)
        });
        run.publish(&first.id, result.clone());
        diagnostics.cancel(false);
        run.publish(&second.id, result);
        let status = diagnostics.status().unwrap();
        assert_eq!(status.phase, BatchPhase::Cancelling);
        assert_eq!((status.checked, status.total, status.succeeded), (1, 2, 1));
        assert!(status.pending_node_ids.contains(&second.id));
        assert_eq!(diagnostics.live_results().len(), 1);
        drop(run);
        assert!(!diagnostics
            .begin(false)
            .unwrap()
            .cancellation()
            .load(Ordering::SeqCst));
    }

    #[test]
    fn cancelled_batch_keeps_measured_results_in_delay_sort_and_cache() {
        let directory = std::env::temp_dir().join(format!(
            "dot-batch-{}-{}",
            std::process::id(),
            crate::diagnostics::now_ms()
        ));
        let path = directory.join("state.json");
        let store = Store::open(path.clone()).unwrap();
        let first = parse_vless("vless://first@127.0.0.1:443#first").unwrap();
        let second = parse_vless("vless://second@127.0.0.1:443#second").unwrap();
        let nodes = vec![first.clone(), second.clone()];
        store
            .upsert_group(SubscriptionGroup {
                id: "group".into(),
                name: "group".into(),
                url: String::new(),
                updated_at_ms: 0,
                nodes: nodes.iter().cloned().rev().collect(),
                hwid: None,
            })
            .unwrap();
        store.set_sort_mode("group", NodeSortMode::Delay).unwrap();

        let diagnostics = Arc::new(Diagnostics::default());
        let run = diagnostics.begin(false).unwrap();
        let settings = PingSettings {
            parallelism: 1,
            ..PingSettings::default()
        };
        let mut advanced = store.preferences().advanced;
        advanced.ping = settings.clone();
        store.set_advanced(advanced).unwrap();
        run.run_batch(&store, "group".into(), &nodes, &settings, |node| {
            if node.id == second.id {
                let live = store.groups_with_results(&diagnostics.live_results());
                assert_eq!(live[0].nodes[0].id, first.id);
                assert_eq!(live[0].nodes[0].latency_ms, Some(25));
                diagnostics.cancel(false);
            }
            measure(&settings, false, run.cancellation(), || Ok(25))
        })
        .unwrap();

        assert_eq!(diagnostics.status().unwrap().phase, BatchPhase::Cancelled);
        drop(run);
        drop(store);
        let restored = Store::open(path.clone()).unwrap();
        assert_eq!(restored.groups()[0].nodes[0].id, first.id);
        assert_eq!(restored.groups()[0].nodes[0].latency_ms, Some(25));
        assert_eq!(restored.unmeasured_nodes("group").unwrap()[0].id, second.id);
        drop(restored);
        std::fs::remove_file(path).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn a_slow_node_does_not_hold_up_other_worker_slots() {
        let (release, wait) = std::sync::mpsc::sync_channel(1);
        let wait = Mutex::new(wait);
        let cancel = AtomicBool::new(false);
        let results = run_bounded(&[0, 1, 2], 2, &cancel, |node| {
            if *node == 0 {
                assert!(wait
                    .lock()
                    .unwrap()
                    .recv_timeout(Duration::from_secs(3))
                    .is_ok());
            } else if *node == 2 {
                release.send(()).unwrap();
            }
            *node
        })
        .unwrap();
        assert_eq!(results, vec![0, 1, 2]);
    }
}
