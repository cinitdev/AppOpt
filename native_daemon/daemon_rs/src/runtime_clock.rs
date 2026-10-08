use crate::BOOT_ID_FILE;
use std::fs;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    OnceLock,
};
use std::time::{SystemTime, UNIX_EPOCH};

const BOOT_ID_READ_RETRY_MS: u64 = 60_000;
static CURRENT_BOOT_ID: OnceLock<String> = OnceLock::new();
static BOOT_ID_RETRY_AFTER_ELAPSED_MS: AtomicU64 = AtomicU64::new(0);

pub(crate) fn unix_now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn elapsed_realtime_ms() -> u64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    let rc = unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) };
    if rc != 0 || ts.tv_sec < 0 || ts.tv_nsec < 0 {
        return 0;
    }
    ts.tv_sec as u64 * 1000 + ts.tv_nsec as u64 / 1_000_000
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(crate) fn elapsed_realtime_ms() -> u64 {
    // 非 Android/Linux 仅用于宿主机编译检查，不会读取设备 helper 状态。
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

pub(crate) fn current_boot_id(now_elapsed: u64) -> Option<&'static str> {
    if let Some(value) = CURRENT_BOOT_ID.get() {
        return Some(value.as_str());
    }
    let retry_after = BOOT_ID_RETRY_AFTER_ELAPSED_MS.load(Ordering::Relaxed);
    if retry_after > 0 && now_elapsed < retry_after {
        return None;
    }
    let value = fs::read_to_string(BOOT_ID_FILE)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    if let Some(value) = value {
        let _ = CURRENT_BOOT_ID.set(value);
        return CURRENT_BOOT_ID.get().map(String::as_str);
    }
    BOOT_ID_RETRY_AFTER_ELAPSED_MS.store(
        now_elapsed.saturating_add(BOOT_ID_READ_RETRY_MS),
        Ordering::Relaxed,
    );
    None
}
