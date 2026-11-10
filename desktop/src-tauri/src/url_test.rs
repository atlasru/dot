use std::{
    fs,
    net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream},
    path::Path,
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Mutex, MutexGuard, TryLockError,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use reqwest::{Client, Proxy};

use crate::{
    config::build_url_test_config,
    diagnostics::{cancelled, measure, network, PingResult},
    job::ProcessJob,
    model::ProxyNode,
    settings::{PingMethod, PingSettings},
};

pub fn test_active_connection(settings: &PingSettings, cancel: &AtomicBool) -> PingResult {
    measure(settings, true, cancel, || {
        http_attempt(settings, None, cancel)
    })
}

pub fn test_node(
    runtime_source: &Path,
    work_dir: &Path,
    node: &ProxyNode,
    settings: &PingSettings,
    cancel: &AtomicBool,
) -> PingResult {
    if !settings.method.proxy() {
        return crate::diagnostics::direct(node, settings, cancel);
    }
    match test_proxy(runtime_source, work_dir, node, settings, cancel) {
        Ok(result) => result,
        Err(error) => measure(settings, false, cancel, || Err(error.clone())),
    }
}

fn test_proxy(
    runtime_source: &Path,
    work_dir: &Path,
    node: &ProxyNode,
    settings: &PingSettings,
    cancel: &AtomicBool,
) -> Result<PingResult, String> {
    cancelled(cancel)?;
    fs::create_dir_all(work_dir).map_err(|_| "Failed to create diagnostics directory")?;
    let xray = runtime_source.join("xray.exe");
    if !xray.is_file() {
        return Err("Xray runtime is incomplete".into());
    }

    let interface = tauri::async_runtime::block_on(network::within(
        Instant::now() + Duration::from_secs(settings.timeout_seconds),
        cancel,
        "Server DNS lookup timed out",
        async {
            let addresses = network::resolve(&node.host, node.port).await?;
            addresses
                .into_iter()
                .find_map(|address| network::egress(address).ok())
                .ok_or_else(|| "No underlying Windows route for this node".into())
        },
    ))?;

    // Serialize port handoffs until Xray listens so another probe cannot reserve the same port.
    let startup = reserve_proxy_start(cancel)?;
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .map_err(|_| "Failed to reserve diagnostics proxy port")?;
    let address = listener
        .local_addr()
        .map_err(|_| "Failed to inspect diagnostics proxy port")?;
    let mut config = build_url_test_config(node, address.port())?;
    if let Some(interface) = interface {
        config["outbounds"][1]["streamSettings"]["sockopt"]["interface"] =
            serde_json::json!(interface.name);
    }
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let serial = NEXT.fetch_add(1, Ordering::Relaxed);
    let config_path = work_dir.join(format!(
        "url-test-{}-{stamp}-{serial}.json",
        std::process::id()
    ));
    let _config_file = ConfigFile(config_path.clone());
    fs::write(
        &config_path,
        serde_json::to_vec(&config).map_err(|_| "Failed to serialize diagnostics configuration")?,
    )
    .map_err(|_| "Failed to write diagnostics configuration")?;

    cancelled(cancel)?;
    let job = ProcessJob::kill_on_close()?;
    drop(listener);
    let mut child = command(&xray, work_dir)
        .args(["run", "-config"])
        .arg(&config_path)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| "Failed to start diagnostics Xray")?;

    if let Err(error) = job
        .assign(&child)
        .and_then(|_| wait_for_proxy(&mut child, address, cancel))
    {
        terminate(job, &mut child);
        return Err(error);
    }
    drop(startup);
    let result = Proxy::all(format!("http://{address}"))
        .map_err(|_| "Failed to configure diagnostics HTTP proxy".into())
        .map(|proxy| {
            measure(settings, false, cancel, || {
                http_attempt(settings, Some(proxy.clone()), cancel)
            })
        });
    terminate(job, &mut child);
    result
}

fn reserve_proxy_start(cancel: &AtomicBool) -> Result<MutexGuard<'static, ()>, String> {
    static STARTING: Mutex<()> = Mutex::new(());
    loop {
        cancelled(cancel)?;
        match STARTING.try_lock() {
            Ok(guard) => return Ok(guard),
            Err(TryLockError::WouldBlock) => thread::sleep(Duration::from_millis(20)),
            Err(TryLockError::Poisoned(_)) => {
                return Err("Diagnostics proxy startup unavailable".into())
            }
        }
    }
}

struct ConfigFile(std::path::PathBuf);

