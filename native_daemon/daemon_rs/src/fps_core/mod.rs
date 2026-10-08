//! FPS 采集后端与传输封装为内部模块，核心分配使用独立工作线程。
use super::*;
mod preamble;
use preamble::*;
mod monitor;

mod command;
use command::*;

mod fallback;
use fallback::*;

mod binder;
use binder::*;

mod socket;
use socket::*;

pub(crate) use preamble::start_fps_thread;
