//! CPU 亲和性的应用与验证，以及 cpuset 的管理与恢复。
mod apply;
pub(crate) use apply::{apply_hits, ApplyPolicy};
mod verify;
pub(crate) use verify::{verify_managed_affinity, AffinityVerifyPolicy};
mod diagnostics;
mod mask;
mod state;
pub(crate) use mask::CpuMask;
mod syscall;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) use syscall::{read_allowed_mask_syscall, set_affinity};
pub(crate) mod cpuset;
mod cpuset_owned;
pub(crate) use cpuset_owned::cleanup as cleanup_owned_cpuset_dirs;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) use cpuset_owned::cleanup_after_scan as cleanup_owned_cpuset_dirs_after_scan;
mod restore;
pub(crate) use cpuset::read_present_cpus;
mod managed_cache;
#[cfg(test)]
mod tests;
pub(crate) use managed_cache::{refresh_managed_tid_cache, restore_all_managed_tids};
mod journal;
pub(crate) use journal::{
    ensure_managed_tid_journal_loaded, load_managed_tid_journal, managed_tid_starttime_cutoff,
    sync_managed_tid_journal,
};
mod journal_format;
#[cfg(test)]
mod journal_tests;