impl Drop for ConfigFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn http_attempt(
    settings: &PingSettings,
    proxy: Option<Proxy>,
    cancel: &AtomicBool,
) -> Result<u64, String> {
    tauri::async_runtime::block_on(async {
        let client = client(settings, proxy)?;
        let deadline = Instant::now() + Duration::from_secs(settings.timeout_seconds);
        network::within(
            deadline,
            cancel,
            "HTTP request timed out",
            measure_request(&client, settings),
        )
        .await
    })
}

fn client(settings: &PingSettings, proxy: Option<Proxy>) -> Result<Client, String> {
    let mut builder = Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .retry(reqwest::retry::never())
        .connect_timeout(Duration::from_secs(settings.timeout_seconds))
        .timeout(Duration::from_secs(settings.timeout_seconds));
    if let Some(proxy) = proxy {
        builder = builder.proxy(proxy);
    }
    builder
        .build()
        .map_err(|_| "Failed to initialize diagnostics HTTP client".into())
}

async fn measure_request(client: &Client, settings: &PingSettings) -> Result<u64, String> {
    let request = if settings.method == PingMethod::ProxyHead {
        client.head(&settings.url)
    } else {
        client.get(&settings.url)
    };
    let started = Instant::now();
    let mut response = request
        .header("Cache-Control", "no-cache")
        .send()
        .await
        .map_err(|error| {
            if error.is_timeout() {
                "HTTP request timed out"
            } else {
                "HTTP request failed (DNS, TLS or proxy)"
            }
        })?;
    let code = response.status().as_u16();
    if settings.method == PingMethod::ProxyHead && code == 405 {
        return Err("HEAD unsupported by test server (405); use GET; proxy health unknown".into());
    }
    if !(200..300).contains(&code) {
        return Err(format!("Test URL returned HTTP {code}"));
    }
    if settings.method == PingMethod::ProxyGet {
        let mut size = 0;
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| "HTTP response body failed or timed out")?
        {
            size += chunk.len();
            if size > 1_048_576 {
                return Err("Test response exceeds 1 MiB; use a small test URL".into());
            }
        }
    }
    Ok(started.elapsed().as_millis().max(1) as u64)
}

