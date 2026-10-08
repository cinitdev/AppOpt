use super::mask::CpuMask;
use std::{fs, io};

pub(crate) fn read_allowed_mask(pid: i32, tid: i32) -> io::Result<Option<CpuMask>> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        match read_allowed_mask_syscall(tid) {
            Ok(mask) => return Ok(Some(mask)),
            Err(err) if matches!(err.raw_os_error(), Some(22 | 38)) => {}
            Err(err) => return Err(err),
        }
    }
    read_allowed_mask_proc(pid, tid)
}

pub(crate) fn read_allowed_mask_proc(pid: i32, tid: i32) -> io::Result<Option<CpuMask>> {
    let status = fs::read_to_string(format!("/proc/{pid}/task/{tid}/status"))?;
    for line in status.lines() {
        let Some(value) = line.strip_prefix("Cpus_allowed_list:") else {
            continue;
        };
        return Ok(CpuMask::parse(value.trim()));
    }
    Ok(None)
}

#[cfg(any(target_os = "android", target_os = "linux"))]
unsafe extern "C" {
    fn sched_setaffinity(pid: i32, cpusetsize: usize, mask: *const u8) -> i32;
    fn sched_getaffinity(pid: i32, cpusetsize: usize, mask: *mut u8) -> i32;
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn read_allowed_mask_syscall(tid: i32) -> io::Result<CpuMask> {
    let mut mask = CpuMask::empty();
    let rc = unsafe {
        sched_getaffinity(
            tid,
            std::mem::size_of_val(&mask.words),
            mask.words.as_mut_ptr().cast::<u8>(),
        )
    };
    if rc == 0 {
        Ok(mask)
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn set_affinity(tid: i32, mask: &CpuMask) -> io::Result<()> {
    let rc = unsafe {
        sched_setaffinity(
            tid,
            std::mem::size_of_val(&mask.words),
            mask.words.as_ptr().cast::<u8>(),
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(crate) fn set_affinity(_tid: i32, _mask: &CpuMask) -> io::Result<()> {
    Ok(())
}
