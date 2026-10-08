use crate::affinity::cpuset::valid_cpuset_relative_path;
use crate::{ManagedTidEntry, MANAGED_TID_STATE_MAGIC, MAX_MANAGED_TIDS};
use std::collections::HashMap;
use std::io;

pub(crate) fn parse_managed_tid_journal(
    content: &str,
    current_boot_id: &str,
    cpuset_name: &str,
) -> io::Result<HashMap<i32, ManagedTidEntry>> {
    let mut lines = content.lines();
    let header = lines
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "线程恢复基线缺少头部"))?;
    let mut header_fields = header.split('\t');
    if header_fields.next() != Some(MANAGED_TID_STATE_MAGIC) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "线程恢复基线版本不兼容",
        ));
    }
    let stored_boot_id = header_fields.next().unwrap_or_default();
    if stored_boot_id.is_empty() || current_boot_id.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "线程恢复基线缺少 boot_id",
        ));
    }
    if stored_boot_id != current_boot_id {
        // TID/starttime 只在同一启动周期内有意义；跨重启状态绝不能用于恢复新线程。
        return Ok(HashMap::new());
    }
    let stored_cpuset_name = header_fields.next().unwrap_or_default();
    if stored_cpuset_name.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "线程恢复基线缺少 cpuset 名称",
        ));
    }

    let mut managed_tids = HashMap::new();
    for (line_index, line) in lines.enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let fields = line.split('\t').collect::<Vec<_>>();
        if fields.len() != 6 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("线程恢复基线第 {} 行字段数量错误", line_index + 2),
            ));
        }
        let parse_number = |value: &str, field: &str| -> io::Result<u64> {
            value.parse::<u64>().map_err(|_| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("线程恢复基线第 {} 行 {field} 无效", line_index + 2),
                )
            })
        };
        let tid_raw = parse_number(fields[0], "TID")?;
        let tgid_raw = parse_number(fields[1], "TGID")?;
        if tid_raw == 0 || tgid_raw == 0 || tid_raw > i32::MAX as u64 || tgid_raw > i32::MAX as u64
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("线程恢复基线第 {} 行进程身份无效", line_index + 2),
            ));
        }
        let tid = tid_raw as i32;
        let tgid = tgid_raw as i32;
        let tgid_starttime = parse_number(fields[2], "TGID starttime")?;
        let tid_starttime = parse_number(fields[3], "TID starttime")?;
        let original_mask_low64 = if fields[4] == "-" {
            None
        } else {
            Some(u64::from_str_radix(fields[4], 16).map_err(|_| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("线程恢复基线第 {} 行 affinity 无效", line_index + 2),
                )
            })?)
            .filter(|mask| *mask != 0)
        };
        let original_cpuset = decode_managed_tid_cpuset(fields[5]).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("线程恢复基线第 {} 行 cpuset 无效", line_index + 2),
            )
        })?;
        if original_mask_low64.is_none() && original_cpuset.is_none() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("线程恢复基线第 {} 行没有可恢复状态", line_index + 2),
            ));
        }
        let entry = ManagedTidEntry {
            tgid,
            tgid_starttime: Some(tgid_starttime),
            starttime: Some(tid_starttime),
            last_seen_round: 0,
            cpuset_synced: false,
            cpuset_failure_count: 0,
            cpuset_retry_after_elapsed_ms: 0,
            desired_mask_low64: None,
            verified_mask_low64: None,
            last_affinity_check_elapsed_ms: 0,
            next_affinity_check_elapsed_ms: 0,
            original_mask_low64,
            original_cpuset,
            restore_persisted: true,
            restore_pending: false,
            restore_failure_count: 0,
            restore_retry_after_elapsed_ms: 0,
        };
        if !managed_tids.contains_key(&tid) && managed_tids.len() >= MAX_MANAGED_TIDS {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "线程恢复基线超过安全上限",
            ));
        }
        managed_tids.insert(tid, entry);
    }

    // cpuset 名称变化不影响已保存的原 cpuset 和亲和性；新一轮接管会使用当前名称。
    let _ = (stored_cpuset_name, cpuset_name);
    Ok(managed_tids)
}

pub(crate) fn serialize_managed_tid_journal(
    managed_tids: &HashMap<i32, ManagedTidEntry>,
    boot_id: &str,
    cpuset_name: &str,
) -> String {
    let mut rows = managed_tids.iter().collect::<Vec<_>>();
    rows.sort_unstable_by_key(|(tid, _)| **tid);
    let mut output = format!("{MANAGED_TID_STATE_MAGIC}\t{boot_id}\t{cpuset_name}\n");
    for (tid, entry) in rows {
        let (Some(tgid_starttime), Some(tid_starttime)) = (entry.tgid_starttime, entry.starttime)
        else {
            continue;
        };
        if entry.original_mask_low64.is_none() && entry.original_cpuset.is_none() {
            continue;
        }
        let mask = entry
            .original_mask_low64
            .filter(|mask| *mask != 0)
            .map_or_else(|| "-".to_string(), |mask| format!("{mask:016x}"));
        output.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\n",
            tid,
            entry.tgid,
            tgid_starttime,
            tid_starttime,
            mask,
            encode_managed_tid_cpuset(entry.original_cpuset.as_deref())
        ));
    }
    output
}

pub(crate) fn encode_managed_tid_cpuset(value: Option<&str>) -> String {
    let Some(value) = value else {
        return "-".to_string();
    };
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(value.len() * 2);
    for byte in value.as_bytes() {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

pub(crate) fn decode_managed_tid_cpuset(value: &str) -> Option<Option<String>> {
    if value == "-" {
        return Some(None);
    }
    if !value.len().is_multiple_of(2) {
        return None;
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().chunks_exact(2) {
        let pair = std::str::from_utf8(pair).ok()?;
        bytes.push(u8::from_str_radix(pair, 16).ok()?);
    }
    let cpuset = String::from_utf8(bytes).ok()?;
    valid_cpuset_relative_path(&cpuset).then_some(Some(cpuset))
}
