use crate::constants::MAX_ERROR_DETAILS;
use std::error::Error;
use std::ffi::CString;
use std::fs;

pub(crate) fn cstring_lossy(s: impl AsRef<str>) -> CString {
    let cleaned = s.as_ref().replace('\0', " ");
    CString::new(cleaned).unwrap_or_else(|_| CString::new("unknown").unwrap())
}

pub(crate) fn pid_role_label(pid: u32, target_pkg: Option<&str>) -> String {
    let Some(pkg) = target_pkg.filter(|pkg| !pkg.is_empty()) else {
        return "目标进程".to_string();
    };
    let Ok(raw) = fs::read(format!("/proc/{pid}/cmdline")) else {
        return "同包进程".to_string();
    };
    let name = raw.split(|byte| *byte == 0).next().unwrap_or_default();
    let Ok(name) = std::str::from_utf8(name) else {
        return "同包进程".to_string();
    };
    let name = name.rsplit('/').next().unwrap_or(name);
    if name == pkg {
        "主进程".to_string()
    } else if let Some(suffix) = name
        .strip_prefix(pkg)
        .and_then(|rest| rest.strip_prefix(':'))
    {
        if suffix.is_empty() {
            "子进程".to_string()
        } else {
            format!("子进程:{suffix}")
        }
    } else {
        "同包进程".to_string()
    }
}

pub(crate) fn single_line_error(error: &str) -> String {
    error.split_whitespace().collect::<Vec<_>>().join(" ")
}

pub(crate) fn compact_error_details(errors: &[String]) -> String {
    let mut message = errors
        .iter()
        .take(MAX_ERROR_DETAILS)
        .map(|error| single_line_error(error))
        .collect::<Vec<_>>()
        .join("; ");
    let suppressed = errors.len().saturating_sub(MAX_ERROR_DETAILS);
    if suppressed > 0 {
        if !message.is_empty() {
            message.push_str("; ");
        }
        message.push_str(&format!("另有 {suppressed} 条同类错误已省略"));
    }
    message
}

pub(crate) fn backend_selection_report(
    ring_status: &str,
    ring_error: Option<&str>,
    stats_status: &str,
    stats_error: Option<&str>,
    perf_status: &str,
) -> String {
    let status = |name: &str, value: &str, error: Option<&str>| match error {
        Some(error) => format!("{name}={value}（{}）", single_line_error(error)),
        None => format!("{name}={value}"),
    };
    format!(
        "{} | {} | {} | SurfaceFlinger=待命",
        status("RingBuf", ring_status, ring_error),
        status("StatsMap", stats_status, stats_error),
        status("PerfEvent", perf_status, None),
    )
}

pub(crate) fn error_chain(error: &dyn Error) -> String {
    let mut message = error.to_string();
    let mut source = error.source();
    while let Some(error) = source {
        let detail = error.to_string();
        if !detail.is_empty() && !message.ends_with(&detail) {
            message.push_str(": ");
            message.push_str(&detail);
        }
        source = error.source();
    }
    message
}
