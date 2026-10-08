//! 守护进程与历史探针共用的只读 Linux CPU/proc 信息。
//! 不依赖型号名称、不猜测核心数、不启动后台线程，也不写入设备节点。
pub mod cpu;
pub mod proc;
