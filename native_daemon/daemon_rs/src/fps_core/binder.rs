    // 轻量 Binder 客户端，只服务于 SurfaceFlinger dump。
    //
    // 这里没有使用 dumpsys 命令，也没有依赖 Android Java API：
    // - 打开 /dev/binder
    // - 向 servicemanager 查询 SurfaceFlinger handle
    // - 对 SurfaceFlinger 发 dump transaction，并通过 pipe 取回文本
    //
    // 这段代码和 Android binder ABI 强相关，改动时要同时在 Android 12-16 上验证。
    struct BinderSf {
        fd: i32,
        map: *mut libc::c_void,
        map_size: usize,
        sf_handle: u32,
        have_sf: bool,
    }

    impl BinderSf {
        fn new() -> io::Result<Self> {
            let path = CString::new("/dev/binder").map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidInput, "invalid binder device path")
            })?;
            let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
            if fd < 0 {
                return Err(io::Error::last_os_error());
            }

            let mut version = BinderVersion {
                protocol_version: 0,
            };
            let version_rc =
                unsafe { libc::ioctl(fd, BINDER_VERSION, &mut version as *mut BinderVersion) };
            if version_rc < 0 {
                let err = io::Error::last_os_error();
                close_fd(fd);
                return Err(err);
            }
            if version.protocol_version != BINDER_CURRENT_PROTOCOL_VERSION {
                close_fd(fd);
                return Err(io::Error::new(
                    io::ErrorKind::Other,
                    format!(
                        "binder protocol {} != {}",
                        version.protocol_version, BINDER_CURRENT_PROTOCOL_VERSION
                    ),
                ));
            }

            let map_size = 128 * 1024;
            let map = unsafe {
                libc::mmap(
                    ptr::null_mut(),
                    map_size,
                    libc::PROT_READ,
                    libc::MAP_PRIVATE,
                    fd,
                    0,
                )
            };
            if map == libc::MAP_FAILED {
                let err = io::Error::last_os_error();
                close_fd(fd);
                return Err(err);
            }

            let mut max_threads = 0u32;
            let _ =
                unsafe { libc::ioctl(fd, BINDER_SET_MAX_THREADS, &mut max_threads as *mut u32) };

            let mut binder = Self {
                fd,
                map,
                map_size,
                sf_handle: 0,
                have_sf: false,
            };
            binder.resolve_surfaceflinger()?;
            Ok(binder)
        }

        fn dump(&mut self, args: &[&str]) -> io::Result<String> {
            if !self.have_sf {
                self.resolve_surfaceflinger()?;
            }

            let mut pfd = [0i32; 2];
            if unsafe { libc::pipe(pfd.as_mut_ptr()) } != 0 {
                return Err(io::Error::last_os_error());
            }
            let read_fd = pfd[0];
            let write_fd = pfd[1];

            let reader = thread::spawn(move || {
                let mut out = Vec::with_capacity(64 * 1024);
                let mut buf = [0u8; 4096];
                loop {
                    let rc = unsafe { libc::read(read_fd, buf.as_mut_ptr().cast(), buf.len()) };
                    if rc > 0 {
                        out.extend_from_slice(&buf[..rc as usize]);
                    } else if rc == 0 {
                        break;
                    } else {
                        let err = io::Error::last_os_error();
                        if err.kind() == io::ErrorKind::Interrupted {
                            continue;
                        }
                        break;
                    }
                }
                close_fd(read_fd);
                out
            });

            let result = (|| {
                let mut parcel = Parcel::with_capacity(1024);
                parcel.write_fd(write_fd);
                parcel.write_i32(args.len() as i32);
                for arg in args {
                    parcel.write_str16(arg);
                }
                if parcel.bad {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "SurfaceFlinger dump parcel overflow",
                    ));
                }
                self.transact(self.sf_handle, SF_DUMP_CODE, &parcel, 512)
                    .map(|_| ())
            })();

            close_fd(write_fd);
            let output = reader.join().unwrap_or_default();
            if let Err(err) = result {
                self.have_sf = false;
                return Err(err);
            }
            Ok(String::from_utf8_lossy(&output).into_owned())
        }

        fn resolve_surfaceflinger(&mut self) -> io::Result<()> {
            let mut parcel = Parcel::with_capacity(256);
            parcel.write_iface("android.os.IServiceManager");
            parcel.write_str16("SurfaceFlinger");
            if parcel.bad {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "service manager parcel overflow",
                ));
            }
            let reply = self.transact(0, SVC_CHECK_CODE, &parcel, 512)?;
            for offset in (0..reply.data.len()).step_by(4) {
                if offset + mem::size_of::<FlatBinderObject>() > reply.data.len() {
                    break;
                }
                let obj = unsafe {
                    ptr::read_unaligned(reply.data.as_ptr().add(offset).cast::<FlatBinderObject>())
                };
                let type_ = obj.hdr.type_;
                if type_ == BINDER_TYPE_HANDLE || type_ == BINDER_TYPE_WEAK_HANDLE {
                    self.sf_handle = unsafe { obj.data.handle };
                    self.have_sf = true;
                    println!("[FPS][binder] SurfaceFlinger handle={}", self.sf_handle);
                    return Ok(());
                }
            }
            Err(io::Error::new(
                io::ErrorKind::NotFound,
                "SurfaceFlinger binder handle not found",
            ))
        }

        fn transact(
            &mut self,
            handle: u32,
            code: u32,
            input: &Parcel,
            reply_cap: usize,
        ) -> io::Result<TransactionReply> {
            let tr = BinderTransactionData {
                target: BinderTarget { handle },
                cookie: 0,
                code,
                flags: 0,
                sender_pid: 0,
                sender_euid: 0,
                data_size: input.buf.len() as BinderSize,
                offsets_size: (input.offsets.len() * mem::size_of::<BinderSize>()) as BinderSize,
                data: BinderData {
                    ptr: BinderDataPtr {
                        buffer: input.buf.as_ptr() as BinderUintPtr,
                        offsets: input.offsets.as_ptr() as BinderUintPtr,
                    },
                },
            };
            let mut write_buf = Vec::with_capacity(4 + mem::size_of::<BinderTransactionData>());
            push_u32(&mut write_buf, BC_TRANSACTION);
            push_struct(&mut write_buf, &tr);

            let mut wrote = false;
            let mut read_buf = [0u8; 4096];
            for _ in 0..64 {
                let mut bwr = BinderWriteRead {
                    write_size: if wrote {
                        0
                    } else {
                        write_buf.len() as BinderSize
                    },
                    write_consumed: 0,
                    write_buffer: if wrote {
                        0
                    } else {
                        write_buf.as_ptr() as BinderUintPtr
                    },
                    read_size: read_buf.len() as BinderSize,
                    read_consumed: 0,
                    read_buffer: read_buf.as_mut_ptr() as BinderUintPtr,
                };
                let rc = unsafe {
                    libc::ioctl(self.fd, BINDER_WRITE_READ, &mut bwr as *mut BinderWriteRead)
                };
                if rc < 0 {
                    let err = io::Error::last_os_error();
                    if err.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(err);
                }
                if bwr.write_consumed >= write_buf.len() as BinderSize {
                    wrote = true;
                }
                if let Some(reply) =
                    self.parse_binder_read(&read_buf[..bwr.read_consumed as usize], reply_cap)?
                {
                    if reply.status != 0 {
                        return Err(io::Error::new(
                            io::ErrorKind::Other,
                            format!("binder transaction status {}", reply.status),
                        ));
                    }
                    return Ok(reply);
                }
            }
            Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "binder transaction timed out",
            ))
        }

        fn parse_binder_read(
            &mut self,
            mut data: &[u8],
            reply_cap: usize,
        ) -> io::Result<Option<TransactionReply>> {
            while data.len() >= 4 {
                let cmd = read_u32(data);
                data = &data[4..];
                match cmd {
                    BR_TRANSACTION_COMPLETE | BR_NOOP | BR_SPAWN_LOOPER => {}
                    BR_INCREFS | BR_ACQUIRE | BR_RELEASE | BR_DECREFS => {
                        data = skip_bytes(data, 2 * mem::size_of::<BinderUintPtr>())?;
                    }
                    BR_DEAD_BINDER | BR_CLEAR_DEATH_NOTIFICATION_DONE => {
                        data = skip_bytes(data, mem::size_of::<BinderUintPtr>())?;
                    }
                    BR_FROZEN_BINDER => {
                        data = skip_bytes(data, mem::size_of::<BinderUintPtr>() + 8)?;
                    }
                    BR_ERROR => {
                        let _ = skip_bytes(data, 4)?;
                        return Err(io::Error::new(io::ErrorKind::Other, "binder BR_ERROR"));
                    }
                    BR_DEAD_REPLY | BR_FAILED_REPLY | BR_FROZEN_REPLY => {
                        return Err(io::Error::new(
                            io::ErrorKind::Other,
                            "binder transaction failed",
                        ));
                    }
                    BR_REPLY => {
                        if data.len() < mem::size_of::<BinderTransactionData>() {
                            return Err(io::Error::new(
                                io::ErrorKind::UnexpectedEof,
                                "short binder reply",
                            ));
                        }
                        let rt = unsafe {
                            ptr::read_unaligned(data.as_ptr().cast::<BinderTransactionData>())
                        };
                        let reply = self.copy_reply(&rt, reply_cap);
                        self.retain_reply_handles(&rt);
                        self.free_buffer(unsafe { rt.data.ptr.buffer });
                        return Ok(Some(reply));
                    }
                    _ => {
                        return Err(io::Error::new(
                            io::ErrorKind::Other,
                            format!("unknown binder command 0x{cmd:08x}"),
                        ));
                    }
                }
            }
            Ok(None)
        }

        fn copy_reply(&self, rt: &BinderTransactionData, reply_cap: usize) -> TransactionReply {
            if (rt.flags & TF_STATUS_CODE) != 0 {
                let status = if rt.data_size >= 4 && unsafe { rt.data.ptr.buffer } != 0 {
                    unsafe { ptr::read_unaligned(rt.data.ptr.buffer as *const i32) }
                } else {
                    -1
                };
                return TransactionReply {
                    data: Vec::new(),
                    status,
                };
            }
            let len = (rt.data_size as usize).min(reply_cap);
            let src = unsafe { rt.data.ptr.buffer };
            let data = if src != 0 && len > 0 {
                unsafe { slice::from_raw_parts(src as *const u8, len).to_vec() }
            } else {
                Vec::new()
            };
            TransactionReply { data, status: 0 }
        }

        fn retain_reply_handles(&mut self, rt: &BinderTransactionData) {
            if rt.offsets_size == 0 {
                return;
            }
            let offsets = unsafe { rt.data.ptr.offsets };
            let buffer = unsafe { rt.data.ptr.buffer };
            if offsets == 0 || buffer == 0 {
                return;
            }
            let count = rt.offsets_size as usize / mem::size_of::<BinderSize>();
            for idx in 0..count {
                let off = unsafe { ptr::read_unaligned((offsets as *const BinderSize).add(idx)) };
                let obj = unsafe { ptr::read_unaligned((buffer + off) as *const FlatBinderObject) };
                let type_ = obj.hdr.type_;
                if type_ == BINDER_TYPE_HANDLE {
                    let handle = unsafe { obj.data.handle };
                    let _ = self.acquire_handle(handle);
                }
            }
        }

        fn acquire_handle(&mut self, handle: u32) -> io::Result<()> {
            let mut cmd = Vec::with_capacity(8);
            push_u32(&mut cmd, BC_ACQUIRE);
            push_u32(&mut cmd, handle);
            self.write_command(&cmd)
        }

        fn free_buffer(&mut self, buffer: BinderUintPtr) {
            if buffer == 0 {
                return;
            }
            let mut cmd = Vec::with_capacity(4 + mem::size_of::<BinderUintPtr>());
            push_u32(&mut cmd, BC_FREE_BUFFER);
            push_binder_uintptr(&mut cmd, buffer);
            let _ = self.write_command(&cmd);
        }

        fn write_command(&mut self, cmd: &[u8]) -> io::Result<()> {
            let mut bwr = BinderWriteRead {
                write_size: cmd.len() as BinderSize,
                write_consumed: 0,
                write_buffer: cmd.as_ptr() as BinderUintPtr,
                read_size: 0,
                read_consumed: 0,
                read_buffer: 0,
            };
            let rc = unsafe { libc::ioctl(self.fd, BINDER_WRITE_READ, &mut bwr) };
            if rc < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        }
    }

    impl Drop for BinderSf {
        fn drop(&mut self) {
            if !self.map.is_null() && self.map != libc::MAP_FAILED {
                unsafe {
                    libc::munmap(self.map, self.map_size);
                }
            }
            if self.fd >= 0 {
                close_fd(self.fd);
            }
        }
    }

    struct Parcel {
        buf: Vec<u8>,
        offsets: Vec<BinderSize>,
        bad: bool,
    }

    impl Parcel {
        fn with_capacity(capacity: usize) -> Self {
            Self {
                buf: Vec::with_capacity(capacity),
                offsets: Vec::with_capacity(4),
                bad: false,
            }
        }

        fn write_iface(&mut self, iface: &str) {
            self.write_i32(0x9c | (0x53 << 16));
            self.write_i32(-1);
            self.write_i32(b_pack_chars(b'S', b'Y', b'S', b'T') as i32);
            self.write_str16(iface);
        }

        fn write_i32(&mut self, value: i32) {
            self.buf.extend_from_slice(&value.to_ne_bytes());
        }

        fn write_str16(&mut self, value: &str) {
            let bytes = value.as_bytes();
            if bytes.len() > i32::MAX as usize {
                self.bad = true;
                return;
            }
            self.write_i32(bytes.len() as i32);
            for byte in bytes {
                let ch = *byte as u16;
                self.buf.extend_from_slice(&ch.to_ne_bytes());
            }
            self.buf.extend_from_slice(&0u16.to_ne_bytes());
            self.pad4();
        }

        fn write_fd(&mut self, fd: i32) {
            self.pad4();
            self.offsets.push(self.buf.len() as BinderSize);
            let obj = FlatBinderObject {
                hdr: BinderObjectHeader {
                    type_: BINDER_TYPE_FD,
                },
                flags: 0,
                data: FlatBinderData { handle: fd as u32 },
                cookie: 0,
            };
            push_struct(&mut self.buf, &obj);
        }

        fn pad4(&mut self) {
            while self.buf.len() & 3 != 0 {
                self.buf.push(0);
            }
        }
    }

    struct TransactionReply {
        data: Vec<u8>,
        status: i32,
    }

    type BinderSize = u64;
    type BinderUintPtr = u64;

    #[repr(C)]
    struct BinderWriteRead {
        write_size: BinderSize,
        write_consumed: BinderSize,
        write_buffer: BinderUintPtr,
        read_size: BinderSize,
        read_consumed: BinderSize,
        read_buffer: BinderUintPtr,
    }

    #[repr(C)]
    struct BinderVersion {
        protocol_version: i32,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    union BinderTarget {
        handle: u32,
        ptr: BinderUintPtr,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct BinderDataPtr {
        buffer: BinderUintPtr,
        offsets: BinderUintPtr,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    union BinderData {
        ptr: BinderDataPtr,
        buf: [u8; 8],
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct BinderTransactionData {
        target: BinderTarget,
        cookie: BinderUintPtr,
        code: u32,
        flags: u32,
        sender_pid: i32,
        sender_euid: u32,
        data_size: BinderSize,
        offsets_size: BinderSize,
        data: BinderData,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct BinderObjectHeader {
        type_: u32,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    union FlatBinderData {
        binder: BinderUintPtr,
        handle: u32,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct FlatBinderObject {
        hdr: BinderObjectHeader,
        flags: u32,
        data: FlatBinderData,
        cookie: BinderUintPtr,
    }

    fn push_struct<T>(out: &mut Vec<u8>, value: &T) {
        let bytes =
            unsafe { slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) };
        out.extend_from_slice(bytes);
    }

    fn push_u32(out: &mut Vec<u8>, value: u32) {
        out.extend_from_slice(&value.to_ne_bytes());
    }

    fn push_binder_uintptr(out: &mut Vec<u8>, value: BinderUintPtr) {
        out.extend_from_slice(&value.to_ne_bytes());
    }

    fn read_u32(data: &[u8]) -> u32 {
        let mut raw = [0u8; 4];
        raw.copy_from_slice(&data[..4]);
        u32::from_ne_bytes(raw)
    }

    fn skip_bytes(data: &[u8], count: usize) -> io::Result<&[u8]> {
        if data.len() < count {
            Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "short binder command payload",
            ))
        } else {
            Ok(&data[count..])
        }
    }

    const fn b_pack_chars(c1: u8, c2: u8, c3: u8, c4: u8) -> u32 {
        ((c1 as u32) << 24) | ((c2 as u32) << 16) | ((c3 as u32) << 8) | c4 as u32
    }

    const fn ioc(dir: u64, type_: u64, nr: u64, size: u64) -> u32 {
        ((dir << 30) | (type_ << 8) | nr | (size << 16)) as u32
    }

    const fn ioctl_request(value: u32) -> libc::c_int {
        value as libc::c_int
    }

    const fn io(type_: u8, nr: u8) -> u32 {
        ioc(0, type_ as u64, nr as u64, 0)
    }

    const fn ior<T>(type_: u8, nr: u8) -> u32 {
        ioc(2, type_ as u64, nr as u64, mem::size_of::<T>() as u64)
    }

    const fn iow<T>(type_: u8, nr: u8) -> u32 {
        ioc(1, type_ as u64, nr as u64, mem::size_of::<T>() as u64)
    }

    const fn iow_u32(type_: u8, nr: u8) -> u32 {
        ioc(1, type_ as u64, nr as u64, mem::size_of::<u32>() as u64)
    }

    const fn iowr<T>(type_: u8, nr: u8) -> u32 {
        ioc(3, type_ as u64, nr as u64, mem::size_of::<T>() as u64)
    }

    const B_TYPE_LARGE: u8 = 0x85;
    const BINDER_CURRENT_PROTOCOL_VERSION: i32 = 8;
    const BINDER_TYPE_HANDLE: u32 = b_pack_chars(b's', b'h', b'*', B_TYPE_LARGE);
    const BINDER_TYPE_WEAK_HANDLE: u32 = b_pack_chars(b'w', b'h', b'*', B_TYPE_LARGE);
    const BINDER_TYPE_FD: u32 = b_pack_chars(b'f', b'd', b'*', B_TYPE_LARGE);
    const BINDER_WRITE_READ: libc::c_int = ioctl_request(iowr::<BinderWriteRead>(b'b', 1));
    const BINDER_SET_MAX_THREADS: libc::c_int = ioctl_request(iow::<u32>(b'b', 5));
    const BINDER_VERSION: libc::c_int = ioctl_request(iowr::<BinderVersion>(b'b', 9));
    const BR_ERROR: u32 = ior::<i32>(b'r', 0);
    const BR_REPLY: u32 = ior::<BinderTransactionData>(b'r', 3);
    const BR_DEAD_REPLY: u32 = io(b'r', 5);
    const BR_TRANSACTION_COMPLETE: u32 = io(b'r', 6);
    const BR_INCREFS: u32 = ior::<[BinderUintPtr; 2]>(b'r', 7);
    const BR_ACQUIRE: u32 = ior::<[BinderUintPtr; 2]>(b'r', 8);
    const BR_RELEASE: u32 = ior::<[BinderUintPtr; 2]>(b'r', 9);
    const BR_DECREFS: u32 = ior::<[BinderUintPtr; 2]>(b'r', 10);
    const BR_NOOP: u32 = io(b'r', 12);
    const BR_SPAWN_LOOPER: u32 = io(b'r', 13);
    const BR_DEAD_BINDER: u32 = ior::<BinderUintPtr>(b'r', 15);
    const BR_CLEAR_DEATH_NOTIFICATION_DONE: u32 = ior::<BinderUintPtr>(b'r', 16);
    const BR_FAILED_REPLY: u32 = io(b'r', 17);
    const BR_FROZEN_REPLY: u32 = io(b'r', 18);
    const BR_FROZEN_BINDER: u32 = ior::<(BinderUintPtr, u32, u32)>(b'r', 21);
    const BC_TRANSACTION: u32 = iow::<BinderTransactionData>(b'c', 0);
    const BC_FREE_BUFFER: u32 = iow::<BinderUintPtr>(b'c', 3);
    const BC_ACQUIRE: u32 = iow_u32(b'c', 5);
    const TF_STATUS_CODE: u32 = 0x08;
    const SF_DUMP_CODE: u32 = b_pack_chars(b'_', b'D', b'M', b'P');
    const SVC_CHECK_CODE: u32 = 2;
