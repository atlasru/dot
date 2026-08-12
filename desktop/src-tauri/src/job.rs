#![cfg(windows)]

use std::{
    ffi::c_void,
    mem::{size_of, zeroed},
    os::windows::io::AsRawHandle,
    process::Child,
    ptr::{null, null_mut},
};

use windows_sys::Win32::{
    Foundation::{CloseHandle, HANDLE},
    System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    },
};

/// Owns a Windows Job Object configured so every assigned process is terminated
/// when the last job handle is closed. This is the hard lifecycle boundary
/// between dot. and the Xray process tree.
pub struct ProcessJob {
    handle: HANDLE,
}

unsafe impl Send for ProcessJob {}

impl ProcessJob {
    pub fn kill_on_close() -> Result<Self, String> {
        let handle = unsafe { CreateJobObjectW(null(), null()) };
        if handle.is_null() {
            return Err(format!(
                "failed to create Windows process job: {}",
                std::io::Error::last_os_error()
            ));
        }

        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { zeroed() };
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;

        let configured = unsafe {
            SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION as *const c_void,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if configured == 0 {
            let error = std::io::Error::last_os_error();
            unsafe { CloseHandle(handle) };
            return Err(format!("failed to configure Windows process job: {error}"));
        }

        Ok(Self { handle })
    }

    pub fn assign(&self, child: &Child) -> Result<(), String> {
        let process = child.as_raw_handle() as HANDLE;
        if process.is_null() {
            return Err("Xray process handle is invalid".into());
        }

        let assigned = unsafe { AssignProcessToJobObject(self.handle, process) };
        if assigned == 0 {
            return Err(format!(
                "failed to attach Xray to Windows process job: {}",
                std::io::Error::last_os_error()
            ));
        }
        Ok(())
    }
}

impl Drop for ProcessJob {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe { CloseHandle(self.handle) };
            self.handle = null_mut();
        }
    }
}

#[cfg(test)]
mod tests {
    use std::{process::Command, thread, time::{Duration, Instant}};

    use super::*;

    #[test]
    fn closing_job_terminates_assigned_child() {
        let job = ProcessJob::kill_on_close().expect("create kill-on-close job");
        let mut child = Command::new("cmd.exe")
            .args(["/D", "/Q", "/C", "ping -n 30 127.0.0.1 >NUL"])
            .spawn()
            .expect("spawn harmless test child");
        job.assign(&child).expect("assign child to job");

        drop(job);
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            if child.try_wait().expect("query test child").is_some() {
                break;
            }
            if Instant::now() >= deadline {
                let _ = child.kill();
                panic!("child survived after kill-on-close job was dropped");
            }
            thread::sleep(Duration::from_millis(50));
        }
    }
}
