#[macro_use]
mod event_log;
mod calibration;
mod command_protocol;
mod package_processes;
mod runtime_clock;
mod private_storage;
mod recent_usage;
use runtime_clock::elapsed_realtime_ms;
mod auto_affinity;
mod auto_history;
mod fps;
#[path = "daemon_core/rule_health/mod.rs"]
mod rule_health;

// main.rs 只保留模块声明和聚合入口。
//
// 规则健康、亲和性和主循环使用独立 Rust 模块与显式接口。
// 入口、配置、进程索引、采集及后台服务均使用编译器检查的模块边界。
#[path = "daemon_core/preamble.rs"]
mod preamble;
use preamble::*;
#[path = "daemon_core/file_event.rs"]
mod file_event;
use file_event::*;
#[path = "daemon_core/entry.rs"]
mod entry;
#[path = "daemon_core/rule_syntax.rs"]
mod rule_syntax;
#[path = "daemon_core/runtime_cache.rs"]
mod runtime_cache;
use runtime_cache::*;
#[path = "daemon_core/loop/mod.rs"]
mod daemon_loop;
use daemon_loop::daemon_loop;
#[path = "daemon_core/cli.rs"]
mod cli;
use cli::*;
#[path = "daemon_core/config.rs"]
mod config;
use config::*;
#[path = "daemon_core/scan.rs"]
mod scan;
use scan::*;

#[path = "daemon_core/affinity/mod.rs"]
mod affinity;
use affinity::{apply_hits, read_present_cpus, ApplyPolicy, CpuMask};
#[path = "daemon_core/procfs.rs"]
mod procfs;
use procfs::*;
#[path = "daemon_core/process_tracker.rs"]
mod process_tracker;
use process_tracker::*;
#[path = "daemon_core/app_state.rs"]
mod app_state;
use app_state::*;
#[path = "daemon_core/control_socket.rs"]
mod control_socket;
use control_socket::*;
#[path = "daemon_core/wildcard.rs"]
mod wildcard;
use wildcard::*;

fn main() {
    entry::run();
}
