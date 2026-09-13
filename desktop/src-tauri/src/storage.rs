use std::{fs, path::{Path, PathBuf}, sync::Mutex};

use crate::{
    model::{
        AppPreferences, AppTheme, GroupView, NodeEditView, NodeLatency, NodeSortMode, PersistedState,
        SelectionView, SubscriptionGroup, SubscriptionRefreshResult, VlessNode,
    },
    node_sort::sort_nodes,
    subscription_diff,
};

pub struct Store { path: PathBuf, inner: Mutex<PersistedState> }

impl Store {
    pub fn open(path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = path.parent() { fs::create_dir_all(parent).map_err(|e| format!("failed to create data directory: {e}"))?; }
        let mut state = if path.exists() {
            let bytes = fs::read(&path).map_err(|e| format!("failed to read state: {e}"))?;
            serde_json::from_slice(&bytes).map_err(|e| format!("failed to parse state: {e}"))?
        } else { PersistedState::default() };
        normalize_selection(&mut state);
        prune_runtime_metadata(&mut state);
        Ok(Self { path, inner: Mutex::new(state) })
    }

    pub fn groups(&self) -> Vec<GroupView> {
        let state = self.inner.lock().expect("store poisoned");
        state.groups.iter().map(|group| build_group_view(&state, group)).collect()
    }

    pub fn selection(&self) -> SelectionView {
        let s = self.inner.lock().expect("store poisoned");
        SelectionView { group_id: s.selected_group_id.clone(), node_id: s.selected_node_id.clone() }
    }

    pub fn preferences(&self) -> AppPreferences { self.inner.lock().expect("store poisoned").preferences.clone() }

    pub fn set_theme(&self, theme: AppTheme) -> Result<AppPreferences, String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        s.preferences.theme = theme;
        self.save_locked(&s)?;
        Ok(s.preferences.clone())
    }

    pub fn set_close_to_tray(&self, enabled: bool) -> Result<AppPreferences, String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        s.preferences.close_to_tray = enabled;
        self.save_locked(&s)?;
        Ok(s.preferences.clone())
    }

    pub fn set_sort_mode(&self, group_id: &str, mode: NodeSortMode) -> Result<GroupView, String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        if !s.groups.iter().any(|group| group.id == group_id) { return Err("subscription group not found".into()); }
        s.sort_modes.insert(group_id.to_string(), mode);
        self.save_locked(&s)?;
        let group = s.groups.iter().find(|group| group.id == group_id).expect("group checked above");
        Ok(build_group_view(&s, group))
    }

    pub fn set_selection(&self, group_id: &str, node_id: &str) -> Result<SelectionView, String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        let valid = s.groups.iter().any(|g| g.id == group_id && g.nodes.iter().any(|n| n.id == node_id));
        if !valid { return Err("selected node does not exist in subscription group".into()); }
        s.selected_group_id = Some(group_id.into());
        s.selected_node_id = Some(node_id.into());
        self.save_locked(&s)?;
        Ok(SelectionView { group_id: s.selected_group_id.clone(), node_id: s.selected_node_id.clone() })
    }

    pub fn selected_node(&self) -> Result<VlessNode, String> {
        let s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        let group_id = s.selected_group_id.as_deref().ok_or("no subscription group selected")?;
        let node_id = s.selected_node_id.as_deref().ok_or("no node selected")?;
        s.groups.iter().find(|g| g.id == group_id).and_then(|g| g.nodes.iter().find(|n| n.id == node_id)).cloned().ok_or_else(|| "selected node no longer exists".into())
    }

    pub fn upsert_group(&self, group: SubscriptionGroup) -> Result<GroupView, String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        let id = group.id.clone();
        if let Some(existing) = s.groups.iter_mut().find(|g| g.id == id) { *existing = group; } else { s.groups.push(group); }
        normalize_selection(&mut s);
        prune_runtime_metadata(&mut s);
        self.save_locked(&s)?;
        s.groups.iter().find(|g| g.id == id).map(|group| build_group_view(&s, group)).ok_or_else(|| "subscription group disappeared after save".into())
    }

    pub fn apply_refresh(&self, group_id: &str, nodes: Vec<VlessNode>, updated_at_ms: u64) -> Result<SubscriptionRefreshResult, String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        let index = s.groups.iter().position(|g| g.id == group_id).ok_or("subscription group not found")?;
        let old_nodes = s.groups[index].nodes.clone();
        let diff = subscription_diff::calculate(&old_nodes, &nodes);

        let selected_replacement = if s.selected_group_id.as_deref() == Some(group_id) {
            s.selected_node_id.as_deref().and_then(|old_id| diff.replacement_for(old_id)).map(|node| node.id.clone())
        } else { None };

        let old_latencies: Vec<(String, NodeLatency)> = old_nodes.iter().filter_map(|node| s.latencies.get(&node.id).cloned().map(|latency| (node.id.clone(), latency))).collect();
        for node in &old_nodes { s.latencies.remove(&node.id); }
        for (old_id, latency) in old_latencies {
            if let Some(replacement) = diff.replacement_for(&old_id) {
                s.latencies.insert(replacement.id.clone(), NodeLatency {
                    node_id: replacement.id.clone(),
                    latency_ms: latency.latency_ms,
                    failed: latency.failed,
                    tested_at_ms: latency.tested_at_ms,
                });
            }
        }

        s.groups[index].nodes = nodes;
        s.groups[index].updated_at_ms = updated_at_ms;
        if let Some(replacement_id) = selected_replacement {
            s.selected_node_id = Some(replacement_id);
        }
        normalize_selection(&mut s);
        prune_runtime_metadata(&mut s);
        self.save_locked(&s)?;

        let group = build_group_view(&s, &s.groups[index]);
        Ok(SubscriptionRefreshResult {
            group,
            added: diff.added.iter().map(Into::into).collect(),
            deleted: diff.deleted.iter().map(Into::into).collect(),
            edited: diff.edited.iter().map(|edit| NodeEditView {
                before: (&edit.before).into(),
                after: (&edit.after).into(),
                changed_fields: edit.changed_fields.clone(),
            }).collect(),
            unchanged: diff.unchanged.len(),
        })
    }

    pub fn record_latency(&self, node_id: &str, latency_ms: Option<u64>, failed: bool, tested_at_ms: u64) -> Result<(), String> {
        let mut s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        if !s.groups.iter().any(|group| group.nodes.iter().any(|node| node.id == node_id)) {
            return Err("node not found".into());
        }
        s.latencies.insert(node_id.to_string(), NodeLatency { node_id: node_id.to_string(), latency_ms, failed, tested_at_ms });
        self.save_locked(&s)
    }

    pub fn group_url(&self, group_id: &str) -> Result<String, String> {
        self.inner.lock().map_err(|_| "store lock poisoned".to_string())?.groups.iter().find(|g| g.id == group_id).map(|g| g.url.clone()).ok_or_else(|| "subscription group not found".into())
    }

    pub fn group_nodes(&self, group_id: &str) -> Result<Vec<VlessNode>, String> {
        self.inner.lock().map_err(|_| "store lock poisoned".to_string())?.groups.iter().find(|g| g.id == group_id).map(|g| g.nodes.clone()).ok_or_else(|| "subscription group not found".into())
    }

    pub fn node(&self, group_id: &str, node_id: &str) -> Result<VlessNode, String> {
        let s = self.inner.lock().map_err(|_| "store lock poisoned".to_string())?;
        s.groups.iter().find(|g| g.id == group_id).and_then(|g| g.nodes.iter().find(|n| n.id == node_id)).cloned().ok_or_else(|| "node not found".into())
    }

    fn save_locked(&self, state: &PersistedState) -> Result<(), String> {
        let bytes = serde_json::to_vec_pretty(state).map_err(|e| format!("failed to serialize state: {e}"))?;
        let temp = self.path.with_extension("json.tmp");
        let mut file = fs::File::create(&temp).map_err(|e| format!("failed to create state temp file: {e}"))?;
        use std::io::Write;
        file.write_all(&bytes).map_err(|e| format!("failed to write state: {e}"))?;
        file.sync_all().map_err(|e| format!("failed to flush state: {e}"))?;
        replace_file(&temp, &self.path)
    }
}

