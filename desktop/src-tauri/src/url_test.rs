use std::{
    fs,
    net::{TcpListener, TcpStream},
    path::Path,
    process::{Child, Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use reqwest::{blocking::Client, Proxy};

use crate::{config::build_url_test_config, job::ProcessJob, model::VlessNode};

const TEST_URL: &str = "http://cp.cloudflare.com/";

pub fn test_active_connection() -> Result<u64, String> {
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|e| format!("failed to initialize URL test: {e}"))?;
    measure_request(&client)
}

pub fn test_node(runtime_source: &Path, work_dir: &Path, node: &VlessNode) -> Result<u64, String> {
    fs::create_dir_all(work_dir).map_err(|e| format!("failed to create URL test directory: {e}"))?;
    let xray = runtime_source.join("xray.exe");
    if !xray.is_file() {
        return Err(format!("Xray runtime is incomplete at {}", runtime_source.display()));
    }

    let port = reserve_loopback_port()?;
    let config = build_url_test_config(node, port)?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let config_path = work_dir.join(format!("url-test-{}-{stamp}.json", std::process::id()));
    fs::write(
        &config_path,
        serde_json::to_vec_pretty(&config).map_err(|e| format!("failed to serialize URL test config: {e}"))?,
    )
    .map_err(|e| format!("failed to write URL test config: {e}"))?;

    let tested = command(&xray, work_dir)
        .args(["run", "-test", "-config"])
        .arg(&config_path)
        .output()
        .map_err(|e| format!("failed to validate URL test config: {e}"))?;
    if !tested.status.success() {
        let _ = fs::remove_file(&config_path);
        let stderr = String::from_utf8_lossy(&tested.stderr).trim().to_string();
        return Err(if stderr.is_empty() {
            "Xray rejected the URL test config".into()
        } else {
            stderr
        });
    }

    let mut child = command(&xray, work_dir)
        .args(["run", "-config"])
        .arg(&config_path)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("failed to start URL test Xray: {e}"))?;

    let job = ProcessJob::kill_on_close().map_err(|e| {
        let _ = child.kill();
        let _ = child.wait();
        e
    })?;
    if let Err(error) = job.assign(&child) {
        drop(job);
        let _ = child.kill();
        let _ = child.wait();
        let _ = fs::remove_file(&config_path);
        return Err(error);
    }

    let result = (|| {
        wait_for_proxy(&mut child, port)?;
        let proxy = Proxy::http(format!("http://127.0.0.1:{port}"))
            .map_err(|e| format!("failed to configure URL test proxy: {e}"))?;
        let client = Client::builder()
            .proxy(proxy)
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(5))
            .build()
            .map_err(|e| format!("failed to initialize URL test: {e}"))?;
        measure_request(&client)
    })();

    terminate(job, &mut child);
    let _ = fs::remove_file(&config_path);
    result
}

fn measure_request(client: &Client) -> Result<u64, String> {
    let started = Instant::now();
    let response = client
        .get(TEST_URL)
        .header("Cache-Control", "no-cache")
        .send()
        .map_err(|e| format!("URL test failed: {e}"))?;
    if !response.status().is_success() && response.status().as_u16() != 204 {
        return Err(format!("URL test returned HTTP {}", response.status().as_u16()));
    }
    Ok(started.elapsed().as_millis().max(1) as u64)
}

fn reserve_loopback_port() -> Result<u16, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .map_err(|e| format!("failed to reserve URL test port: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("failed to inspect URL test port: {e}"))?
        .port();
    drop(listener);
    Ok(port)
}

fn wait_for_proxy(child: &mut Child, port: u16) -> Result<(), String> {
    for _ in 0..30 {
        if TcpStream::connect_timeout(
            &format!("127.0.0.1:{port}").parse().map_err(|e| format!("invalid URL test address: {e}"))?,
            Duration::from_millis(80),
        )
        .is_ok()
        {
            return Ok(());
        }
        if let Some(status) = child
            .try_wait()
            .map_err(|e| format!("failed to inspect URL test Xray: {e}"))?
        {
            return Err(format!("URL test Xray exited during startup ({status})"));
        }
        thread::sleep(Duration::from_millis(50));
    }
    Err("URL test proxy did not become ready".into())
}

fn terminate(job: ProcessJob, child: &mut Child) {
    drop(job);
    if child.try_wait().ok().flatten().is_none() {
        let _ = child.kill();
    }
    let _ = child.wait();
}

fn command(xray: &Path, current_dir: &Path) -> Command {
    let mut value = Command::new(xray);
    value.current_dir(current_dir);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        value.creation_flags(0x08000000);
    }
    value
}
