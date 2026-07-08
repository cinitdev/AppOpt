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
            if let Err(err) = socket_send_all(fd, line.as_bytes()) {
                self.disabled = true;
                self.close();
                return Err(err);
            }
            Ok(())
        }

        fn connect(&mut self) -> io::Result<()> {
            let name = self.name.as_deref().unwrap_or_default();
            let token = self.token.as_deref().unwrap_or_default();
            let fd = unix_connect_abstract(name)?;
            let hello = format!("hello {token}\n");
            socket_send_all(fd, hello.as_bytes())?;
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

    fn create_unix_socket() -> io::Result<i32> {
        let fd = unsafe { socket(AF_UNIX, SOCK_STREAM, 0) };
        if fd >= 0 {
            Ok(fd)
        } else {
            Err(io::Error::last_os_error())
        }
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

    unsafe extern "C" {
        fn socket(domain: i32, type_: i32, protocol: i32) -> i32;
        fn connect(fd: i32, addr: *const SockAddrUn, len: u32) -> i32;
        fn send(fd: i32, buf: *const std::ffi::c_void, len: usize, flags: i32) -> isize;
        fn close(fd: i32) -> i32;
    }
