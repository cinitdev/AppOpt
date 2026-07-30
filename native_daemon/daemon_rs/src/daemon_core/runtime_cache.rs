// 配置输入缓存与 inotify 文件变化通知。
//
// daemon 的 2 秒轮次不再重复读取、解析配置并重建 owner 索引。inotify 仅作为及时
// 唤醒信号，内容指纹仍是最终变化依据；监听不可用或事件丢失时保留周期校验。

const RUNTIME_INPUT_VERIFY_MS: u64 = 60_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FileStamp {
    Missing,
    Present {
        len: u64,
        modified_ns: u128,
        inode: u64,
    },
}

#[derive(Debug, Default, Clone, Copy)]
struct RuntimeFileChanges {
    config: bool,
    uid_map: bool,
    overflowed: bool,
    monitor_invalidated: bool,
}

impl RuntimeFileChanges {
    fn all() -> Self {
        Self {
            config: true,
            uid_map: true,
            overflowed: false,
            monitor_invalidated: false,
        }
    }
}

#[derive(Debug, Default)]
struct RuntimeRefresh {
    config_changed: bool,
    uid_map_changed: bool,
    index_rebuilt: bool,
}

#[derive(Debug, Default)]
struct RuntimeInputsCache {
    rules: Vec<Rule>,
    uid_map: HashMap<String, u32>,
    config_key: Option<FileKey>,
    uid_map_key: Option<FileKey>,
    config_stamp: Option<FileStamp>,
    uid_map_stamp: Option<FileStamp>,
    config_retry: bool,
    uid_map_retry: bool,
    initialized: bool,
    last_verify_elapsed_ms: Option<u64>,
    index: RuntimeRuleIndex,
}

impl RuntimeInputsCache {
    fn refresh(
        &mut self,
        args: &Args,
        state: &mut DaemonState,
        changes: RuntimeFileChanges,
        monitor_active: bool,
        now_elapsed: u64,
    ) -> io::Result<RuntimeRefresh> {
        let verify_due = !self.initialized
            || self.last_verify_elapsed_ms.is_none_or(|last| {
                now_elapsed < last
                    || now_elapsed.saturating_sub(last) >= RUNTIME_INPUT_VERIFY_MS
            });
        let probe_metadata = !monitor_active || verify_due || changes.overflowed;
        if probe_metadata {
            self.last_verify_elapsed_ms = Some(now_elapsed);
        }

        let current_config_stamp = probe_metadata
            .then(|| file_stamp(&args.config))
            .transpose()?;
        let current_uid_stamp = probe_metadata
            .then(|| file_stamp(&args.uid_map))
            .transpose()?;
        let config_dirty = !self.initialized
            || self.config_retry
            || changes.config
            || changes.overflowed
            || current_config_stamp.is_some_and(|stamp| Some(stamp) != self.config_stamp);
        let uid_dirty = !self.initialized
            || self.uid_map_retry
            || changes.uid_map
            || changes.overflowed
            || current_uid_stamp.is_some_and(|stamp| Some(stamp) != self.uid_map_stamp);

        let mut refresh = RuntimeRefresh::default();
        if config_dirty {
            match parse_config_with_key(&args.config) {
                Ok((rules, key)) => {
                    refresh.config_changed = self.config_key != Some(key);
                    if refresh.config_changed || !self.initialized {
                        self.rules = rules;
                        self.config_key = Some(key);
                    }
                    self.config_stamp = Some(
                        current_config_stamp
                            .unwrap_or(file_stamp(&args.config)?),
                    );
                    self.config_retry = false;
                }
                Err(err) if self.initialized => {
                    if !self.config_retry {
                        eprintln!(
                            "[RS] 配置文件刷新失败，继续使用上一份有效规则并等待重试: {err}"
                        );
                    }
                    self.config_retry = true;
                }
                Err(err) => return Err(err),
            }
        }

        if uid_dirty {
            match parse_uid_map_with_key(&args.uid_map) {
                Ok((uid_map, key)) => {
                    refresh.uid_map_changed = self.uid_map_key != key;
                    if refresh.uid_map_changed || !self.initialized {
                        self.uid_map = uid_map;
                        self.uid_map_key = key;
                    }
                    self.uid_map_stamp = Some(
                        current_uid_stamp
                            .unwrap_or(file_stamp(&args.uid_map)?),
                    );
                    self.uid_map_retry = false;
                }
                Err(err) if self.initialized => {
                    if !self.uid_map_retry {
                        eprintln!(
                            "[RS] UID 映射刷新失败，继续使用上一份有效映射并等待重试: {err}"
                        );
                    }
                    self.uid_map_retry = true;
                }
                Err(err) => return Err(err),
            }
        }

        if !self.initialized
            || refresh.config_changed
            || refresh.uid_map_changed
            || state.runtime_rule_index_dirty
        {
            self.index = build_runtime_rule_index(
                &self.rules,
                &self.uid_map,
                args.target_pkg.as_deref(),
                state,
            );
            state.runtime_rule_index_dirty = false;
            refresh.index_rebuilt = true;
        }
        self.initialized = true;
        Ok(refresh)
    }
}

