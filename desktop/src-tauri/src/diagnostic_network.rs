use std::{
    future::Future,
    net::{IpAddr, SocketAddr, ToSocketAddrs},
    sync::atomic::AtomicBool,
    time::{Duration, Instant},
};

use crate::diagnostics::cancelled;

#[cfg(windows)]
use std::os::windows::io::AsRawSocket;
#[cfg(windows)]
use windows_sys::Win32::{
    Foundation::{GetLastError, ERROR_ACCESS_DENIED, ERROR_NOT_SUPPORTED, INVALID_HANDLE_VALUE},
    NetworkManagement::{IpHelper::*, Ndis::IfOperStatusUp},
    Networking::WinSock::*,
};

pub(crate) async fn within<T>(
    deadline: Instant,
    cancel: &AtomicBool,
    timeout_error: &str,
    work: impl Future<Output = Result<T, String>>,
) -> Result<T, String> {
    let mut work = std::pin::pin!(work);
    loop {
        cancelled(cancel)?;
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(timeout_error.into());
        }
        let interval = remaining.min(Duration::from_millis(25));
        if let Ok(result) = tokio::time::timeout(interval, &mut work).await {
            cancelled(cancel)?;
            return result;
        }
    }
}

// The system resolver and the synchronous ICMP API can finish after the caller stops
// waiting. Keep their slots until they return, including after cancellation.
async fn blocking<T: Send + 'static>(
    work: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    static SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(8);
    let slot = SLOTS
        .acquire()
        .await
        .map_err(|_| "Diagnostics workers unavailable")?;
    tauri::async_runtime::spawn_blocking(move || {
        let _slot = slot;
        work()
    })
    .await
    .map_err(|_| "Diagnostics worker failed")?
}

pub(crate) async fn resolve(host: &str, port: u16) -> Result<Vec<SocketAddr>, String> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }
    let host = host.to_owned();
    blocking(move || {
        let addresses: Vec<_> = (host.as_str(), port)
            .to_socket_addrs()
            .map_err(|_| "Server DNS lookup failed")?
            .collect();
        if addresses.is_empty() {
            return Err("Server DNS lookup returned no addresses".into());
        }
        Ok(addresses)
    })
    .await
}

#[derive(Clone)]
pub(crate) struct Egress {
    #[cfg(windows)]
    index: u32,
    pub(crate) name: String,
}

#[cfg(windows)]
pub(crate) fn egress(address: SocketAddr) -> Result<Option<Egress>, String> {
    if address.ip().is_loopback() {
        return Ok(None);
    }
    let family = if address.is_ipv4() { AF_INET } else { AF_INET6 };
    let mut table = std::ptr::null_mut();
    // The IP Helper API owns this variable-length array until FreeMibTable.
    if unsafe { GetIpForwardTable2(family, &mut table) } != 0 || table.is_null() {
        return Err("Diagnostics could not read the Windows routing table".into());
    }
    struct ForwardTable(*mut MIB_IPFORWARD_TABLE2);
    impl Drop for ForwardTable {
        fn drop(&mut self) {
            unsafe { FreeMibTable(self.0.cast()) }
        }
    }
    let table = ForwardTable(table);
    let rows = unsafe {
        std::slice::from_raw_parts(
            std::ptr::addr_of!((*table.0).Table).cast::<MIB_IPFORWARD_ROW2>(),
            (*table.0).NumEntries as usize,
        )
    };
    rows.iter()
        .filter(|route| {
            !route.Loopback
                && prefix_contains(
                    ip_from_sockaddr(&route.DestinationPrefix.Prefix),
                    route.DestinationPrefix.PrefixLength,
                    address.ip(),
                )
        })
        .filter_map(|route| {
            let mut interface = MIB_IF_ROW2 {
                InterfaceLuid: route.InterfaceLuid,
                ..Default::default()
            };
            let mut ip_interface = MIB_IPINTERFACE_ROW {
                Family: family,
                InterfaceLuid: route.InterfaceLuid,
                ..Default::default()
            };
            if unsafe { GetIfEntry2(&mut interface) } != 0
                || interface.OperStatus != IfOperStatusUp
                || interface.Type == IF_TYPE_SOFTWARE_LOOPBACK
                || unsafe { GetIpInterfaceEntry(&mut ip_interface) } != 0
            {
                return None;
            }
            let length = interface
                .Alias
                .iter()
                .position(|&ch| ch == 0)
                .unwrap_or(interface.Alias.len());
            let name = String::from_utf16_lossy(&interface.Alias[..length]);
            if name.eq_ignore_ascii_case(crate::config::TUN_INTERFACE_NAME) {
                return None;
            }
            let index = route.InterfaceIndex;
            let score = (
                std::cmp::Reverse(route.DestinationPrefix.PrefixLength),
                route.Metric.saturating_add(ip_interface.Metric),
            );
            Some((score, Egress { index, name }))
        })
        .min_by_key(|(score, _)| *score)
        .map(|(_, interface)| Some(interface))
        .ok_or_else(|| "No underlying Windows route for this node".into())
}

