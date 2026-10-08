use super::*;
// 守护进程身份验证套接字。
//
// App 不能只靠进程名判断守护进程是否可用：项目开源后，其他二改版本也可能叫 QixiaThreads/QiXiaRs。
// 因此这里通过抽象命名空间的 Unix 套接字验证身份：
// - App 连到固定套接字，发送 ping、回调套接字和 token。
// - 守护进程反连 App 提供的回调套接字并带回 token。
// - App 收到匹配 token 后，才能确认这是当前模块里的守护进程。
//
// 同一通道提供仅限 Root 的只读接管诊断；FPS 另有独立套接字。
pub(super) fn start_daemon_socket_thread() -> bool {
    match thread::Builder::new()
        .name("QiXiaRsControlSocket".to_string())
        .spawn(|| {
            if let Err(err) = daemon_socket_thread() {
                log_error!("[CTRL] 守护验证 socket 已停止: {err}");
            }
        }) {
        Ok(_) => true,
        Err(err) => {
            log_error!("[CTRL] 守护验证 socket 线程创建失败: {err}");
            false
        }
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn daemon_socket_ping_client(callback_name: &str, callback_token: &str) -> io::Result<()> {
    if callback_name.is_empty() || callback_token.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "守护验证需要 callback socket 和 token",
        ));
    }
    let fd = unix_connect_abstract(DAEMON_SOCKET_NAME)?;
    let req = format!(
        "{DAEMON_SOCKET_PING_PREFIX} source=reverse callback={callback_name} token={callback_token}\n"
    );
    let result = socket_send_all(fd, req.as_bytes());
    close_fd(fd);
    result
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(super) fn daemon_socket_ping_client(_callback_name: &str, _callback_token: &str) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "abstract unix socket 仅支持 Android/Linux",
    ))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn daemon_socket_thread() -> io::Result<()> {
    let server_fd = create_unix_socket()?;
    let (addr, addr_len) = abstract_sockaddr(DAEMON_SOCKET_NAME)?;

    let bind_rc = unsafe { bind(server_fd, &addr, addr_len) };
    if bind_rc != 0 {
        let err = io::Error::last_os_error();
        close_fd(server_fd);
        return Err(err);
    }

    let listen_rc = unsafe { listen(server_fd, 8) };
    if listen_rc != 0 {
        let err = io::Error::last_os_error();
        close_fd(server_fd);
        return Err(err);
    }

    log_info!("[CTRL] 守护验证 socket 已监听: @{DAEMON_SOCKET_NAME}");

    loop {
        let client_fd = unsafe { accept(server_fd, std::ptr::null_mut(), std::ptr::null_mut()) };
        if client_fd < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            log_error!("[CTRL] 守护验证 socket accept 失败: {err}");
            thread::sleep(Duration::from_millis(200));
            continue;
        }
        if let Err(err) = socket_set_recv_timeout(client_fd, Duration::from_secs(2)) {
            log_error!("[CTRL] 守护验证 socket 设置接收超时失败: {err}");
            close_fd(client_fd);
            continue;
        }
        daemon_socket_handle_client(client_fd);
        close_fd(client_fd);
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(super) fn daemon_socket_thread() -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "abstract unix socket 仅支持 Android/Linux",
    ))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn daemon_socket_handle_client(client_fd: i32) {
    let req = match socket_recv_line(client_fd, 1024) {
        Ok(req) => req,
        Err(err) => {
            log_error!("[CTRL] 守护验证 socket 收包失败: {err}");
            return;
        }
    };
    if let Some(pkg) = req.strip_prefix("qixia.diagnostics v1 ") {
        let mut credentials: libc::ucred = unsafe { std::mem::zeroed() };
        let mut size = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        let verified = unsafe { libc::getsockopt(client_fd, libc::SOL_SOCKET, libc::SO_PEERCRED,
            (&mut credentials as *mut libc::ucred).cast(), &mut size) } == 0;
        if !verified || credentials.uid != 0 { return; }
        let timeout = libc::timeval { tv_sec: 2, tv_usec: 0 };
        unsafe { libc::setsockopt(client_fd, libc::SOL_SOCKET, libc::SO_SNDTIMEO,
            (&timeout as *const libc::timeval).cast(), std::mem::size_of::<libc::timeval>() as libc::socklen_t); }
        if let Ok(report) = auto_affinity::diagnostics::query(pkg) { let _ = socket_send_all(client_fd, report.as_bytes()); }
        return;
    }
    let valid_prefix = req
        .strip_prefix(DAEMON_SOCKET_PING_PREFIX)
        .is_some_and(|rest| rest.is_empty() || rest.as_bytes()[0].is_ascii_whitespace());
    if !valid_prefix {
        log_error!("[CTRL] 守护验证 socket 收到未知请求: {req}");
        return;
    }

    let callback_name = match request_field(&req, "callback") {
        Some(value) => value,
        None => {
            log_error!("[CTRL] 守护验证 socket 缺少 callback");
            return;
        }
    };
    let callback_token = match request_field(&req, "token") {
        Some(value) => value,
        None => {
            log_error!("[CTRL] 守护验证 socket 缺少 token");
            return;
        }
    };

    if let Err(err) = daemon_socket_send_callback(&callback_name, &callback_token) {
        log_error!("[CTRL] 守护反向验证回调失败: {err}");
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn daemon_socket_diagnostics_client(pkg: &str) -> io::Result<()> {
    use std::io::Write;
    let fd = unix_connect_abstract(DAEMON_SOCKET_NAME)?;
    let result = (|| {
        socket_set_recv_timeout(fd, Duration::from_secs(4))?;
        socket_send_all(fd, format!("qixia.diagnostics v1 {pkg}\n").as_bytes())?;
        let mut bytes = Vec::new();
        let mut buffer = [0u8; 8192];
        loop {
            let count = unsafe { libc::recv(fd, buffer.as_mut_ptr().cast(), buffer.len(), 0) };
            if count == 0 { break; }
            if count < 0 { return Err(io::Error::last_os_error()); }
            bytes.extend_from_slice(&buffer[..count as usize]);
            if bytes.len() > 2 * 1024 * 1024 { return Err(io::Error::other("诊断响应过大")); }
        }
        if !bytes.starts_with(b"QIXIA_DIAG\t1\t") || !bytes.ends_with(b"END\n") {
            return Err(io::Error::other("当前守护未提供完整诊断"));
        }
        io::stdout().write_all(&bytes)
    })();
    close_fd(fd);
    result
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn socket_set_recv_timeout(fd: i32, timeout: Duration) -> io::Result<()> {
    let value = libc::timeval {
        tv_sec: timeout.as_secs().try_into().unwrap_or(libc::time_t::MAX),
        tv_usec: i64::from(timeout.subsec_micros()) as libc::suseconds_t,
    };
    let rc = unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            (&value as *const libc::timeval).cast(),
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn socket_recv_line(fd: i32, max_len: usize) -> io::Result<String> {
    let mut request = Vec::with_capacity(160);
    let mut buf = [0u8; 256];
    loop {
        let n = unsafe { recv(fd, buf.as_mut_ptr().cast(), buf.len(), 0) };
        if n > 0 {
            let chunk = &buf[..n as usize];
            if let Some(newline) = chunk.iter().position(|byte| *byte == b'\n') {
                if request.len().saturating_add(newline) > max_len {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "请求行过长"));
                }
                request.extend_from_slice(&chunk[..newline]);
                return Ok(String::from_utf8_lossy(&request).trim().to_string());
            }
            if request.len().saturating_add(chunk.len()) > max_len {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "请求行过长"));
            }
            request.extend_from_slice(chunk);
            continue;
        }
        if n == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "请求未以换行结束",
            ));
        }
        let err = io::Error::last_os_error();
        if err.kind() == io::ErrorKind::Interrupted {
            continue;
        }
        return Err(err);
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn request_field(req: &str, key: &str) -> Option<String> {
    let prefix = format!("{key}=");
    req.split_whitespace()
        .find_map(|part| part.strip_prefix(&prefix).map(|value| value.to_string()))
        .filter(|value| !value.is_empty())
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn daemon_socket_send_callback(callback_name: &str, callback_token: &str) -> io::Result<()> {
    let fd = unix_connect_abstract(callback_name)?;
    let resp = format!(
        "{DAEMON_SOCKET_CALLBACK} token={callback_token} version={VERSION} kind=rust pid={}\n",
        std::process::id()
    );
    let result = socket_send_all(fd, resp.as_bytes());
    close_fd(fd);
    result
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn create_unix_socket() -> io::Result<i32> {
    let fd = unsafe { socket(AF_UNIX, SOCK_STREAM, 0) };
    if fd >= 0 {
        Ok(fd)
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn unix_connect_abstract(name: &str) -> io::Result<i32> {
    let fd = create_unix_socket()?;
    let (addr, addr_len) = abstract_sockaddr(name)?;
    let rc = unsafe { connect(fd, &addr, addr_len) };
    if rc == 0 {
        Ok(fd)
    } else {
        let err = io::Error::last_os_error();
        close_fd(fd);
        Err(err)
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn abstract_sockaddr(name: &str) -> io::Result<(SockAddrUn, u32)> {
    let bytes = name.as_bytes();
    if bytes.is_empty() || bytes.len() + 1 > 108 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "abstract socket 名称过长",
        ));
    }

    let mut addr = SockAddrUn {
        sun_family: AF_UNIX as u16,
        sun_path: [0; 108],
    };
    for (idx, byte) in bytes.iter().enumerate() {
        addr.sun_path[idx + 1] = *byte as i8;
    }
    let addr_len = (mem::size_of::<u16>() + 1 + bytes.len()) as u32;
    Ok((addr, addr_len))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn socket_send_all(fd: i32, data: &[u8]) -> io::Result<()> {
    let mut off = 0usize;
    while off < data.len() {
        let sent = unsafe {
            send(
                fd,
                data[off..].as_ptr().cast(),
                data.len() - off,
                MSG_NOSIGNAL,
            )
        };
        if sent > 0 {
            off += sent as usize;
            continue;
        }
        let err = io::Error::last_os_error();
        if err.kind() == io::ErrorKind::Interrupted {
            continue;
        }
        return Err(err);
    }
    Ok(())
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn close_fd(fd: i32) {
    unsafe {
        close(fd);
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[repr(C)]
pub(super) struct SockAddrUn {
    pub(super) sun_family: u16,
    pub(super) sun_path: [i8; 108],
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) const AF_UNIX: i32 = 1;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) const SOCK_STREAM: i32 = 1;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) const MSG_NOSIGNAL: i32 = 0x4000;

#[cfg(any(target_os = "android", target_os = "linux"))]
unsafe extern "C" {
    fn socket(domain: i32, type_: i32, protocol: i32) -> i32;
    fn connect(fd: i32, addr: *const SockAddrUn, len: u32) -> i32;
    fn bind(fd: i32, addr: *const SockAddrUn, len: u32) -> i32;
    fn listen(fd: i32, backlog: i32) -> i32;
    fn accept(fd: i32, addr: *mut std::ffi::c_void, len: *mut u32) -> i32;
    fn send(fd: i32, buf: *const std::ffi::c_void, len: usize, flags: i32) -> isize;
    fn recv(fd: i32, buf: *mut std::ffi::c_void, len: usize, flags: i32) -> isize;
    fn close(fd: i32) -> i32;
}

#[cfg(all(test, any(target_os = "android", target_os = "linux")))]
mod diagnostic_socket_tests {
    use super::*;

    // 分别由设备 shell 与 Root 执行，验证实际对端身份；不会启动或修改运行中的守护。
    #[test]
    fn diagnostics_socket_checks_peer_and_returns_complete_readonly_report() {
        let mut pair = [-1; 2];
        assert_eq!(unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, pair.as_mut_ptr()) }, 0);
        socket_set_recv_timeout(pair[0], Duration::from_secs(2)).unwrap();
        socket_set_recv_timeout(pair[1], Duration::from_secs(2)).unwrap();
        socket_send_all(pair[0], b"qixia.diagnostics v1 com.qixia.sockettest\n").unwrap();
        daemon_socket_handle_client(pair[1]);
        close_fd(pair[1]);
        let mut bytes = [0u8; 4096];
        let count = unsafe { libc::recv(pair[0], bytes.as_mut_ptr().cast(), bytes.len(), 0) };
        close_fd(pair[0]);
        if unsafe { libc::geteuid() } == 0 {
            assert!(count > 0);
            let reply = std::str::from_utf8(&bytes[..count as usize]).unwrap();
            assert!(reply.starts_with("QIXIA_DIAG\t1\tcom.qixia.sockettest\t"));
            assert!(reply.ends_with("END\n"));
        } else {
            assert_eq!(count, 0, "非 Root 不应读取诊断");
        }
    }
}