fn file_stamp(path: &Path) -> io::Result<FileStamp> {
    let metadata = match fs::metadata(path) {
        Ok(metadata) => metadata,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(FileStamp::Missing),
        Err(err) => return Err(err),
    };
    let modified_ns = metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    #[cfg(unix)]
    let inode = metadata.ino();
    #[cfg(not(unix))]
    let inode = 0;
    Ok(FileStamp::Present {
        len: metadata.len(),
        modified_ns,
        inode,
    })
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[derive(Debug)]
struct RuntimeWatch {
    wd: i32,
    config_name: Option<Vec<u8>>,
    uid_map_name: Option<Vec<u8>>,
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[derive(Debug)]
struct RuntimeFileMonitor {
    fd: i32,
    watches: Vec<RuntimeWatch>,
}

#[cfg(any(target_os = "android", target_os = "linux"))]
impl RuntimeFileMonitor {
    fn new(config: &Path, uid_map: &Path) -> io::Result<Self> {
        use std::os::unix::ffi::OsStrExt;

        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let mut monitor = Self {
            fd,
            watches: Vec::new(),
        };
        let add_result = (|| {
            for (path, is_config) in [(config, true), (uid_map, false)] {
                let parent = path.parent().unwrap_or_else(|| Path::new("."));
                let name = path
                    .file_name()
                    .map(|name| name.as_bytes().to_vec())
                    .unwrap_or_default();
                let parent_c = CString::new(parent.as_os_str().as_bytes())
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "监听路径含 NUL"))?;
                let mask = libc::IN_CLOSE_WRITE
                    | libc::IN_MOVED_TO
                    | libc::IN_DELETE
                    | libc::IN_ATTRIB
                    | libc::IN_MOVE_SELF
                    | libc::IN_DELETE_SELF;
                let wd = unsafe { libc::inotify_add_watch(fd, parent_c.as_ptr(), mask) };
                if wd < 0 {
                    return Err(io::Error::last_os_error());
                }
                if let Some(watch) = monitor.watches.iter_mut().find(|watch| watch.wd == wd) {
                    if is_config {
                        watch.config_name = Some(name);
                    } else {
                        watch.uid_map_name = Some(name);
                    }
                } else {
                    monitor.watches.push(RuntimeWatch {
                        wd,
                        config_name: is_config.then_some(name.clone()),
                        uid_map_name: (!is_config).then_some(name),
                    });
                }
            }
            Ok(())
        })();
        if let Err(err) = add_result {
            unsafe { libc::close(fd) };
            return Err(err);
        }
        Ok(monitor)
    }

    fn as_raw_fd(&self) -> i32 {
        self.fd
    }

    fn drain(&mut self) -> io::Result<RuntimeFileChanges> {
        let mut changes = RuntimeFileChanges::default();
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
                let err = io::Error::last_os_error();
                if err.kind() == io::ErrorKind::WouldBlock {
                    break;
                }
                return Err(err);
            }
            if read == 0 {
                break;
            }
            let mut offset = 0usize;
            while offset + mem::size_of::<libc::inotify_event>() <= read as usize {
                let event = unsafe {
                    std::ptr::read_unaligned(
                        buffer.as_ptr().add(offset).cast::<libc::inotify_event>(),
                    )
                };
                let header = mem::size_of::<libc::inotify_event>();
                let end = offset
                    .saturating_add(header)
                    .saturating_add(event.len as usize)
                    .min(read as usize);
                let name = &buffer[offset + header..end];
                let name = name
                    .split(|byte| *byte == 0)
                    .next()
                    .unwrap_or_default();
                if event.mask & libc::IN_Q_OVERFLOW != 0 {
                    changes.overflowed = true;
                }
                if let Some(watch) = self.watches.iter().find(|watch| watch.wd == event.wd) {
                    let watch_invalidated = event.mask & (libc::IN_IGNORED | libc::IN_MOVE_SELF | libc::IN_DELETE_SELF) != 0;
                    changes.monitor_invalidated |= watch_invalidated;
                    changes.config |= watch.config_name.as_deref() == Some(name) || (watch_invalidated && watch.config_name.is_some());
                    changes.uid_map |= watch.uid_map_name.as_deref() == Some(name) || (watch_invalidated && watch.uid_map_name.is_some());
                }
                offset = offset.saturating_add(header + event.len as usize);
            }
        }
        Ok(changes)
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
impl Drop for RuntimeFileMonitor {
    fn drop(&mut self) {
        unsafe { libc::close(self.fd) };
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
#[derive(Debug)]
struct RuntimeFileMonitor;

#[cfg(not(any(target_os = "android", target_os = "linux")))]
impl RuntimeFileMonitor {
    fn new(_config: &Path, _uid_map: &Path) -> io::Result<Self> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "当前平台不支持 inotify",
        ))
    }

    fn drain(&mut self) -> io::Result<RuntimeFileChanges> {
        Ok(RuntimeFileChanges::default())
    }
}
