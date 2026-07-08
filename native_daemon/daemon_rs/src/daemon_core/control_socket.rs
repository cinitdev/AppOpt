// 守护进程身份验证 socket。
//
// App 不能只靠进程名判断守护进程是否可用：项目开源后，其他二改版本也可能叫 AppOpt/AppOptRs。
// 因此这里提供 abstract unix socket 验证：
// - App 连到固定 socket，发送 ping + callback socket + token。
// - daemon 反连 App 提供的 callback socket 并带回 token。
// - App 收到匹配 token 后，才能确认这是当前模块里的守护进程。
//
// 这套逻辑只做身份验证，不承载 FPS 数据；FPS 另有独立 socket。
fn start_daemon_socket_thread() {
    thread::spawn(|| {
        if let Err(err) = daemon_socket_thread() {
            eprintln!("[CTRL] 守护验证 socket 已停止: {err}");
        }
    });
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn daemon_socket_ping_client(callback_name: &str, callback_token: &str) -> io::Result<()> {
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
fn daemon_socket_ping_client(_callback_name: &str, _callback_token: &str) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "abstract unix socket 仅支持 Android/Linux",
    ))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn daemon_socket_thread() -> io::Result<()> {
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

    println!("[CTRL] 守护验证 socket 已监听: @{DAEMON_SOCKET_NAME}");

    loop {
        let client_fd = unsafe { accept(server_fd, std::ptr::null_mut(), std::ptr::null_mut()) };
        if client_fd < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            eprintln!("[CTRL] 守护验证 socket accept 失败: {err}");
            thread::sleep(Duration::from_millis(200));
            continue;
        }
        daemon_socket_handle_client(client_fd);
        close_fd(client_fd);
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn daemon_socket_thread() -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "abstract unix socket 仅支持 Android/Linux",
    ))
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn daemon_socket_handle_client(client_fd: i32) {
    let mut buf = [0u8; 160];
    let n = unsafe { recv(client_fd, buf.as_mut_ptr().cast(), buf.len() - 1, 0) };
    if n <= 0 {
        eprintln!("[CTRL] 守护验证 socket 收包失败");
        return;
    }
    let req = String::from_utf8_lossy(&buf[..n as usize])
        .trim()
        .to_string();
    if !req.starts_with(DAEMON_SOCKET_PING_PREFIX) {
        eprintln!("[CTRL] 守护验证 socket 收到未知请求: {req}");
        return;
    }

    let callback_name = match request_field(&req, "callback") {
        Some(value) => value,
        None => {
            eprintln!("[CTRL] 守护验证 socket 缺少 callback");
            return;
        }
    };
    let callback_token = match request_field(&req, "token") {
        Some(value) => value,
        None => {
            eprintln!("[CTRL] 守护验证 socket 缺少 token");
            return;
        }
    };

    if let Err(err) = daemon_socket_send_callback(&callback_name, &callback_token) {
        eprintln!("[CTRL] 守护反向验证回调失败: {err}");
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn request_field(req: &str, key: &str) -> Option<String> {
    let prefix = format!("{key}=");
    req.split_whitespace()
        .find_map(|part| part.strip_prefix(&prefix).map(|value| value.to_string()))
        .filter(|value| !value.is_empty())
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn daemon_socket_send_callback(callback_name: &str, callback_token: &str) -> io::Result<()> {
    let fd = unix_connect_abstract(callback_name)?;
    let resp = format!(
        "{DAEMON_SOCKET_CALLBACK} token={callback_token} version={VERSION} pid={}\n",
        std::process::id()
    );
    let result = socket_send_all(fd, resp.as_bytes());
    close_fd(fd);
    result
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn create_unix_socket() -> io::Result<i32> {
    let fd = unsafe { socket(AF_UNIX, SOCK_STREAM, 0) };
    if fd >= 0 {
        Ok(fd)
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn unix_connect_abstract(name: &str) -> io::Result<i32> {
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
fn abstract_sockaddr(name: &str) -> io::Result<(SockAddrUn, u32)> {
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
fn socket_send_all(fd: i32, data: &[u8]) -> io::Result<()> {
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
fn close_fd(fd: i32) {
    unsafe {
        close(fd);
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[repr(C)]
struct SockAddrUn {
    sun_family: u16,
    sun_path: [i8; 108],
}

#[cfg(any(target_os = "android", target_os = "linux"))]
const AF_UNIX: i32 = 1;
#[cfg(any(target_os = "android", target_os = "linux"))]
const SOCK_STREAM: i32 = 1;
#[cfg(any(target_os = "android", target_os = "linux"))]
const MSG_NOSIGNAL: i32 = 0x4000;

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
