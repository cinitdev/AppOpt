//! 校准采集、历史记录与待确认草稿，按职责明确划分模块。
use qixia_kernel_info::proc::read_cmdline;
use std::collections::{HashMap, HashSet, VecDeque};
use std::fmt::Write as FmtWrite;
use std::fs;
use std::io::{self, Write as IoWrite};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[path = "calibration_core/activity.rs"]
mod activity;
use activity::Activity;
#[path = "calibration_core/preamble.rs"]
mod model;
use model::*;
pub(crate) use model::CALIB_POLICY_FILE;
#[path = "calibration_core/loop.rs"]
mod worker;

#[path = "calibration_core/session.rs"]
mod session;

#[path = "calibration_core/rules.rs"]
mod rules;
use rules::*;

#[path = "calibration_core/draft.rs"]
mod draft;
use draft::*;

#[path = "calibration_core/topology.rs"]
mod topology;
use topology::*;

#[path = "calibration_core/policy.rs"]
mod policy;

#[path = "calibration_core/history.rs"]
mod history;
use history::*;

#[path = "calibration_core/policy_lock.rs"]
mod policy_lock;
use policy_lock::*;

#[path = "calibration_core/procfs.rs"]
mod procfs;
use procfs::*;

pub(crate) use policy::{print_version_diagnostics, PolicyTopologySync};
pub(crate) use worker::start_calibration_thread;
#[cfg(test)]
#[path = "calibration_core/activity_history_tests.rs"]
mod activity_history_tests;
