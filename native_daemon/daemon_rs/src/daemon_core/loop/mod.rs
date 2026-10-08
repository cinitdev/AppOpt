//! 主循环各阶段保持扫描节奏、先记录日志再写入的顺序，以及模式交接语义。
mod cadence;
mod device;
mod execute;
mod lifecycle;
mod model;
mod prepare;
mod process_index;
mod report;
mod round;
mod scan;
pub(crate) use lifecycle::daemon_loop;
#[cfg(test)]
mod tests;
