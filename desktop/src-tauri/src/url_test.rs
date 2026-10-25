use std::{
    fs,
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    io::Read,
    net::{TcpListener, TcpStream},
    path::Path,
    process::{Child, Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use reqwest::{blocking::Client, Proxy};

use crate::{config::build_url_test_config, job::ProcessJob, model::ProxyNode, settings::{PingSettings, PingMethod}, diagnostics::{PingResult, measure, cancelled}};

pub fn test_active_connection(settings: &PingSettings, cancel: &AtomicBool) -> PingResult {
    measure(settings, true, cancel, || {
        let client = client(settings, None)?;
        measure_request(&client, settings)
    })
}

pub fn test_node(runtime_source: &Path, work_dir: &Path, node: &ProxyNode, settings: &PingSettings, cancel: &AtomicBool) -> PingResult {
    if !settings.method.proxy() { return crate::diagnostics::direct(node, settings, cancel); }
    match test_proxy(runtime_source, work_dir, node, settings, cancel) {
        Ok(result) => result,
        Err(error) => measure(settings, false, cancel, || Err(error.clone())),
    }
}

fn test_proxy(runtime_source: &Path, work_dir: &Path, node: &ProxyNode, settings: &PingSettings, cancel: &AtomicBool) -> Result<PingResult, String> {
    cancelled(cancel)?;
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
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let serial=NEXT.fetch_add(1,Ordering::Relaxed);
    let config_path = work_dir.join(format!("url-test-{}-{stamp}-{serial}.json", std::process::id()));
    let _config_file = ConfigFile(config_path.clone());
    fs::write(
        &config_path,
        serde_json::to_vec_pretty(&config).map_err(|e| format!("failed to serialize URL test config: {e}"))?,
    )
    .map_err(|e| format!("failed to write URL test config: {e}"))?;

    cancelled(cancel)?;
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
        wait_for_proxy(&mut child, port, cancel)?;
        let proxy = Proxy::all(format!("http://127.0.0.1:{port}"))
            .map_err(|_| "failed to configure diagnostics proxy")?;
        Ok(measure(settings, false, cancel, || {
            // Each attempt opens a fresh HTTP/TLS connection; core startup is excluded.
            let client = client(settings, Some(proxy.clone()))?;
            measure_request(&client, settings)
        }))
    })();
    terminate(job, &mut child);
    result
}

struct ConfigFile(std::path::PathBuf);
impl Drop for ConfigFile { fn drop(&mut self) { let _ = fs::remove_file(&self.0); } }

fn client(settings: &PingSettings, proxy: Option<Proxy>) -> Result<Client, String> {
    let mut builder = Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(settings.timeout_seconds))
        .timeout(Duration::from_secs(settings.timeout_seconds));
    if let Some(proxy) = proxy { builder = builder.proxy(proxy); }
    builder.build().map_err(|_| "failed to initialize diagnostics HTTP client".into())
}

fn measure_request(client: &Client, settings: &PingSettings) -> Result<u64, String> {
    let request = if settings.method == PingMethod::ProxyHead { client.head(&settings.url) } else { client.get(&settings.url) };
    let started = Instant::now();
    let response = request.header("Cache-Control", "no-cache").send().map_err(|e| if e.is_timeout() { "HTTP request timed out" } else { "HTTP request failed (DNS, TLS or proxy)" })?;
    let code = response.status().as_u16();
    if settings.method == PingMethod::ProxyHead && code == 405 { return Err("HEAD unsupported by test server (405); use GET; proxy health unknown".into()); }
    if !(200..300).contains(&code) { return Err(format!("test URL returned HTTP {code}")); }
    if settings.method == PingMethod::ProxyGet {
        let size = std::io::copy(&mut response.take(1_048_577), &mut std::io::sink()).map_err(|_| "HTTP response body failed or timed out")?;
        if size > 1_048_576 { return Err("test response exceeds 1 MiB; use a small test URL".into()); }
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

fn wait_for_proxy(child: &mut Child, port: u16, cancel: &AtomicBool) -> Result<(), String> {
    for _ in 0..100 {
        cancelled(cancel)?;
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

#[cfg(test)] mod tests {
    use super::*;
    use std::io::{Read,Write};
    fn server(code: u16, expected: &'static str, pause: Duration) -> (String, std::thread::JoinHandle<()>) {
        let listener=TcpListener::bind("127.0.0.1:0").unwrap();
        let url=format!("http://{}/test",listener.local_addr().unwrap());
        let handle=std::thread::spawn(move || {
            let (mut stream,_)=listener.accept().unwrap();
            let mut bytes=[0;4096]; let n=stream.read(&mut bytes).unwrap();
            assert!(String::from_utf8_lossy(&bytes[..n]).starts_with(expected));
            std::thread::sleep(pause);
            let _=write!(stream,"HTTP/1.1 {code} Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        }); (url,handle)
    }
    #[test] fn get_head_and_405_are_distinct() {
        for (method,expected,code) in [(PingMethod::ProxyGet,"GET /test",204),(PingMethod::ProxyHead,"HEAD /test",204),(PingMethod::ProxyHead,"HEAD /test",405),(PingMethod::ProxyGet,"GET /test",503)] {
            let (url,handle)=server(code,expected,Duration::ZERO);
            let mut s=PingSettings::default(); s.method=method; s.url=url;
            let result=measure_request(&client(&s,None).unwrap(),&s);
            if code==204 { assert!(result.is_ok()); } else if code==405 { assert!(result.unwrap_err().starts_with("HEAD unsupported")); } else { assert!(result.is_err()); }
            handle.join().unwrap();
        }
    }
    #[test] fn timeout_is_a_failure() {
        let (url,handle)=server(204,"GET /test",Duration::from_millis(200));
        let mut s=PingSettings::default(); s.url=url;
        let http=Client::builder().no_proxy().timeout(Duration::from_millis(30)).build().unwrap();
        assert!(measure_request(&http,&s).unwrap_err().contains("timed out")); handle.join().unwrap();
    }
}
