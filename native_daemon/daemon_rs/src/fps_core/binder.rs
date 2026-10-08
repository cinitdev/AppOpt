use super::*;
// 轻量 Binder 客户端，只服务于 SurfaceFlinger dump。
//
// 这里没有使用 dumpsys 命令，也没有依赖 Android Java API：
// - 打开 /dev/binder
// - 向 servicemanager 查询 SurfaceFlinger handle
// - 对 SurfaceFlinger 发 dump transaction，并通过 pipe 取回文本
//
// 这段代码和 Android binder ABI 强相关，改动时要同时在 Android 12-16 上验证。
pub(super) struct BinderSf {
    pub(super) fd: i32,
    pub(super) map: *mut libc::c_void,
    pub(super) map_size: usize,
    pub(super) sf_handle: u32,
    pub(super) have_sf: bool,
}

impl BinderSf {
    pub(super) fn new() -> io::Result<Self> {
        let path = CString::new("/dev/binder").map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, "invalid binder device path")
        })?;
        let fd = unsafe {
            libc::open(
                path.as_ptr(),
                libc::O_RDWR | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
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

    pub(super) fn dump(&mut self, args: &[&str]) -> io::Result<String> {
        if !self.have_sf {
            self.resolve_surfaceflinger()?;
        }

        let mut pfd = [0i32; 2];
        if unsafe { libc::pipe(pfd.as_mut_ptr()) } != 0 {
            return Err(io::Error::last_os_error());
        }
        let read_fd = pfd[0];
        let write_fd = pfd[1];
        if let Err(err) = set_fd_nonblocking(read_fd) {
            close_fd(read_fd);
            close_fd(write_fd);
            return Err(err);
        }

        let reader = match thread::Builder::new()
            .name("QixiaThreadsSfBinder".to_string())
            .spawn(move || read_binder_dump_pipe(read_fd))
        {
            Ok(reader) => reader,
            Err(err) => {
                close_fd(read_fd);
                close_fd(write_fd);
                return Err(err);
            }
        };

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
        let output = reader.join().map_err(|_| {
            io::Error::new(io::ErrorKind::Other, "SurfaceFlinger dump reader panicked")
        })?;
        if let Err(err) = result {
            self.have_sf = false;
            return Err(err);
        }
        let output = output?;
        Ok(String::from_utf8_lossy(&output).into_owned())
    }

    pub(super) fn resolve_surfaceflinger(&mut self) -> io::Result<()> {
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
                log_info!("[FPS][binder] SurfaceFlinger handle={}", self.sf_handle);
                return Ok(());
            }
        }
        Err(io::Error::new(
            io::ErrorKind::NotFound,
            "SurfaceFlinger binder handle not found",
        ))
    }

    pub(super) fn transact(
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

        let deadline = Instant::now() + BINDER_TRANSACTION_TIMEOUT;
        let mut write_offset = 0usize;
        let mut read_buf = [0u8; 4096];
        loop {
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "binder transaction timed out",
                ));
            }
            let write_remaining = write_buf.len().saturating_sub(write_offset);
            let mut bwr = BinderWriteRead {
                write_size: write_remaining as BinderSize,
                write_consumed: 0,
                write_buffer: if write_remaining == 0 {
                    0
                } else {
                    (unsafe { write_buf.as_ptr().add(write_offset) }) as BinderUintPtr
                },
                read_size: read_buf.len() as BinderSize,
                read_consumed: 0,
                read_buffer: read_buf.as_mut_ptr() as BinderUintPtr,
            };
            let rc = unsafe {
                libc::ioctl(self.fd, BINDER_WRITE_READ, &mut bwr as *mut BinderWriteRead)
            };
            let wrote = bwr.write_consumed as usize;
            if wrote > write_remaining {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "binder reported invalid write_consumed",
                ));
            }
            write_offset = write_offset.saturating_add(wrote);
            let read_consumed = bwr.read_consumed as usize;
            if read_consumed > read_buf.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "binder reported invalid read_consumed",
                ));
            }
            if rc < 0 {
                let err = io::Error::last_os_error();
                if err.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                if err.kind() == io::ErrorKind::WouldBlock {
                    let events = if write_offset >= write_buf.len() {
                        libc::POLLIN
                    } else {
                        libc::POLLIN | libc::POLLOUT
                    };
                    let _ = poll_fd_until(self.fd, events, deadline, false)?;
                    continue;
                }
                return Err(err);
            }
            if let Some(reply) =
                self.parse_binder_read(&read_buf[..read_consumed], reply_cap)?
            {
                if reply.status != 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::Other,
                        format!("binder transaction status {}", reply.status),
                    ));
                }
                return Ok(reply);
            }
            if wrote == 0 && read_consumed == 0 {
                let events = if write_offset >= write_buf.len() {
                    libc::POLLIN
                } else {
                    libc::POLLIN | libc::POLLOUT
                };
                let _ = poll_fd_until(self.fd, events, deadline, false)?;
            }
        }
    }

    pub(super) fn parse_binder_read(
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

    pub(super) fn copy_reply(&self, rt: &BinderTransactionData, reply_cap: usize) -> TransactionReply {
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

    pub(super) fn retain_reply_handles(&mut self, rt: &BinderTransactionData) {
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

    pub(super) fn acquire_handle(&mut self, handle: u32) -> io::Result<()> {
        let mut cmd = Vec::with_capacity(8);
        push_u32(&mut cmd, BC_ACQUIRE);
        push_u32(&mut cmd, handle);
        self.write_command(&cmd)
    }

    pub(super) fn free_buffer(&mut self, buffer: BinderUintPtr) {
        if buffer == 0 {
            return;
        }
        let mut cmd = Vec::with_capacity(4 + mem::size_of::<BinderUintPtr>());
        push_u32(&mut cmd, BC_FREE_BUFFER);
        push_binder_uintptr(&mut cmd, buffer);
        let _ = self.write_command(&cmd);
    }

    pub(super) fn write_command(&mut self, cmd: &[u8]) -> io::Result<()> {
        let deadline = Instant::now() + BINDER_TRANSACTION_TIMEOUT;
        let mut offset = 0usize;
        while offset < cmd.len() {
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "binder command write timed out",
                ));
            }
            let remaining = cmd.len() - offset;
            let mut bwr = BinderWriteRead {
                write_size: remaining as BinderSize,
                write_consumed: 0,
                write_buffer: (unsafe { cmd.as_ptr().add(offset) }) as BinderUintPtr,
                read_size: 0,
                read_consumed: 0,
                read_buffer: 0,
            };
            let rc = unsafe { libc::ioctl(self.fd, BINDER_WRITE_READ, &mut bwr) };
            let wrote = bwr.write_consumed as usize;
            if wrote > remaining {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "binder reported invalid command write_consumed",
                ));
            }
            if wrote > 0 {
                offset += wrote;
            }
            if rc < 0 {
                let err = io::Error::last_os_error();
                if err.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                if err.kind() == io::ErrorKind::WouldBlock {
                    let _ = poll_fd_until(self.fd, libc::POLLOUT, deadline, false)?;
                    continue;
                }
                return Err(err);
            }
            if wrote == 0 {
                let _ = poll_fd_until(self.fd, libc::POLLOUT, deadline, false)?;
            }
        }
        Ok(())
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