#[cfg(not(windows))]
pub(crate) fn egress(_: SocketAddr) -> Result<Option<Egress>, String> {
    Ok(None)
}

#[cfg(windows)]
fn prefix_contains(prefix: Option<IpAddr>, bits: u8, address: IpAddr) -> bool {
    match (prefix, address) {
        (Some(IpAddr::V4(prefix)), IpAddr::V4(address)) => {
            bits == 0
                || (bits <= 32 && (u32::from(prefix) ^ u32::from(address)) >> (32 - bits) == 0)
        }
        (Some(IpAddr::V6(prefix)), IpAddr::V6(address)) => {
            bits == 0
                || (bits <= 128 && (u128::from(prefix) ^ u128::from(address)) >> (128 - bits) == 0)
        }
        _ => false,
    }
}

#[cfg(windows)]
fn ip_from_sockaddr(address: &SOCKADDR_INET) -> Option<IpAddr> {
    unsafe {
        match address.si_family {
            AF_INET => Some(IpAddr::from(
                address.Ipv4.sin_addr.S_un.S_addr.to_ne_bytes(),
            )),
            AF_INET6 => Some(IpAddr::from(address.Ipv6.sin6_addr.u.Byte)),
            _ => None,
        }
    }
}

#[cfg(windows)]
fn sockaddr(address: SocketAddr) -> SOCKADDR_INET {
    match address {
        SocketAddr::V4(address) => SOCKADDR_INET {
            Ipv4: SOCKADDR_IN {
                sin_family: AF_INET,
                sin_port: address.port().to_be(),
                sin_addr: IN_ADDR {
                    S_un: IN_ADDR_0 {
                        S_addr: u32::from_ne_bytes(address.ip().octets()),
                    },
                },
                ..Default::default()
            },
        },
        SocketAddr::V6(address) => SOCKADDR_INET {
            Ipv6: SOCKADDR_IN6 {
                sin6_family: AF_INET6,
                sin6_port: address.port().to_be(),
                sin6_flowinfo: address.flowinfo(),
                sin6_addr: IN6_ADDR {
                    u: IN6_ADDR_0 {
                        Byte: address.ip().octets(),
                    },
                },
                Anonymous: SOCKADDR_IN6_0 {
                    sin6_scope_id: address.scope_id(),
                },
            },
        },
    }
}

pub(crate) async fn tcp(address: SocketAddr, egress: Option<&Egress>) -> Result<(), String> {
    let socket = if address.is_ipv4() {
        tokio::net::TcpSocket::new_v4()
    } else {
        tokio::net::TcpSocket::new_v6()
    }
    .map_err(|_| "TCP socket unavailable")?;
    #[cfg(windows)]
    if let Some(interface) = egress {
        let (level, option, index) = if address.is_ipv4() {
            (IPPROTO_IP, IP_UNICAST_IF, interface.index.to_be())
        } else {
            (IPPROTO_IPV6, IPV6_UNICAST_IF, interface.index)
        };
        if unsafe {
            setsockopt(
                socket.as_raw_socket() as _,
                level,
                option,
                (&index as *const u32).cast(),
                4,
            )
        } != 0
        {
            return Err("TCP could not bind the underlying Windows interface".into());
        }
    }
    socket
        .connect(address)
        .await
        .map(|_| ())
        .map_err(|_| "TCP connect failed".into())
}

