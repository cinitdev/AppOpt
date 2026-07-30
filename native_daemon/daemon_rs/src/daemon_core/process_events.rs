// eBPF 进程事件只用于缩短发现延迟，任何事件都必须重新经过现有 /proc 校验。

#[cfg(any(target_os = "android", target_os = "linux"))]
use appopt_ebpf_bridge::{ProcessEventBatch, ProcessEventMonitor};

#[cfg(any(target_os = "android", target_os = "linux"))]
#[derive(Default)]
struct ProcessDiscovery {
    monitor: Option<ProcessEventMonitor>,
    next_retry_elapsed_ms: u64,
    last_error_log_elapsed_ms: Option<u64>,
    discovery_sources: BTreeSet<i32>,
    last_source_refresh_elapsed_ms: Option<u64>,
}

#[cfg(any(target_os = "android", target_os = "linux"))]
impl ProcessDiscovery {
    fn ensure_started_and_sync(
        &mut self,
        package_count: usize,
        known_pids: &BTreeSet<i32>,
        managed_tids: &HashMap<i32, ManagedTidEntry>,
        now_elapsed: u64,
    ) {
        let source_refresh_due = self.last_source_refresh_elapsed_ms.is_none_or(|last| {
            now_elapsed < last || now_elapsed.saturating_sub(last) >= FULL_RESCAN_MAX_MS
        });
        if source_refresh_due {
            let mut sources = BTreeSet::new();
            for name in ["zygote", "zygote64", "usap32", "usap64"] {
                if let Ok(pids) = process_index_find_pids(name) {
                    sources.extend(pids);
                }
            }
            self.discovery_sources = sources;
            self.last_source_refresh_elapsed_ms = Some(now_elapsed);
        }
        let mut monitored_tgids = known_pids.clone();
        monitored_tgids.extend(self.discovery_sources.iter().copied());
        let requested_targets = package_count
            .saturating_mul(4)
            .saturating_add(monitored_tgids.len());
        let requested_tracked_tids = managed_tids.len();
        let needs_resize = self.monitor.as_ref().is_some_and(|monitor| {
            monitored_tgids.len() > monitor.target_capacity()
                || requested_tracked_tids > monitor.tracked_capacity()
        });
        if needs_resize {
            self.monitor = None;
        }
        if self.monitor.is_none() && now_elapsed >= self.next_retry_elapsed_ms {
            match ProcessEventMonitor::start(
                Path::new(PROCESS_EVENTS_BPF_FILE),
                requested_targets,
                requested_tracked_tids,
            ) {
                Ok(monitor) => {
                    println!(
                        "[RS] eBPF进程/线程事件发现已启用: {}",
                        monitor.startup_note()
                    );
                    self.monitor = Some(monitor);
                    self.last_error_log_elapsed_ms = None;
                }
                Err(err) => {
                    self.report_error(now_elapsed, &format!(
                        "eBPF进程发现不可用，继续使用 pid_cache + /proc: {err}"
                    ));
                    self.next_retry_elapsed_ms =
                        now_elapsed.saturating_add(PROCESS_EVENT_RETRY_MS);
                    return;
                }
            }
        }

        let sync_result = self.monitor.as_mut().map(|monitor| {
            monitor
                .sync_target_tgids(monitored_tgids.iter().copied())
                .and_then(|()| monitor.sync_tracked_tids(managed_tids.keys().copied()))
        });
        if let Some(Err(err)) = sync_result {
            self.monitor = None;
            self.next_retry_elapsed_ms = now_elapsed.saturating_add(PROCESS_EVENT_RETRY_MS);
            self.report_error(
                now_elapsed,
                &format!("eBPF目标TGID/TID同步失败，已回退 /proc: {err}"),
            );
        }
    }

    fn event_fds(&self) -> Vec<i32> {
        self.monitor
            .as_ref()
            .map(ProcessEventMonitor::event_fds)
            .unwrap_or_default()
    }

    fn drain(&mut self, now_elapsed: u64) -> ProcessEventBatch {
        let Some(monitor) = self.monitor.as_mut() else {
            return ProcessEventBatch::default();
        };
        match monitor.drain() {
            Ok(batch) => batch,
            Err(err) => {
                self.monitor = None;
                self.next_retry_elapsed_ms = now_elapsed.saturating_add(PROCESS_EVENT_RETRY_MS);
                self.report_error(
                    now_elapsed,
                    &format!("eBPF进程事件读取失败，触发完整校验并回退 /proc: {err}"),
                );
                ProcessEventBatch {
                    dropped: 1,
                    ..ProcessEventBatch::default()
                }
            }
        }
    }

    fn report_error(&mut self, now_elapsed: u64, message: &str) {
        let due = self.last_error_log_elapsed_ms.is_none_or(|last| {
            now_elapsed < last
                || now_elapsed.saturating_sub(last) >= PROCESS_EVENT_ERROR_LOG_MS
        });
        if due {
            eprintln!("[RS] {message}");
            self.last_error_log_elapsed_ms = Some(now_elapsed);
        }
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
#[derive(Debug, Default)]
struct ProcessEventBatch {
    process_pids: BTreeSet<i32>,
    thread_tgids: BTreeSet<i32>,
    renamed_tgids: BTreeSet<i32>,
    exited_tids: BTreeSet<i32>,
    exited_tgids: BTreeSet<i32>,
    submitted: u64,
    dropped: u64,
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
#[derive(Debug, Default)]
struct ProcessDiscovery;

#[cfg(not(any(target_os = "android", target_os = "linux")))]
impl ProcessDiscovery {
    fn ensure_started_and_sync(
        &mut self,
        _package_count: usize,
        _known_pids: &BTreeSet<i32>,
        _managed_tids: &HashMap<i32, ManagedTidEntry>,
        _now_elapsed: u64,
    ) {
    }

    fn event_fds(&self) -> Vec<i32> {
        Vec::new()
    }

    fn drain(&mut self, _now_elapsed: u64) -> ProcessEventBatch {
        ProcessEventBatch::default()
    }
}

fn wait_for_daemon_wake(
    file_monitor: Option<&RuntimeFileMonitor>,
    process_discovery: &ProcessDiscovery,
    timeout: Duration,
) -> io::Result<()> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let mut poll_fds = Vec::<libc::pollfd>::with_capacity(2);
        if let Some(monitor) = file_monitor {
            poll_fds.push(libc::pollfd {
                fd: monitor.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            });
        }
        for fd in process_discovery.event_fds() {
            poll_fds.push(libc::pollfd {
                fd,
                events: libc::POLLIN,
                revents: 0,
            });
        }
        if poll_fds.is_empty() {
            thread::sleep(timeout);
            return Ok(());
        }
        let timeout_ms = timeout.as_millis().min(i32::MAX as u128) as i32;
        let result = unsafe {
            libc::poll(
                poll_fds.as_mut_ptr(),
                poll_fds.len() as libc::nfds_t,
                timeout_ms,
            )
        };
        if result < 0 {
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::Interrupted {
                return Err(err);
            }
        }
        Ok(())
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let _ = (file_monitor, process_discovery);
        thread::sleep(timeout);
        Ok(())
    }
}