pub(super) fn set_fd_nonblocking(fd: i32) -> io::Result<()> {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

pub(super) fn read_binder_dump_pipe(fd: i32) -> io::Result<Vec<u8>> {
    let result = (|| {
        let deadline = Instant::now() + BINDER_DUMP_READER_TIMEOUT;
        let mut out = Vec::with_capacity(BINDER_DUMP_MAX_OUTPUT.min(64 * 1024));
        let mut buf = [0u8; 8192];
        let mut truncated = false;
        let mut peer_closed = false;
        loop {
            let rc = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
            if rc > 0 {
                let read = rc as usize;
                let remaining = BINDER_DUMP_MAX_OUTPUT.saturating_sub(out.len());
                out.extend_from_slice(&buf[..read.min(remaining)]);
                truncated |= read > remaining;
                continue;
            }
            if rc == 0 {
                break;
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if err.kind() != io::ErrorKind::WouldBlock {
                return Err(err);
            }
            if peer_closed {
                break;
            }
            let revents = poll_fd_until(fd, libc::POLLIN, deadline, true)?;
            peer_closed |= revents & libc::POLLHUP != 0;
        }
        if truncated {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "SurfaceFlinger dump exceeded 1 MiB",
            ));
        }
        Ok(out)
    })();
    close_fd(fd);
    result
}

