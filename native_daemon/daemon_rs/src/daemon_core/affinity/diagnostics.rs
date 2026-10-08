use crate::{
    proc_thread_identity_matches, ApplyStats, ProcHit, ThreadAction, MAX_ERROR_DETAILS_PER_ROUND,
};
use std::io;

impl ApplyStats {
    pub(crate) fn merge(&mut self, other: ApplyStats) {
        self.applied = self.applied.saturating_add(other.applied);
        self.skipped = self.skipped.saturating_add(other.skipped);
        self.failed = self.failed.saturating_add(other.failed);
        self.restricted = self.restricted.saturating_add(other.restricted);
        self.invalid_rules = self.invalid_rules.saturating_add(other.invalid_rules);
        self.mismatched = self.mismatched.saturating_add(other.mismatched);
        self.cpuset_failed = self.cpuset_failed.saturating_add(other.cpuset_failed);
    }
}

pub(crate) fn action_identity_is_current(
    hit: &ProcHit,
    action: &ThreadAction,
    phase: &str,
    detail_log: bool,
    identity_details: &mut usize,
) -> bool {
    match proc_thread_identity_matches(hit, action) {
        Ok(true) => true,
        Ok(false) => {
            if should_log_detail(detail_log, identity_details) {
                log_info!(
                    "[RS] 跳过已变化的进程/线程身份 阶段={} 进程={} 线程={} 线程名={} 规则={}",
                    phase, hit.pid, action.tid, action.name, action.rule
                );
            }
            false
        }
        Err(err) => {
            if should_log_detail(detail_log, identity_details) {
                log_error!(
                    "[RS] 复核进程/线程身份失败 阶段={} 进程={} 线程={} 线程名={} 规则={} 错误={}",
                    phase,
                    hit.pid,
                    action.tid,
                    action.name,
                    action.rule,
                    error_text_zh(&err)
                );
            }
            false
        }
    }
}

pub(crate) fn should_log_detail(detail_log: bool, detail_count: &mut usize) -> bool {
    if !detail_log {
        return false;
    }
    let should_log = *detail_count < MAX_ERROR_DETAILS_PER_ROUND;
    *detail_count += 1;
    should_log
}

pub(crate) fn log_limited_detail_count(kind: &str, detail_count: usize) {
    if detail_count > MAX_ERROR_DETAILS_PER_ROUND {
        let level = match kind {
            "进程/线程身份已变化" => crate::event_log::Level::Info,
            "绑核抢写恢复" | "CPU规则受设备核心范围限制" | "cpuset辅助写入失败" | "绑核被系统改写" => crate::event_log::Level::Warning,
            _ => crate::event_log::Level::Error,
        };
        crate::event_log::emit(level, format_args!(
            "[RS] {kind}: 本轮共 {} 条, 仅显示前 {} 条明细",
            detail_count, MAX_ERROR_DETAILS_PER_ROUND
        ));
    }
}

pub(crate) fn is_cpuset_expected_reject(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(1 | 13 | 19 | 22 | 30 | 95))
        || matches!(
            err.kind(),
            io::ErrorKind::PermissionDenied | io::ErrorKind::InvalidInput
        )
}

pub(crate) fn is_affinity_restricted_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(1 | 13 | 22))
        || matches!(
            err.kind(),
            io::ErrorKind::PermissionDenied | io::ErrorKind::InvalidInput
        )
}

pub(crate) fn is_thread_gone_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(2 | 3)) || err.kind() == io::ErrorKind::NotFound
}

pub(crate) fn error_text_zh(err: &io::Error) -> String {
    match err.raw_os_error() {
        Some(1) => "权限不足(EPERM/1), 内核或安全策略拒绝操作".to_string(),
        Some(2) => "路径不存在(ENOENT/2), 目标进程或线程可能已经退出".to_string(),
        Some(3) => "线程不存在(ESRCH/3), 目标线程可能已经结束".to_string(),
        Some(13) => "权限不足(EACCES/13), 无法访问目标文件或线程".to_string(),
        Some(16) => "资源忙(EBUSY/16), 系统暂时无法完成操作".to_string(),
        Some(19) => "设备不存在(ENODEV/19), 目标 cpuset 或系统节点不可用".to_string(),
        Some(22) => "无效参数(EINVAL/22), CPU 核心范围对当前线程不可用或 CPU mask 非法".to_string(),
        Some(code) => format!("系统错误(OS {code})"),
        None => match err.kind() {
            io::ErrorKind::NotFound => "路径不存在, 目标进程或线程可能已经退出".to_string(),
            io::ErrorKind::PermissionDenied => "权限不足, 内核或安全策略拒绝操作".to_string(),
            io::ErrorKind::InvalidInput => {
                "无效参数, CPU 核心范围对当前线程不可用或 CPU mask 非法".to_string()
            }
            _ => "I/O 操作失败".to_string(),
        },
    }
}
