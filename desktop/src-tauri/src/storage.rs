use std::{fs, path::PathBuf, sync::Mutex};

use crate::model::{GroupView, PersistedState, SubscriptionGroup, VlessNode};

pub struct Store {
    path: PathBuf,
    inner: Mutex<PersistedState>,
}

impl Store {
    pub fn open(path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("failed to create data directory: {e}"))?;
        }
        let state = if path.exists() {
            let bytes = fs::read(&path).map_err(|e| format!("failed to read state: {e}"))?;
            serde_json::from_slice(&bytes).map_err(|e| format!("failed to parse state: {e}"))?
        } else {
            PersistedState::default()
        };
        Ok(Self { path, inner: Mutex::new(state) })
    }

    pub fn groups(&self) -> Vec<GroupView> {
        self.inner
            .lock()
            .expect("store poisoned")
            .groups
            .iter()
            .map(GroupView::from)
            .collect()
    }

    pub fn upsert_group(&self, group: SubscriptionGroup) -> Result<GroupView, String> {
        let mut state = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        if let Some(existing) = state.groups.iter_mut().find(|g| g.id == group.id) {
            *existing = group;
        } else {
            state.groups.push(group);
        }
        self.save_locked(&state)?;
        let group = state.groups.last().expect("group exists");
        Ok(GroupView::from(group))
    }

    pub fn replace_group_nodes(&self, group_id: &str, nodes: Vec<VlessNode>, updated_at_ms: u64) -> Result<GroupView, String> {
        let mut state = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        let group = state.groups.iter_mut().find(|g| g.id == group_id).ok_or_else(|| "subscription group not found".to_string())?;
        group.nodes = nodes;
        group.updated_at_ms = updated_at_ms;
        let view = GroupView::from(&*group);
        self.save_locked(&state)?;
        Ok(view)
    }

    pub fn group_url(&self, group_id: &str) -> Result<String, String> {
        self.inner
            .lock()
            .map_err(|_| "store lock poisoned".to_string())?
            .groups
            .iter()
            .find(|g| g.id == group_id)
            .map(|g| g.url.clone())
            .ok_or_else(|| "subscription group not found".to_string())
    }

    pub fn node(&self, group_id: &str, node_id: &str) -> Result<VlessNode, String> {
        let state = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        state
            .groups
            .iter()
            .find(|g| g.id == group_id)
            .and_then(|g| g.nodes.iter().find(|n| n.id == node_id))
            .cloned()
            .ok_or_else(|| "node not found".to_string())
    }

    fn save_locked(&self, state: &PersistedState) -> Result<(), String> {
        let bytes = serde_json::to_vec_pretty(state).map_err(|e| format!("failed to serialize state: {e}"))?;
        let temp = self.path.with_extension("json.tmp");
        fs::write(&temp, bytes).map_err(|e| format!("failed to write state: {e}"))?;
        fs::rename(&temp, &self.path).map_err(|e| format!("failed to commit state: {e}"))?;
        Ok(())
    }
}
