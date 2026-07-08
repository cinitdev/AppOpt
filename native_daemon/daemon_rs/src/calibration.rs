// 校准模块聚合入口。
//
// 使用 include! 是为了在不改动函数可见性和调用关系的前提下，把原来的大文件按功能拆分。
// 后续如果 Rust 版稳定，可以再逐步改成真正的 mod 子模块。
include!("calibration_core/preamble.rs");
include!("calibration_core/loop.rs");
include!("calibration_core/session.rs");
include!("calibration_core/rules.rs");
include!("calibration_core/topology.rs");
include!("calibration_core/policy.rs");
include!("calibration_core/history.rs");
include!("calibration_core/config_write.rs");
include!("calibration_core/procfs.rs");
