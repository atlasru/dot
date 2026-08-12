use std::{sync::{Arc, RwLock, Weak}, thread, time::{Duration, Instant}};

use crate::model::{EnginePhase, EngineSnapshot, TrafficSnapshot};

#[cfg(windows)]
use std::{ffi::c_void, ptr::null_mut, slice};
#[cfg(windows)]
use windows_sys::Win32::NetworkManagement::IpHelper::{FreeMibTable, GetIfTable2, MIB_IF_TABLE2};

pub fn spawn_traffic_sampler(engine: &Arc<RwLock<EngineSnapshot>>, traffic: &Arc<RwLock<TrafficSnapshot>>) {
    let engine: Weak<RwLock<EngineSnapshot>> = Arc::downgrade(engine);
    let traffic: Weak<RwLock<TrafficSnapshot>> = Arc::downgrade(traffic);
    thread::spawn(move || {
        let mut baseline: Option<(u64, u64)> = None;
        let mut previous: Option<(u64, u64, Instant)> = None;
        let mut connected_at: Option<Instant> = None;
        loop {
            thread::sleep(Duration::from_secs(1));
            let (Some(engine), Some(traffic)) = (engine.upgrade(), traffic.upgrade()) else { break; };
            let connected = read_engine(&engine).phase == EnginePhase::Connected;
            if !connected {
                baseline = None;
                previous = None;
                connected_at = None;
                write_traffic(&traffic, TrafficSnapshot::default());
                continue;
            }

            let now = Instant::now();
            let Ok(Some((rx, tx))) = interface_octets("dot0") else { continue; };
            let base = *baseline.get_or_insert((rx, tx));
            let start = *connected_at.get_or_insert(now);
            let (down_rate, up_rate) = previous.map(|(prx, ptx, at)| {
                let elapsed = now.duration_since(at).as_secs_f64().max(0.001);
                (((rx.saturating_sub(prx)) as f64 / elapsed) as u64, ((tx.saturating_sub(ptx)) as f64 / elapsed) as u64)
            }).unwrap_or((0, 0));
            previous = Some((rx, tx, now));
            write_traffic(&traffic, TrafficSnapshot {
                download_bytes_per_second: down_rate,
                upload_bytes_per_second: up_rate,
                session_download_bytes: rx.saturating_sub(base.0),
                session_upload_bytes: tx.saturating_sub(base.1),
                connected_seconds: now.duration_since(start).as_secs(),
            });
        }
    });
}

fn read_engine(value: &RwLock<EngineSnapshot>) -> EngineSnapshot {
    match value.read() { Ok(v) => v.clone(), Err(p) => p.into_inner().clone() }
}
fn write_traffic(value: &RwLock<TrafficSnapshot>, next: TrafficSnapshot) {
    match value.write() { Ok(mut v) => *v = next, Err(p) => *p.into_inner() = next }
}

#[cfg(windows)]
fn interface_octets(alias: &str) -> Result<Option<(u64, u64)>, String> {
    let mut table: *mut MIB_IF_TABLE2 = null_mut();
    let status = unsafe { GetIfTable2(&mut table) };
    if status != 0 { return Err(format!("GetIfTable2 failed with status {status}")); }
    if table.is_null() { return Ok(None); }

    let result = unsafe {
        let count = (*table).NumEntries as usize;
        let rows = slice::from_raw_parts((*table).Table.as_ptr(), count);
        rows.iter().find(|row| wide_string(&row.Alias).eq_ignore_ascii_case(alias) || wide_string(&row.Description).eq_ignore_ascii_case("dot.")).map(|row| (row.InOctets, row.OutOctets))
    };
    unsafe { FreeMibTable(table as *const c_void) };
    Ok(result)
}

#[cfg(not(windows))]
fn interface_octets(_alias: &str) -> Result<Option<(u64, u64)>, String> { Ok(None) }

#[cfg(windows)]
fn wide_string(value: &[u16]) -> String {
    let len = value.iter().position(|c| *c == 0).unwrap_or(value.len());
    String::from_utf16_lossy(&value[..len])
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[test]
    fn windows_interface_table_can_be_queried() {
        interface_octets("__dot_interface_that_does_not_exist__").expect("GetIfTable2 should enumerate interfaces");
    }
}