#[cfg(windows)]
pub(crate) async fn icmp(
    mut address: SocketAddr,
    egress: Option<Egress>,
    deadline: Instant,
) -> Result<u64, String> {
    blocking(move || {
        address.set_port(0);
        let destination = sockaddr(address);
        let mut source = destination;
        if let Some(interface) = egress {
            let mut route = MIB_IPFORWARD_ROW2::default();
            if unsafe {
                GetBestRoute2(
                    std::ptr::null(),
                    interface.index,
                    std::ptr::null(),
                    &destination,
                    0,
                    &mut route,
                    &mut source,
                )
            } != 0
            {
                return Err("No underlying Windows route for this node".into());
            }
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err("ICMP timed out".into());
        }
        let timeout = (remaining.as_millis() as u32).max(1);
        echo(address.is_ipv4(), &source, &destination, timeout)
    })
    .await
}

#[cfg(not(windows))]
pub(crate) async fn icmp(_: SocketAddr, _: Option<Egress>, _: Instant) -> Result<u64, String> {
    Err("ICMP unavailable on this platform".into())
}

#[cfg(windows)]
fn echo(
    ipv4: bool,
    source: &SOCKADDR_INET,
    destination: &SOCKADDR_INET,
    timeout: u32,
) -> Result<u64, String> {
    let handle = unsafe {
        if ipv4 {
            IcmpCreateFile()
        } else {
            Icmp6CreateFile()
        }
    };
    if handle == INVALID_HANDLE_VALUE {
        return Err("ICMP unavailable on this Windows network stack".into());
    }
    struct EchoHandle(windows_sys::Win32::Foundation::HANDLE);
    impl Drop for EchoHandle {
        fn drop(&mut self) {
            unsafe { IcmpCloseHandle(self.0) };
        }
    }
    let handle = EchoHandle(handle);
    let request = b"dot";
    // Aligned buffer large enough for either reply struct, the request, and ICMP error data.
    let mut reply = [0u64; 16];
    let buffer = reply.as_mut_ptr().cast();
    let size = std::mem::size_of_val(&reply) as u32;
    let replies = unsafe {
        if ipv4 {
            IcmpSendEcho2Ex(
                handle.0,
                std::ptr::null_mut(),
                None,
                std::ptr::null(),
                source.Ipv4.sin_addr.S_un.S_addr,
                destination.Ipv4.sin_addr.S_un.S_addr,
                request.as_ptr().cast(),
                request.len() as u16,
                std::ptr::null(),
                buffer,
                size,
                timeout,
            )
        } else {
            Icmp6SendEcho2(
                handle.0,
                std::ptr::null_mut(),
                None,
                std::ptr::null(),
                &source.Ipv6,
                &destination.Ipv6,
                request.as_ptr().cast(),
                request.len() as u16,
                std::ptr::null(),
                buffer,
                size,
                timeout,
            )
        }
    };
    if replies == 0 {
        return Err(match unsafe { GetLastError() } {
            ERROR_NOT_SUPPORTED | ERROR_ACCESS_DENIED => {
                "ICMP unavailable on this Windows network stack"
            }
            IP_REQ_TIMED_OUT => "ICMP timed out",
            _ => "ICMP echo failed; proxy health unknown",
        }
        .into());
    }
    let (status, ms) = unsafe {
        if ipv4 {
            let reply = &*buffer.cast::<ICMP_ECHO_REPLY>();
            (reply.Status, reply.RoundTripTime)
        } else {
            let reply = &*buffer.cast::<ICMPV6_ECHO_REPLY_LH>();
            (reply.Status, reply.RoundTripTime)
        }
    };
    if status != IP_SUCCESS {
        return Err("ICMP blocked or timed out; proxy health unknown".into());
    }
    Ok(u64::from(ms).max(1))
}