fn build_group_view(state: &PersistedState, group: &SubscriptionGroup) -> GroupView {
    let mut view = GroupView::from(group);
    view.sort_mode = state.sort_modes.get(&group.id).copied().unwrap_or_default();
    for node in &mut view.nodes {
        if let Some(latency) = state.latencies.get(&node.id) {
            node.latency_ms = latency.latency_ms;
            node.latency_failed = latency.failed;
        }
    }
    sort_nodes(&mut view.nodes, view.sort_mode);
    view
}

fn normalize_selection(s: &mut PersistedState) {
    let valid = s.selected_group_id.as_deref().zip(s.selected_node_id.as_deref()).is_some_and(|(gid, nid)| s.groups.iter().any(|g| g.id == gid && g.nodes.iter().any(|n| n.id == nid)));
    if valid { return; }
    if let Some((gid, nid)) = s.groups.iter().find_map(|g| g.nodes.first().map(|n| (g.id.clone(), n.id.clone()))) {
        s.selected_group_id = Some(gid);
        s.selected_node_id = Some(nid);
    } else {
        s.selected_group_id = None;
        s.selected_node_id = None;
    }
}

fn prune_runtime_metadata(s: &mut PersistedState) {
    let valid_node_ids: std::collections::HashSet<String> = s.groups.iter().flat_map(|group| group.nodes.iter().map(|node| node.id.clone())).collect();
    let valid_group_ids: std::collections::HashSet<String> = s.groups.iter().map(|group| group.id.clone()).collect();
    s.latencies.retain(|node_id, _| valid_node_ids.contains(node_id));
    s.sort_modes.retain(|group_id, _| valid_group_ids.contains(group_id));
}

#[cfg(windows)]
fn replace_file(source: &Path, target: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH};
    let src: Vec<u16> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let dst: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let ok = unsafe { MoveFileExW(src.as_ptr(), dst.as_ptr(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) };
    if ok == 0 { return Err(format!("failed to commit state: {}", std::io::Error::last_os_error())); }
    Ok(())
}

#[cfg(not(windows))]
fn replace_file(source: &Path, target: &Path) -> Result<(), String> {
    if target.exists() { fs::remove_file(target).map_err(|e| format!("failed to replace state: {e}"))?; }
    fs::rename(source, target).map_err(|e| format!("failed to commit state: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn old_state_without_preferences_or_node_metadata_migrates() {
        let state: PersistedState = serde_json::from_str(r#"{"groups":[]}"#).unwrap();
        assert_eq!(state.preferences.theme, AppTheme::Amoled);
        assert!(state.preferences.close_to_tray);
        assert!(state.latencies.is_empty());
        assert!(state.sort_modes.is_empty());
    }
}