fn wait_for_proxy(
    child: &mut Child,
    address: SocketAddr,
    cancel: &AtomicBool,
) -> Result<(), String> {
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        cancelled(cancel)?;
        if child
            .try_wait()
            .map_err(|_| "Failed to inspect diagnostics Xray")?
            .is_some()
        {
            return Err("Diagnostics Xray exited during startup".into());
        }
        if TcpStream::connect_timeout(&address, Duration::from_millis(50)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err("Diagnostics proxy did not become ready".into())
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{BufRead, BufReader, Write},
        sync::mpsc,
    };

    fn accept(listener: &TcpListener) -> TcpStream {
        listener.set_nonblocking(true).unwrap();
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            match listener.accept() {
                Ok((stream, _)) => {
                    stream.set_nonblocking(false).unwrap();
                    stream
                        .set_read_timeout(Some(Duration::from_secs(5)))
                        .unwrap();
                    return stream;
                }
                Err(error)
                    if error.kind() == std::io::ErrorKind::WouldBlock
                        && Instant::now() < deadline =>
                {
                    thread::sleep(Duration::from_millis(10));
                }
                Err(error) => panic!("diagnostics test server did not accept a request: {error}"),
            }
        }
    }

    fn server(
        code: u16,
        expected: &'static str,
        pause: Duration,
    ) -> (String, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/test", listener.local_addr().unwrap());
        let handle = thread::spawn(move || {
            let mut stream = accept(&listener);
            let mut request_line = String::new();
            BufReader::new(&mut stream)
                .read_line(&mut request_line)
                .unwrap();
            assert!(request_line.starts_with(expected));
            thread::sleep(pause);
            let _ = write!(
                stream,
                "HTTP/1.1 {code} Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            );
        });
        (url, handle)
    }

    #[test]
    fn get_head_and_405_are_distinct() {
        for (method, expected, code) in [
            (PingMethod::ProxyGet, "GET /test", 204),
            (PingMethod::ProxyHead, "HEAD /test", 204),
            (PingMethod::ProxyHead, "HEAD /test", 405),
            (PingMethod::ProxyGet, "GET /test", 503),
        ] {
            let (url, handle) = server(code, expected, Duration::ZERO);
            let settings = PingSettings {
                method,
                url,
                ..PingSettings::default()
            };
            let result = http_attempt(&settings, None, &AtomicBool::new(false));
            if code == 204 {
                assert!(result.is_ok());
            } else if code == 405 {
                assert!(result.unwrap_err().starts_with("HEAD unsupported"));
            } else {
                assert!(result.is_err());
            }
            handle.join().unwrap();
        }
    }

    #[test]
    fn default_https_request_requires_a_connect_through_the_proxy() {
        let (url, handle) = server(503, "CONNECT cp.cloudflare.com:443", Duration::ZERO);
        let proxy = Proxy::all(url.trim_end_matches("/test")).unwrap();
        let result = http_attempt(
            &PingSettings::default(),
            Some(proxy),
            &AtomicBool::new(false),
        );
        assert!(result.is_err());
        handle.join().unwrap();
    }

    #[test]
    fn http_attempt_honors_deadline_and_cancellation() {
        let (url, handle) = server(204, "GET /test", Duration::from_millis(200));
        let settings = PingSettings {
            url,
            ..PingSettings::default()
        };
        let timeout = tauri::async_runtime::block_on(async {
            let http = client(&settings, None).unwrap();
            network::within(
                Instant::now() + Duration::from_millis(50),
                &AtomicBool::new(false),
                "HTTP request timed out",
                measure_request(&http, &settings),
            )
            .await
        });
        assert!(timeout.unwrap_err().contains("timed out"));
        handle.join().unwrap();

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let settings = PingSettings {
            url: format!("http://{}/test", listener.local_addr().unwrap()),
            ..PingSettings::default()
        };
        let cancel = AtomicBool::new(false);
        thread::scope(|scope| {
            let (tx, rx) = mpsc::channel();
            let request = scope.spawn(|| http_attempt(&settings, None, &cancel));
            let accepted = scope.spawn(move || {
                let stream = accept(&listener);
                tx.send(()).unwrap();
                stream
            });
            rx.recv_timeout(Duration::from_secs(2)).unwrap();
            let started = Instant::now();
            cancel.store(true, Ordering::SeqCst);
            assert_eq!(
                request.join().unwrap().unwrap_err(),
                "diagnostics cancelled"
            );
            assert!(started.elapsed() < Duration::from_secs(1));
            drop(accepted.join().unwrap());
        });
    }

    #[test]
    #[cfg(windows)]
    fn get_traverses_the_selected_vless_outbound() {
        let xray = std::env::var_os("DOT_XRAY_EXE")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("runtime/xray.exe"));
        if !xray.is_file() {
            return;
        }
        let work_dir =
            std::env::temp_dir().join(format!("dot-diagnostics-fixture-{}", std::process::id()));
        fs::create_dir_all(&work_dir).unwrap();
        struct WorkDir(std::path::PathBuf);
        impl Drop for WorkDir {
            fn drop(&mut self) {
                let _ = fs::remove_dir(&self.0);
            }
        }
        let _work_dir = WorkDir(work_dir.clone());
        let (origin, origin_thread) = server(204, "GET /test", Duration::ZERO);
        let origin = origin
            .trim_start_matches("http://")
            .trim_end_matches("/test");
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let id = "11111111-1111-4111-8111-111111111111";
        let server_config = serde_json::json!({
            "log": {"loglevel": "none"},
            "inbounds": [{
                "listen": "127.0.0.1", "port": address.port(), "protocol": "vless",
                "settings": { "clients": [{ "id": id }], "decryption": "none" },
                "streamSettings": { "method": "ws", "wsSettings": { "path": "/dot-diagnostics" } }
            }],
            "outbounds": [{ "protocol": "freedom", "settings": {
                "redirect": origin,
                "finalRules": [{ "action": "allow", "ip": ["127.0.0.1/32"] }]
            } }]
        });
        let config_path = work_dir.join("server.json");
        let _server_config = ConfigFile(config_path.clone());
        fs::write(&config_path, serde_json::to_vec(&server_config).unwrap()).unwrap();
        let job = ProcessJob::kill_on_close().unwrap();
        drop(listener);
        let mut child = command(&xray, &work_dir)
            .args(["run", "-config"])
            .arg(config_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let outcome = (|| {
            let cancel = AtomicBool::new(false);
            job.assign(&child)?;
            wait_for_proxy(&mut child, address, &cancel)?;
            let node = crate::vless::parse_vless(&format!(
                "vless://{id}@{address}?encryption=none&type=ws&path=%2Fdot-diagnostics"
            )).unwrap();
            let settings = PingSettings {
                url: "http://diagnostic-target.invalid/test".into(),
                attempts: 1,
                ..PingSettings::default()
            };
            Ok(test_node(
                xray.parent().unwrap(),
                &work_dir,
                &node,
                &settings,
                &cancel,
            ))
        })();
        terminate(job, &mut child);
        let result: PingResult = outcome.unwrap_or_else(|error: String| panic!("{error}"));
        assert!(result.success, "{:?}", result.error);
        origin_thread.join().unwrap();
    }
}