pub(super) fn poll_fd_until(
    fd: i32,
    events: i16,
    deadline: Instant,
    allow_hup: bool,
) -> io::Result<i16> {
    loop {
        let now = Instant::now();
        if now >= deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "fd poll timed out"));
        }
        let timeout_ms = deadline
            .saturating_duration_since(now)
            .as_millis()
            .saturating_add(1)
            .min(i32::MAX as u128) as i32;
        let mut poll_fd = libc::pollfd {
            fd,
            events,
            revents: 0,
        };
        let rc = unsafe { libc::poll(&mut poll_fd, 1, timeout_ms) };
        if rc < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(err);
        }
        if rc == 0 {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "fd poll timed out"));
        }
        if poll_fd.revents & (libc::POLLERR | libc::POLLNVAL) != 0
            || (!allow_hup && poll_fd.revents & libc::POLLHUP != 0)
        {
            return Err(io::Error::new(
                io::ErrorKind::BrokenPipe,
                format!("fd poll failed: revents=0x{:x}", poll_fd.revents),
            ));
        }
        return Ok(poll_fd.revents);
    }
}

pub(super) struct Parcel {
    pub(super) buf: Vec<u8>,
    pub(super) offsets: Vec<BinderSize>,
    pub(super) bad: bool,
}

impl Parcel {
    pub(super) fn with_capacity(capacity: usize) -> Self {
        Self {
            buf: Vec::with_capacity(capacity),
            offsets: Vec::with_capacity(4),
            bad: false,
        }
    }

    pub(super) fn write_iface(&mut self, iface: &str) {
        self.write_i32(0x9c | (0x53 << 16));
        self.write_i32(-1);
        self.write_i32(b_pack_chars(b'S', b'Y', b'S', b'T') as i32);
        self.write_str16(iface);
    }

    pub(super) fn write_i32(&mut self, value: i32) {
        self.buf.extend_from_slice(&value.to_ne_bytes());
    }

    pub(super) fn write_str16(&mut self, value: &str) {
        let units = value.encode_utf16().collect::<Vec<_>>();
        if units.len() > i32::MAX as usize {
            self.bad = true;
            return;
        }
        self.write_i32(units.len() as i32);
        for unit in units {
            self.buf.extend_from_slice(&unit.to_ne_bytes());
        }
        self.buf.extend_from_slice(&0u16.to_ne_bytes());
        self.pad4();
    }

