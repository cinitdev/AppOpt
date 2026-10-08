use std::collections::BTreeSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum RuleHealthStatus {
    Pending,
    Valid,
    Missed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct RuleHealthLifecycle {
    pub(super) entered_elapsed_ms: u64,
    pub(super) entered_wall_ms: u64,
}

#[derive(Debug, Clone)]
pub(super) struct RuleHealthEntry {
    pub(super) kind: char,
    pub(super) owner: String,
    pub(super) target: String,
    pub(super) status: RuleHealthStatus,
    pub(super) miss_count: u32,
    pub(super) first_observed_at: u64,
    pub(super) last_matched_at: u64,
    pub(super) last_checked_at: u64,
    pub(super) last_checked_boot_id: String,
    pub(super) last_checked_lifecycle_elapsed_ms: u64,
    pub(super) rule_line: String,
}

#[derive(Debug)]
pub(super) struct CompletedScan {
    pub(super) at: u64,
    pub(super) observed_owners: BTreeSet<String>,
}

#[derive(Debug)]
pub(super) struct ObservationSession {
    pub(super) started: u64,
    pub(super) lifecycle: RuleHealthLifecycle,
    pub(super) checked: bool,
    pub(super) full_scan: Option<CompletedScan>,
    pub(super) full_scan_attempted: bool,
    pub(super) eligible_keys: BTreeSet<String>,
}
