use crate::{ApplyStats, FileKey, FullScanEvidence, ProcHit};
use std::collections::BTreeSet;
use std::time::{Duration, Instant};

pub(super) struct PreparedRound {
    pub(super) round_start: Instant,
    pub(super) scan_clock: u64,
    pub(super) foreground_state: crate::rule_health::ForegroundState,
    pub(super) focused_package: Option<String>,
    pub(super) config_key: FileKey,
    pub(super) uid_key: Option<FileKey>,
    pub(super) rule_config_changed: bool,
    pub(super) config_changed: bool,
    pub(super) index_rebuilt: bool,
    pub(super) foreground_event_round: bool,
    pub(super) proc_total: Option<u64>,
    pub(super) health_scan_packages: BTreeSet<String>,
    pub(super) targeted_scan_packages: BTreeSet<String>,
    pub(super) full_scan: bool,
    pub(super) scan_reason: &'static str,
}

pub(super) struct ScannedRound {
    pub(super) hits: Vec<ProcHit>,
    pub(super) previous_known_pids: BTreeSet<i32>,
    pub(super) priority_pids: BTreeSet<i32>,
    pub(super) scan_finished_at: u64,
    pub(super) scan_complete: bool,
    pub(super) health_incomplete_packages: BTreeSet<String>,
    pub(super) full_scan_evidence: Option<FullScanEvidence>,
    pub(super) scan_elapsed: Duration,
    pub(super) scan_reason: &'static str,
}

pub(super) struct AppliedRound {
    pub(super) stats: ApplyStats,
    pub(super) apply_elapsed: Duration,
    pub(super) detail_log: bool,
    pub(super) hit_preview_log: bool,
    pub(super) known_pids: usize,
    pub(super) processes: usize,
}
