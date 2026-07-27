    // FPS 输出 socket。
    //
    // App 启动悬浮窗时会创建 abstract unix socket，并把 socket name/token 写进 fps.cmd。
    // daemon 连接后先发送 hello token，App 验证通过后持续接收每秒 FPS。
    //
    // socket 不可用时不要让 FPS 线程失败；直接返回 Err，由 monitor.write_fps 回退到文件输出。
    struct FpsSocket {
        name: Option<String>,
        token: Option<String>,
        fd: Option<i32>,
        disabled: bool,
    }

    impl FpsSocket {
        fn new(name: Option<String>, token: Option<String>) -> Self {
            Self {
                name,
                token,
                fd: None,
                disabled: false,
            }
        }

        fn send_fps(&mut self, fps: f64) -> io::Result<()> {
            if self.disabled || self.name.is_none() || self.token.is_none() {
                return Err(io::Error::new(
                    io::ErrorKind::NotConnected,
                    "fps socket disabled",
                ));
            }
            if self.fd.is_none() {
                self.connect()?;
            }
            let line = format!("{fps:.1}\n");
            let Some(fd) = self.fd else {
                return Err(io::Error::new(
                    io::ErrorKind::NotConnected,
                    "fps socket missing fd",
                ));
            };
            match socket_send_nowait(fd, line.as_bytes()) {
                Ok(SocketSendResult::Complete) => {}
                Ok(SocketSendResult::WouldBlock) => {
                    // App 一时来不及读取时直接丢本次样本，避免改走文件通道增加 IO。
                }
                Ok(SocketSendResult::Partial) => {
                    // 流式协议不能留下半行；断开后由下一次样本重新握手。
                    self.close();
                }
                Err(err) => {
                    self.disabled = true;
                    self.close();
                    return Err(err);
                }
            }
            Ok(())
        }

        fn connect(&mut self) -> io::Result<()> {
            let name = self.name.as_deref().unwrap_or_default();
            let token = self.token.as_deref().unwrap_or_default();
            let fd = unix_connect_abstract(name)?;
            let hello = format!("hello {token}\n");
            let hello_result = socket_send_nowait(fd, hello.as_bytes());
            match hello_result {
                Ok(SocketSendResult::Complete) => {}
                Ok(SocketSendResult::WouldBlock | SocketSendResult::Partial) => {
                    close_fd(fd);
                    return Err(io::Error::new(
                        io::ErrorKind::WouldBlock,
                        "fps socket handshake incomplete",
                    ));
                }
                Err(err) => {
                    close_fd(fd);
                    return Err(err);
                }
            }
            self.fd = Some(fd);
            Ok(())
        }

        fn close(&mut self) {
            if let Some(fd) = self.fd.take() {
                close_fd(fd);
            }
        }
    }

    impl Drop for FpsSocket {
        fn drop(&mut self) {
            self.close();
        }
    }

    fn cstr_lossy(ptr: *const std::os::raw::c_char) -> String {
        if ptr.is_null() {
            return String::new();
        }
        unsafe { CStr::from_ptr(ptr) }
            .to_string_lossy()
            .into_owned()
    }

    fn unix_connect_abstract(name: &str) -> io::Result<i32> {
        let fd = create_unix_socket()?;
        let (addr, addr_len) = match abstract_sockaddr(name) {
            Ok(address) => address,
            Err(err) => {
                close_fd(fd);
                return Err(err);
            }
        };
        let rc = unsafe { connect(fd, &addr, addr_len) };
        if rc == 0 {
            return Ok(fd);
        }

        let err = io::Error::last_os_error();
        if !err.raw_os_error().is_some_and(|code| {
            code == libc::EINPROGRESS || code == libc::EAGAIN || code == libc::EWOULDBLOCK
        }) {
            close_fd(fd);
            return Err(err);
        }

        let started = Instant::now();
        let timeout = Duration::from_millis(150);
        loop {
            let elapsed = started.elapsed();
            if elapsed >= timeout {
                close_fd(fd);
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "fps socket connect timeout",
                ));
            }
            let remaining_ms = timeout
                .saturating_sub(elapsed)
                .as_millis()
                .clamp(1, i32::MAX as u128) as i32;
            let mut poll_fd = libc::pollfd {
                fd,
                events: libc::POLLOUT,
                revents: 0,
            };
            let poll_rc = unsafe { libc::poll(&mut poll_fd, 1, remaining_ms) };
            if poll_rc < 0 {
                let poll_err = io::Error::last_os_error();
                if poll_err.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                close_fd(fd);
                return Err(poll_err);
            }
            if poll_rc == 0 {
                continue;
            }

            let mut socket_error = 0i32;
            let mut socket_error_len = mem::size_of::<i32>() as libc::socklen_t;
            let opt_rc = unsafe {
                libc::getsockopt(
                    fd,
                    libc::SOL_SOCKET,
                    libc::SO_ERROR,
                    (&mut socket_error as *mut i32).cast(),
                    &mut socket_error_len,
                )
            };
            if opt_rc != 0 {
                let opt_err = io::Error::last_os_error();
                close_fd(fd);
                return Err(opt_err);
            }
            if socket_error == 0 {
                return Ok(fd);
            }
            close_fd(fd);
            return Err(io::Error::from_raw_os_error(socket_error));
        }
    }

    fn create_unix_socket() -> io::Result<i32> {
        let fd = unsafe { socket(AF_UNIX, SOCK_STREAM, 0) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let descriptor_flags = unsafe { libc::fcntl(fd, libc::F_GETFD) };
        let status_flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if descriptor_flags < 0
            || status_flags < 0
            || unsafe { libc::fcntl(fd, libc::F_SETFD, descriptor_flags | libc::FD_CLOEXEC) } < 0
            || unsafe { libc::fcntl(fd, libc::F_SETFL, status_flags | libc::O_NONBLOCK) } < 0
        {
            let err = io::Error::last_os_error();
            close_fd(fd);
            return Err(err);
        }
        Ok(fd)
    }

    fn abstract_sockaddr(name: &str) -> io::Result<(SockAddrUn, u32)> {
        let bytes = name.as_bytes();
        if bytes.is_empty() || bytes.len() + 1 > 108 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "abstract socket name too long",
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

    enum SocketSendResult {
        Complete,
        WouldBlock,
        Partial,
    }

    fn socket_send_nowait(fd: i32, data: &[u8]) -> io::Result<SocketSendResult> {
        loop {
            let sent = unsafe {
                send(
                    fd,
                    data.as_ptr().cast(),
                    data.len(),
                    MSG_NOSIGNAL | MSG_DONTWAIT,
                )
            };
            if sent == data.len() as isize {
                return Ok(SocketSendResult::Complete);
            }
            if sent >= 0 {
                return Ok(SocketSendResult::Partial);
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if err.kind() == io::ErrorKind::WouldBlock {
                return Ok(SocketSendResult::WouldBlock);
            }
            return Err(err);
        }
    }

    fn close_fd(fd: i32) {
        unsafe {
            close(fd);
        }
    }

    #[repr(C)]
    struct SockAddrUn {
        sun_family: u16,
        sun_path: [i8; 108],
    }

    const AF_UNIX: i32 = 1;
    const SOCK_STREAM: i32 = 1;
    const MSG_NOSIGNAL: i32 = 0x4000;
    const MSG_DONTWAIT: i32 = 0x40;

    unsafe extern "C" {
        fn socket(domain: i32, type_: i32, protocol: i32) -> i32;
        fn connect(fd: i32, addr: *const SockAddrUn, len: u32) -> i32;
        fn send(fd: i32, buf: *const std::ffi::c_void, len: usize, flags: i32) -> isize;
        fn close(fd: i32) -> i32;
    }