    pub(super) fn write_fd(&mut self, fd: i32) {
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

    pub(super) fn pad4(&mut self) {
        while self.buf.len() & 3 != 0 {
            self.buf.push(0);
        }
    }
}

pub(super) struct TransactionReply {
    pub(super) data: Vec<u8>,
    pub(super) status: i32,
}

#[cfg(target_pointer_width = "32")]
pub(super) type BinderSize = u32;
#[cfg(target_pointer_width = "64")]
pub(super) type BinderSize = u64;
#[cfg(target_pointer_width = "32")]
pub(super) type BinderUintPtr = u32;
#[cfg(target_pointer_width = "64")]
pub(super) type BinderUintPtr = u64;

#[repr(C)]
pub(super) struct BinderWriteRead {
    pub(super) write_size: BinderSize,
    pub(super) write_consumed: BinderSize,
    pub(super) write_buffer: BinderUintPtr,
    pub(super) read_size: BinderSize,
    pub(super) read_consumed: BinderSize,
    pub(super) read_buffer: BinderUintPtr,
}

#[repr(C)]
pub(super) struct BinderVersion {
    pub(super) protocol_version: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) union BinderTarget {
    handle: u32,
    ptr: BinderUintPtr,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) struct BinderDataPtr {
    pub(super) buffer: BinderUintPtr,
    pub(super) offsets: BinderUintPtr,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) union BinderData {
    ptr: BinderDataPtr,
    buf: [u8; 8],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) struct BinderTransactionData {
    pub(super) target: BinderTarget,
    pub(super) cookie: BinderUintPtr,
    pub(super) code: u32,
    pub(super) flags: u32,
    pub(super) sender_pid: i32,
    pub(super) sender_euid: u32,
    pub(super) data_size: BinderSize,
    pub(super) offsets_size: BinderSize,
    pub(super) data: BinderData,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) struct BinderObjectHeader {
    pub(super) type_: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) union FlatBinderData {
    binder: BinderUintPtr,
    handle: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub(super) struct FlatBinderObject {
    pub(super) hdr: BinderObjectHeader,
    pub(super) flags: u32,
    pub(super) data: FlatBinderData,
    pub(super) cookie: BinderUintPtr,
}

#[cfg(target_pointer_width = "32")]
const _: [(); 24] = [(); mem::size_of::<BinderWriteRead>()];
#[cfg(target_pointer_width = "64")]
const _: [(); 48] = [(); mem::size_of::<BinderWriteRead>()];
#[cfg(target_pointer_width = "32")]
const _: [(); 40] = [(); mem::size_of::<BinderTransactionData>()];
#[cfg(target_pointer_width = "64")]
const _: [(); 64] = [(); mem::size_of::<BinderTransactionData>()];
#[cfg(target_pointer_width = "32")]
const _: [(); 16] = [(); mem::size_of::<FlatBinderObject>()];
#[cfg(target_pointer_width = "64")]
const _: [(); 24] = [(); mem::size_of::<FlatBinderObject>()];

pub(super) fn push_struct<T>(out: &mut Vec<u8>, value: &T) {
    let bytes =
        unsafe { slice::from_raw_parts((value as *const T).cast::<u8>(), mem::size_of::<T>()) };
    out.extend_from_slice(bytes);
}

pub(super) fn push_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_ne_bytes());
}

pub(super) fn push_binder_uintptr(out: &mut Vec<u8>, value: BinderUintPtr) {
    out.extend_from_slice(&value.to_ne_bytes());
}

pub(super) fn read_u32(data: &[u8]) -> u32 {
    let mut raw = [0u8; 4];
    raw.copy_from_slice(&data[..4]);
    u32::from_ne_bytes(raw)
}

pub(super) fn skip_bytes(data: &[u8], count: usize) -> io::Result<&[u8]> {
    if data.len() < count {
        Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "short binder command payload",
        ))
    } else {
        Ok(&data[count..])
    }
}

pub(super) const fn b_pack_chars(c1: u8, c2: u8, c3: u8, c4: u8) -> u32 {
    ((c1 as u32) << 24) | ((c2 as u32) << 16) | ((c3 as u32) << 8) | c4 as u32
}

pub(super) const fn ioc(dir: u64, type_: u64, nr: u64, size: u64) -> u32 {
    ((dir << 30) | (type_ << 8) | nr | (size << 16)) as u32
}

pub(super) const fn ioctl_request(value: u32) -> libc::c_int {
    value as libc::c_int
}

pub(super) const fn io(type_: u8, nr: u8) -> u32 {
    ioc(0, type_ as u64, nr as u64, 0)
}

pub(super) const fn ior<T>(type_: u8, nr: u8) -> u32 {
    ioc(2, type_ as u64, nr as u64, mem::size_of::<T>() as u64)
}

pub(super) const fn iow<T>(type_: u8, nr: u8) -> u32 {
    ioc(1, type_ as u64, nr as u64, mem::size_of::<T>() as u64)
}

