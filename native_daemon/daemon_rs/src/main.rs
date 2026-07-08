mod calibration;
mod fps;

// main.rs 只保留模块声明和聚合入口。
//
// daemon_core 里的文件是按功能拆出来的，但仍通过 include! 保持在同一个 crate 根作用域。
// 这么做是低风险拆分：不需要把大量私有函数改成 pub(crate)，也不会因为模块边界改变行为。
include!("daemon_core/preamble.rs");
include!("daemon_core/entry.rs");
include!("daemon_core/loop.rs");
include!("daemon_core/cli.rs");
include!("daemon_core/config.rs");
include!("daemon_core/scan.rs");
include!("daemon_core/affinity.rs");
include!("daemon_core/procfs.rs");
include!("daemon_core/app_state.rs");
include!("daemon_core/control_socket.rs");
include!("daemon_core/wildcard.rs");
