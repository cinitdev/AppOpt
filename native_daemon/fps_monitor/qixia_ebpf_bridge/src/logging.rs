//! 可选的宿主日志接收器；独立 C ABI 调用方仍通过 stdout/stderr 输出诊断信息。
use std::fmt;
use std::io::{self, Write};
use std::sync::OnceLock;

static SINK: OnceLock<fn(bool, &str)> = OnceLock::new();

/// 在启动监测器前安装一次；`warning` 表示可恢复问题的诊断信息。
pub fn set_log_sink(sink: fn(bool, &str)) { let _ = SINK.set(sink); }

pub(crate) fn emit(warning: bool, args: fmt::Arguments<'_>) {
    if let Some(sink) = SINK.get() { sink(warning, &args.to_string()); }
    else if warning { let _ = writeln!(io::stderr(), "{args}"); }
    else { let _ = writeln!(io::stdout(), "{args}"); }
}

macro_rules! log_info { ($($arg:tt)*) => { $crate::logging::emit(false, format_args!($($arg)*)) }; }
macro_rules! log_warn { ($($arg:tt)*) => { $crate::logging::emit(true, format_args!($($arg)*)) }; }
