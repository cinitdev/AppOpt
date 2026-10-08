use super::*;
// 为长期运行的工作线程提供轻量的命令文件 inotify 监听。
//
// 此实现有意独立于 RuntimeFileMonitor：命令线程只需要唤醒信号，
// 唤醒后始终重新读取文件，并以原子方式认领。
// 不直接依据事件判断实际状态；仍保留有界超时，
// 以便在事件队列溢出或文件系统不支持监听时恢复。
pub(super) struct CommandFileMonitor {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub(super) fd: i32,
    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub(super) names: BTreeSet<Vec<u8>>,
}

impl CommandFileMonitor {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub(super) fn new(paths: &[&str]) -> io::Result<Self> {
        let first = paths
            .first()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "缺少监听文件"))?;
        let parent = Path::new(first)
            .parent()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "监听文件缺少父目录"))?;
        let mut names = BTreeSet::new();
        for path in paths {
            let path = Path::new(path);
            if path.parent() != Some(parent) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "命令文件必须位于同一目录",
                ));
            }
            let name = path
                .file_name()
                .and_then(|value| value.to_str())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "命令文件名无效"))?;
            names.insert(name.as_bytes().to_vec());
        }

        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let parent = CString::new(parent.as_os_str().as_encoded_bytes()).map_err(|_| {
            unsafe {
                libc::close(fd);
            }
            io::Error::new(io::ErrorKind::InvalidInput, "监听目录包含空字节")
        })?;
        let mask = libc::IN_CLOSE_WRITE
            | libc::IN_CREATE
            | libc::IN_MOVED_TO
            | libc::IN_MOVED_FROM
            | libc::IN_DELETE
            | libc::IN_ATTRIB
            | libc::IN_DELETE_SELF
            | libc::IN_MOVE_SELF;
        let watch = unsafe { libc::inotify_add_watch(fd, parent.as_ptr(), mask) };
        if watch < 0 {
            let error = io::Error::last_os_error();
            unsafe {
                libc::close(fd);
            }
            return Err(error);
        }
        Ok(Self { fd, names })
    }

    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    pub(super) fn new(_paths: &[&str]) -> io::Result<Self> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "inotify 仅支持 Android/Linux",
        ))
    }

    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub(super) fn wait(&mut self, timeout: Duration) -> io::Result<()> {
        self.wait_with_signal(timeout, -1)
    }

    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub(super) fn wait_with_signal(&mut self, timeout: Duration, signal_fd: i32) -> io::Result<()> {
        let started = Instant::now();
        loop {
            let remaining = timeout.saturating_sub(started.elapsed());
            if remaining.is_zero() {
                return Ok(());
            }
            let shutdown_fd = shutdown_event_fd().unwrap_or(-1);
            let mut poll_fds = [
                libc::pollfd {
                    fd: self.fd,
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: shutdown_fd,
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd { fd: signal_fd, events: libc::POLLIN, revents: 0 },
            ];
            let timeout_ms = remaining.as_millis().min(i32::MAX as u128) as i32;
            let result = unsafe {
                libc::poll(
                    poll_fds.as_mut_ptr(),
                    poll_fds.len() as _,
                    timeout_ms.max(1),
                )
            };
            if result == 0 {
                return Ok(());
            }
            if result < 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(error);
            }
            if shutdown_fd >= 0 && poll_fds[1].revents & libc::POLLIN != 0 {
                return Ok(());
            }
            if signal_fd >= 0 && poll_fds[2].revents & libc::POLLIN != 0 { return Ok(()); }
            if poll_fds[0].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                return Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "命令文件 inotify 监听失效",
                ));
            }
            if self.drain()? {
                return Ok(());
            }
        }
    }

    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    pub(super) fn wait(&mut self, timeout: Duration) -> io::Result<()> {
        thread::sleep(timeout);
        Ok(())
    }

    #[cfg(any(target_os = "android", target_os = "linux"))]
    pub(super) fn drain(&mut self) -> io::Result<bool> {
        let mut matched = false;
        let mut buffer = [0u8; 4096];
        loop {
            let read = unsafe {
                libc::read(
                    self.fd,
                    buffer.as_mut_ptr().cast(),
                    buffer.len(),
                )
            };
            if read < 0 {
                let error = io::Error::last_os_error();
                if error.kind() == io::ErrorKind::WouldBlock {
                    return Ok(matched);
                }
                return Err(error);
            }
            if read == 0 {
                return Ok(matched);
            }
            let mut offset = 0usize;
            while offset + mem::size_of::<libc::inotify_event>() <= read as usize {
                let event = unsafe {
                    std::ptr::read_unaligned(
                        buffer.as_ptr().add(offset).cast::<libc::inotify_event>(),
                    )
                };
                if event.mask & libc::IN_Q_OVERFLOW != 0 {
                    matched = true;
                }
                if event.mask & (libc::IN_IGNORED | libc::IN_DELETE_SELF | libc::IN_MOVE_SELF) != 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::BrokenPipe,
                        "命令文件监听目录已被替换",
                    ));
                }
                let header = mem::size_of::<libc::inotify_event>();
                let end = offset
                    .saturating_add(header)
                    .saturating_add(event.len as usize)
                    .min(read as usize);
                let name = buffer[offset + header..end]
                    .split(|byte| *byte == 0)
                    .next()
                    .unwrap_or_default();
                matched |= self.names.contains(name);
                offset = offset.saturating_add(header + event.len as usize);
            }
        }
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
impl Drop for CommandFileMonitor {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.fd);
        }
    }
}

pub(super) fn wait_for_command_files(
    monitor: &mut Option<CommandFileMonitor>,
    event_timeout: Duration,
    fallback_timeout: Duration,
) {
    let result = match monitor.as_mut() {
        Some(monitor) => monitor.wait(event_timeout),
        None => {
            thread::sleep(fallback_timeout);
            return;
        }
    };
    if let Err(error) = result {
        log_warn!("[RS] 命令文件事件监听失效，改用低频轮询: {error}");
        *monitor = None;
        thread::sleep(fallback_timeout);
    }
}
