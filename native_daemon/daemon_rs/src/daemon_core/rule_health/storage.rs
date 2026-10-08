use super::rules::{reset_rule_health_packages, rule_health_entry_key};
use super::{
    model::{RuleHealthEntry, RuleHealthStatus},
    RuleHealth,
};
use crate::MAX_CONFIG_OWNER_BYTES;
use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub(super) struct Paths {
    pub(super) state: PathBuf,
    pub(super) reset: PathBuf,
    pub(super) claim: PathBuf,
}

impl Default for Paths {
    fn default() -> Self {
        let base = Path::new("/data/adb/modules/QixiaThreads/config/state");
        Self {
            state: base.join("rule_health.tsv"),
            reset: base.join("rule_health.reset"),
            claim: base.join("rule_health.reset.processing"),
        }
    }
}

pub(super) fn finish_rule_health_update(changed: bool, state: &mut RuleHealth) -> io::Result<()> {
    if changed {
        state.dirty = true;
        state.index_dirty = true;
    }
    if !state.dirty {
        return Ok(());
    }
    write_rule_health(&state.paths.state, &state.entries)?;
    state.dirty = false;
    Ok(())
}

pub(super) fn ensure_rule_health_loaded(state: &mut RuleHealth) -> io::Result<()> {
    if state.loaded {
        return Ok(());
    }
    state.entries = load_rule_health(&state.paths.state)?;
    state.loaded = true;
    state.index_dirty = true;
    Ok(())
}

pub(super) fn consume_rule_health_reset_request(
    state: &mut RuleHealth,
) -> io::Result<BTreeSet<String>> {
    ensure_rule_health_loaded(state)?;
    let claimed = match fs::metadata(&state.paths.claim) {
        Ok(metadata) if metadata.is_file() => true,
        Ok(_) => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "规则健康重新检测认领路径不是普通文件",
            ));
        }
        Err(err) if err.kind() == io::ErrorKind::NotFound => {
            match fs::rename(&state.paths.reset, &state.paths.claim) {
                Ok(()) => true,
                Err(err) if err.kind() == io::ErrorKind::NotFound => false,
                Err(err) => return Err(err),
            }
        }
        Err(err) => return Err(err),
    };
    if !claimed {
        return Ok(BTreeSet::new());
    }

    let content = fs::read_to_string(&state.paths.claim)?;
    let requested = content
        .lines()
        .filter_map(normalize_rule_health_reset_package)
        .collect::<BTreeSet<_>>();
    let (reset_count, reset_packages) = reset_rule_health_packages(state, &requested);
    // 状态文件写盘成功后才能删除认领文件。进程在两步之间退出时，下次启动会
    // 重新消费 processing 文件；重复处理是幂等的，不会丢失用户请求。
    finish_rule_health_update(false, state)?;
    fs::remove_file(&state.paths.claim)?;
    if reset_count > 0 {
        log_info!(
            "[RS] 规则健康已重新检测: 应用={} 规则={} [{}]",
            reset_packages.len(),
            reset_count,
            reset_packages.iter().cloned().collect::<Vec<_>>().join(",")
        );
    }
    Ok(reset_packages)
}

pub(super) fn normalize_rule_health_reset_package(raw: &str) -> Option<String> {
    let pkg = raw.trim().trim_start_matches('\u{feff}');
    if pkg.is_empty()
        || pkg.starts_with('#')
        || pkg.len() > MAX_CONFIG_OWNER_BYTES
        || pkg.contains(':')
        || pkg.starts_with('.')
        || pkg.ends_with('.')
        || pkg.split('.').any(str::is_empty)
        || !pkg
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.'))
    {
        return None;
    }
    Some(pkg.to_string())
}

pub(super) fn load_rule_health(path: &Path) -> io::Result<HashMap<String, RuleHealthEntry>> {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(HashMap::new()),
        Err(err) => return Err(err),
    };
    let mut entries = HashMap::new();
    for raw in text.lines() {
        let parts = raw.split('\t').collect::<Vec<_>>();
        if parts.len() < 9 {
            continue;
        }
        let Some(kind) = parts[0].chars().next() else {
            continue;
        };
        let owner = unescape_rule_health_field(parts[1]);
        let target = unescape_rule_health_field(parts[2]);
        let status = match parts[3] {
            "valid" => RuleHealthStatus::Valid,
            "missed" => RuleHealthStatus::Missed,
            _ => RuleHealthStatus::Pending,
        };
        let has_lifecycle_identity = parts.len() >= 11;
        let entry = RuleHealthEntry {
            kind,
            owner,
            target,
            status,
            miss_count: parts[4].parse().unwrap_or(0),
            first_observed_at: parts[5].parse().unwrap_or(0),
            last_matched_at: parts[6].parse().unwrap_or(0),
            last_checked_at: parts[7].parse().unwrap_or(0),
            last_checked_boot_id: if has_lifecycle_identity {
                unescape_rule_health_field(parts[8])
            } else {
                String::new()
            },
            last_checked_lifecycle_elapsed_ms: if has_lifecycle_identity {
                parts[9].parse().unwrap_or(0)
            } else {
                0
            },
            rule_line: unescape_rule_health_field(
                &parts[if has_lifecycle_identity { 10 } else { 8 }..].join("\t"),
            ),
        };
        entries.insert(rule_health_entry_key(&entry), entry);
    }
    Ok(entries)
}

pub(super) fn write_rule_health(
    path: &Path,
    entries: &HashMap<String, RuleHealthEntry>,
) -> io::Result<()> {
    let mut rows = entries.values().collect::<Vec<_>>();
    rows.sort_by_key(|entry| (entry.owner.as_str(), entry.kind, entry.target.as_str()));
    let mut output = String::new();
    for entry in rows {
        let status = match entry.status {
            RuleHealthStatus::Pending => "pending",
            RuleHealthStatus::Valid => "valid",
            RuleHealthStatus::Missed => "missed",
        };
        output.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            entry.kind,
            escape_rule_health_field(&entry.owner),
            escape_rule_health_field(&entry.target),
            status,
            entry.miss_count,
            entry.first_observed_at,
            entry.last_matched_at,
            entry.last_checked_at,
            escape_rule_health_field(&entry.last_checked_boot_id),
            entry.last_checked_lifecycle_elapsed_ms,
            escape_rule_health_field(&entry.rule_line)
        ));
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = path.with_extension(format!("tsv.tmp.{}", std::process::id()));
    let commit_result = (|| -> io::Result<()> {
        let mut file = fs::OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temp)?;
        file.write_all(output.as_bytes())?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, path)
    })();
    if let Err(err) = commit_result {
        let _ = fs::remove_file(&temp);
        return Err(err);
    }
    if let Some(parent) = path.parent() {
        // 部分 Android 特殊挂载不支持目录 fsync；rename 已提交后不应因此每轮重写。
        let _ = fs::File::open(parent).and_then(|dir| dir.sync_all());
    }
    Ok(())
}

pub(super) fn escape_rule_health_field(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => output.push_str("\\\\"),
            '\t' => output.push_str("\\t"),
            '\n' => output.push_str("\\n"),
            _ => output.push(ch),
        }
    }
    output
}

pub(super) fn unescape_rule_health_field(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            output.push(ch);
            continue;
        }
        match chars.next() {
            Some('t') => output.push('\t'),
            Some('n') => output.push('\n'),
            Some('\\') => output.push('\\'),
            Some(other) => {
                output.push('\\');
                output.push(other);
            }
            None => output.push('\\'),
        }
    }
    output
}
