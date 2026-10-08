use crate::affinity::journal::{decode_managed_tid_journal, write_managed_tid_journal_file};
use crate::affinity::journal_format::{parse_managed_tid_journal, serialize_managed_tid_journal};
use crate::affinity::managed_cache::{
    managed_identity_conflicts_with_observation, managed_identity_matches_observation,
    managed_tid_capacity_available, managed_tid_identity_quarantined,
};
use crate::{ManagedTidEntry, MAX_MANAGED_TIDS};
use std::collections::HashMap;
use std::time::UNIX_EPOCH;
use std::{env, fs};

fn entry(last_seen_round: u64, restore_pending: bool) -> ManagedTidEntry {
    ManagedTidEntry {
        tgid: 1,
        tgid_starttime: Some(1),
        starttime: Some(1),
        last_seen_round,
        cpuset_synced: false,
        cpuset_failure_count: 0,
        cpuset_retry_after_elapsed_ms: 0,
        desired_mask_low64: None,
        verified_mask_low64: None,
        last_affinity_check_elapsed_ms: 0,
        next_affinity_check_elapsed_ms: 0,
        original_mask_low64: Some(0xff),
        original_cpuset: Some("/top-app".to_string()),
        restore_persisted: true,
        restore_pending,
        restore_failure_count: 0,
        restore_retry_after_elapsed_ms: 0,
    }
}

#[test]
fn capacity_limit_keeps_existing_entries_and_rejects_only_new_tids() {
    let mut managed = HashMap::new();
    for tid in 1..=MAX_MANAGED_TIDS as i32 {
        managed.insert(tid, entry(tid as u64, false));
    }

    assert!(managed_tid_capacity_available(&managed, 1));
    assert!(!managed_tid_capacity_available(
        &managed,
        MAX_MANAGED_TIDS as i32 + 1
    ));
    assert_eq!(managed.len(), MAX_MANAGED_TIDS);
}

#[test]
fn missing_observed_starttime_preserves_existing_identity() {
    let current = entry(1, false);
    assert!(managed_identity_matches_observation(
        &current,
        (1, None, None)
    ));
    assert!(!managed_identity_conflicts_with_observation(
        &current,
        (1, None, None)
    ));
    assert!(managed_identity_conflicts_with_observation(
        &current,
        (1, Some(1), Some(2))
    ));
}

#[test]
fn managed_tid_journal_round_trip_preserves_restore_baseline() {
    let managed = HashMap::from([(42, entry(7, true))]);
    let encoded = serialize_managed_tid_journal(&managed, "boot-test", "QiXiaRs");
    let decoded = parse_managed_tid_journal(&encoded, "boot-test", "QiXiaRs").unwrap();

    let decoded = decoded.get(&42).unwrap();
    let original = managed.get(&42).unwrap();
    assert_eq!(decoded.tgid, original.tgid);
    assert_eq!(decoded.tgid_starttime, original.tgid_starttime);
    assert_eq!(decoded.starttime, original.starttime);
    assert_eq!(decoded.original_mask_low64, original.original_mask_low64);
    assert_eq!(decoded.original_cpuset, original.original_cpuset);
    assert!(decoded.restore_persisted);
    assert!(!decoded.restore_pending);
    assert!(
        parse_managed_tid_journal(&encoded, "other-boot", "QiXiaRs")
            .unwrap()
            .is_empty()
    );
}

#[test]
fn corrupt_journal_header_and_row_enter_identity_quarantine() {
    let bad_header = decode_managed_tid_journal("broken\n", "boot-test", "QiXiaRs");
    assert!(bad_header.entries.is_empty());
    assert!(bad_header.quarantine_existing);
    assert!(bad_header.warning.is_some());

    let bad_row = decode_managed_tid_journal(
        "QIXIA_MANAGED_TIDS_V1\tboot-test\tQiXiaRs\n42\tbad\n",
        "boot-test",
        "QiXiaRs",
    );
    assert!(bad_row.entries.is_empty());
    assert!(bad_row.quarantine_existing);
    assert!(bad_row.warning.is_some());

    let other_boot = decode_managed_tid_journal(
        "QIXIA_MANAGED_TIDS_V1\told-boot\tQiXiaRs\n",
        "boot-test",
        "QiXiaRs",
    );
    assert!(!other_boot.quarantine_existing);
    assert!(other_boot.warning.is_none());
}

#[test]
fn corrupt_journal_quarantine_expires_only_for_new_thread_identity() {
    assert!(managed_tid_identity_quarantined(
        false,
        Some(100),
        Some(100)
    ));
    assert!(!managed_tid_identity_quarantined(
        false,
        Some(101),
        Some(100)
    ));
    assert!(!managed_tid_identity_quarantined(
        true,
        Some(100),
        Some(100)
    ));
}

#[test]
fn journal_commit_failure_removes_temporary_file_and_can_retry() {
    let unique = std::time::SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let target = env::temp_dir().join(format!(
        "qixia-managed-journal-test-{}-{unique}",
        std::process::id()
    ));
    fs::create_dir(&target).unwrap();
    let temporary = target.with_extension(format!("tmp.{}", std::process::id()));

    assert!(write_managed_tid_journal_file(&target, "test").is_err());
    assert!(!temporary.exists());

    fs::remove_dir(&target).unwrap();
}
