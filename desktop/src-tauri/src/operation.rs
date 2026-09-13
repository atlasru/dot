use std::sync::{Arc, atomic::{AtomicBool, Ordering}};

/// Reserves a connection-changing or URL-test operation, including tray actions.
pub struct Operation(Arc<AtomicBool>);
impl Operation {
    pub fn acquire(flag: &Arc<AtomicBool>) -> Result<Self, String> {
        flag.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| "wait for the current connection or URL test operation to finish".to_string())?;
        Ok(Self(Arc::clone(flag)))
    }
}
impl Drop for Operation { fn drop(&mut self) { self.0.store(false, Ordering::SeqCst); } }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_overlap_and_releases_on_scope_exit() {
        let flag = Arc::new(AtomicBool::new(false));
        let first = Operation::acquire(&flag).unwrap();
        assert!(Operation::acquire(&flag).is_err());
        drop(first);
        assert!(Operation::acquire(&flag).is_ok());
    }
}
