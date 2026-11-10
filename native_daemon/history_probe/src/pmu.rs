use std::collections::BTreeMap;
#[cfg(any(target_os = "android", target_os = "linux"))]
use std::{fs::File, io::Read, os::fd::FromRawFd};

/// PERF_ATTR_SIZE_VER0（64 字节）兼容旧版 Android 内核与当前 GKI。
#[cfg(any(target_os = "android", target_os = "linux"))]
#[repr(C)]
#[derive(Default)]
struct Attr {
    kind: u32,
    size: u32,
    config: u64,
    period: u64,
    sample_type: u64,
    read_format: u64,
    flags: u64,
    wakeup: u32,
    bp_type: u32,
    config1: u64,
}

#[derive(Clone, Copy, Default)]
#[cfg(any(target_os = "android", target_os = "linux"))]
struct Count {
    value: u64,
    enabled: u64,
    running: u64,
}
#[cfg(any(target_os = "android", target_os = "linux"))]
fn rate(old: Count, new: Count, elapsed_ms: u64) -> Option<f64> {
    if elapsed_ms == 0 || elapsed_ms > 6000 {
        return None;
    }
    let delta = new.value.checked_sub(old.value)?;
    let enabled = new.enabled.checked_sub(old.enabled)?;
    let running = new.running.checked_sub(old.running)?;
    if enabled == 0 || running == 0 || running > enabled || running as f64 / (enabled as f64) < 0.9
    {
        return None;
    }
    let result = delta as f64 * (enabled as f64 / running as f64) / elapsed_ms as f64 / 1000.0;
    (result.is_finite() && (0.0..=20000.0).contains(&result)).then_some(result)
}

#[cfg(any(target_os = "android", target_os = "linux"))]
const RETRY_MS: u64 = 60_000;

#[cfg(any(target_os = "android", target_os = "linux"))]
struct Counter<T> {
    handle: Option<T>,
    previous: Option<(Count, u64)>,
    retry_after: u64,
}
#[cfg(any(target_os = "android", target_os = "linux"))]
impl<T> Default for Counter<T> {
    fn default() -> Self {
        Self {
            handle: None,
            previous: None,
            retry_after: 0,
        }
    }
}
#[cfg(any(target_os = "android", target_os = "linux"))]
impl<T> Counter<T> {
    fn sample(
        &mut self,
        now: u64,
        open: impl FnOnce() -> Option<T>,
        read: impl FnOnce(&mut T) -> Option<Count>,
    ) -> Option<f64> {
        if self.handle.is_none() {
            if now < self.retry_after {
                return None;
            }
            self.retry_after = now.saturating_add(RETRY_MS);
            self.handle = open();
        }
        let count = match read(self.handle.as_mut()?) {
            Some(count) => count,
            None => {
                // 释放失效的文件描述符，但保留对应 CPU 和重试期限。
                self.handle = None;
                self.previous = None;
                self.retry_after = now.saturating_add(RETRY_MS);
                return None;
            }
        };
        let value = self.previous.and_then(|(old, time)| {
            now.checked_sub(time)
                .and_then(|elapsed| rate(old, count, elapsed))
        });
        self.previous = Some((count, now));
        value
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn open_counter(cpu: u32) -> Option<File> {
    let attr = Attr {
        size: std::mem::size_of::<Attr>() as u32,
        read_format: 3,
        flags: 1 << 6,
        ..Attr::default()
    };
    // 统计 CPU 硬件周期（type=0, config=0），不使用中断或事件缓冲区。
    let fd = unsafe {
        libc::syscall(
            libc::SYS_perf_event_open,
            &attr as *const Attr,
            -1i32,
            cpu as i32,
            -1i32,
            8u64,
        ) as i32
    };
    (fd >= 0).then(|| unsafe { File::from_raw_fd(fd) })
}
#[cfg(any(target_os = "android", target_os = "linux"))]
fn read_counter(file: &mut File) -> Option<Count> {
    let mut bytes = [0u8; 24];
    file.read_exact(&mut bytes).ok()?;
    Some(Count {
        value: u64::from_ne_bytes(bytes[..8].try_into().unwrap()),
        enabled: u64::from_ne_bytes(bytes[8..16].try_into().unwrap()),
        running: u64::from_ne_bytes(bytes[16..].try_into().unwrap()),
    })
}

#[derive(Default)]
pub struct Pmu {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    counters: BTreeMap<u32, Counter<File>>,
    #[cfg(any(target_os = "android", target_os = "linux"))]
    next_discovery: u64,
}
impl Pmu {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn sample(&mut self, now: u64) -> BTreeMap<u32, f64> {
        #[allow(unused_mut)]
        let mut values = BTreeMap::new();
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            if now >= self.next_discovery {
                self.next_discovery = now.saturating_add(RETRY_MS);
                if let Some(present) =
                    crate::fs::KernelFs::new("/").read("/sys/devices/system/cpu/present")
                {
                    for cpu in crate::fs::cpu_ids(&present) {
                        self.counters.entry(cpu).or_default();
                    }
                }
            }
            for (&cpu, counter) in &mut self.counters {
                if let Some(value) = counter.sample(now, || open_counter(cpu), read_counter) {
                    values.insert(cpu, value);
                }
            }
        }
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        let _ = now;
        values
    }
}
