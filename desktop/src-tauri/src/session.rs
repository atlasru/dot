use std::{fs, path::{Path, PathBuf}, time::{SystemTime, UNIX_EPOCH}};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionRecord {
    pub pid: u32,
    pub node_id: String,
    pub node_name: String,
    pub xray_path: PathBuf,
    pub started_at_ms: u64,
}

pub struct SessionJournal {
    path: PathBuf,
}

impl SessionJournal {
    pub fn new(work_dir: &Path) -> Result<Self, String> {
        fs::create_dir_all(work_dir)
            .map_err(|e| format!("failed to create VPN work directory: {e}"))?;
        Ok(Self { path: work_dir.join("session.json") })
    }

    /// A stale journal means the previous dot. process did not get to execute
    /// its normal shutdown path. M2's Windows Job Object guarantees that its
    /// Xray process tree is killed when dot. disappears, so startup can safely
    /// discard this bookkeeping record without touching unrelated processes.
    pub fn clear_stale(&self) -> Result<Option<SessionRecord>, String> {
        if !self.path.exists() {
            return Ok(None);
        }
        let record = fs::read(&self.path)
            .ok()
            .and_then(|bytes| serde_json::from_slice::<SessionRecord>(&bytes).ok());
        self.clear()?;
        Ok(record)
    }

    pub fn write(&self, pid: u32, node_id: &str, node_name: &str, xray_path: &Path) -> Result<(), String> {
        let record = SessionRecord {
            pid,
            node_id: node_id.to_string(),
            node_name: node_name.to_string(),
            xray_path: xray_path.to_path_buf(),
            started_at_ms: now_ms(),
        };
        let bytes = serde_json::to_vec_pretty(&record)
            .map_err(|e| format!("failed to serialize VPN session journal: {e}"))?;
        let temp = self.path.with_extension("json.tmp");
        fs::write(&temp, bytes)
            .map_err(|e| format!("failed to write VPN session journal: {e}"))?;
        fs::rename(&temp, &self.path)
            .map_err(|e| format!("failed to commit VPN session journal: {e}"))?;
        Ok(())
    }

    pub fn clear(&self) -> Result<(), String> {
        match fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(format!("failed to clear VPN session journal: {error}")),
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_journal_is_consumed_once() {
        let root = std::env::temp_dir().join(format!("dot-session-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let journal = SessionJournal::new(&root).unwrap();
        journal.write(1234, "node-id", "node", Path::new("C:/dot/xray.exe")).unwrap();

        let recovered = journal.clear_stale().unwrap().expect("stale session record");
        assert_eq!(recovered.pid, 1234);
        assert!(journal.clear_stale().unwrap().is_none());
        let _ = fs::remove_dir_all(root);
    }
}