pub(super) const fn iow_u32(type_: u8, nr: u8) -> u32 {
    ioc(1, type_ as u64, nr as u64, mem::size_of::<u32>() as u64)
}

pub(super) const fn iowr<T>(type_: u8, nr: u8) -> u32 {
    ioc(3, type_ as u64, nr as u64, mem::size_of::<T>() as u64)
}

pub(super) const B_TYPE_LARGE: u8 = 0x85;
#[cfg(target_pointer_width = "32")]
pub(super) const BINDER_CURRENT_PROTOCOL_VERSION: i32 = 7;
#[cfg(target_pointer_width = "64")]
pub(super) const BINDER_CURRENT_PROTOCOL_VERSION: i32 = 8;
pub(super) const BINDER_TRANSACTION_TIMEOUT: Duration = Duration::from_millis(1500);
pub(super) const BINDER_DUMP_READER_TIMEOUT: Duration = Duration::from_millis(2000);
pub(super) const BINDER_DUMP_MAX_OUTPUT: usize = 1 << 20;
pub(super) const BINDER_TYPE_HANDLE: u32 = b_pack_chars(b's', b'h', b'*', B_TYPE_LARGE);
pub(super) const BINDER_TYPE_WEAK_HANDLE: u32 = b_pack_chars(b'w', b'h', b'*', B_TYPE_LARGE);
pub(super) const BINDER_TYPE_FD: u32 = b_pack_chars(b'f', b'd', b'*', B_TYPE_LARGE);
pub(super) const BINDER_WRITE_READ: libc::c_int = ioctl_request(iowr::<BinderWriteRead>(b'b', 1));
pub(super) const BINDER_SET_MAX_THREADS: libc::c_int = ioctl_request(iow::<u32>(b'b', 5));
pub(super) const BINDER_VERSION: libc::c_int = ioctl_request(iowr::<BinderVersion>(b'b', 9));
pub(super) const BR_ERROR: u32 = ior::<i32>(b'r', 0);
pub(super) const BR_REPLY: u32 = ior::<BinderTransactionData>(b'r', 3);
pub(super) const BR_DEAD_REPLY: u32 = io(b'r', 5);
pub(super) const BR_TRANSACTION_COMPLETE: u32 = io(b'r', 6);
pub(super) const BR_INCREFS: u32 = ior::<[BinderUintPtr; 2]>(b'r', 7);
pub(super) const BR_ACQUIRE: u32 = ior::<[BinderUintPtr; 2]>(b'r', 8);
pub(super) const BR_RELEASE: u32 = ior::<[BinderUintPtr; 2]>(b'r', 9);
pub(super) const BR_DECREFS: u32 = ior::<[BinderUintPtr; 2]>(b'r', 10);
pub(super) const BR_NOOP: u32 = io(b'r', 12);
pub(super) const BR_SPAWN_LOOPER: u32 = io(b'r', 13);
pub(super) const BR_DEAD_BINDER: u32 = ior::<BinderUintPtr>(b'r', 15);
pub(super) const BR_CLEAR_DEATH_NOTIFICATION_DONE: u32 = ior::<BinderUintPtr>(b'r', 16);
pub(super) const BR_FAILED_REPLY: u32 = io(b'r', 17);
pub(super) const BR_FROZEN_REPLY: u32 = io(b'r', 18);
pub(super) const BR_FROZEN_BINDER: u32 = ior::<(BinderUintPtr, u32, u32)>(b'r', 21);
pub(super) const BC_TRANSACTION: u32 = iow::<BinderTransactionData>(b'c', 0);
pub(super) const BC_FREE_BUFFER: u32 = iow::<BinderUintPtr>(b'c', 3);
pub(super) const BC_ACQUIRE: u32 = iow_u32(b'c', 5);
pub(super) const TF_STATUS_CODE: u32 = 0x08;
pub(super) const SF_DUMP_CODE: u32 = b_pack_chars(b'_', b'D', b'M', b'P');
pub(super) const SVC_CHECK_CODE: u32 = 2;
