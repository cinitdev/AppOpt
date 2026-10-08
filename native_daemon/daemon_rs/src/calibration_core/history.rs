use super::*;
#[path = "history_retention.rs"]
mod retention;
// 校准历史写入。
//
// history/<pkg>.log 是 App 历史记录页面的数据源，不只是调试日志。
// 每次校准写一个 session：
// - 第一行：# <epoch> <rounds>
// - 后续行：avg max name|series[,series...]|child-thread-detail
//
// 子进程线程明细跟在子进程整体负载后面，方便 App 展开查看“哪个线程贡献了子进程负载”，
// 规则建议与历史各自筛选，子进程活跃线程同样可以生成建议。
pub(super) fn select_history_records(records: &[LoadRecord]) -> Vec<&LoadRecord> {
    let mut rows = records
        .iter()
        .filter(|record| record.sample_count > 0 && record.activity.worth_saving(record.avg()))
        .collect::<Vec<_>>();
    rows.sort_by(|a, b| {
        // 先过滤再分配容量，休眠子进程不能挤掉真正活跃的主进程线程。
        b.is_process
            .cmp(&a.is_process)
            .then_with(|| b.avg().total_cmp(&a.avg()))
            .then_with(|| b.max_pct.total_cmp(&a.max_pct))
            .then_with(|| a.owner.cmp(&b.owner))
            .then_with(|| a.name.cmp(&b.name))
    });

    rows
}

pub(super) fn select_history_child_threads<'a>(
    records: &[&LoadRecord],
    child_threads: &'a HashMap<ChildThreadKey, ChildThreadSummary>,
    total_samples: usize,
) -> Vec<&'a ChildThreadSummary> {
    let owners = records
        .iter()
        .filter(|record| record.is_process)
        .map(|record| record.owner.as_str())
        .collect::<HashSet<_>>();
    let mut rows = child_threads
        .values()
        .filter(|summary| owners.contains(summary.owner.as_str()))
        .filter(|summary| summary.activity.worth_saving(summary.avg(total_samples)))
        .collect::<Vec<_>>();
    rows.sort_by(|a, b| {
        b.avg(total_samples)
            .total_cmp(&a.avg(total_samples))
            .then_with(|| b.max_pct.total_cmp(&a.max_pct))
            .then_with(|| a.owner.cmp(&b.owner))
            .then_with(|| a.name.cmp(&b.name))
    });
    rows
}

pub(super) fn write_history(
    pkg: &str,
    history_rounds: usize,
    sample_rounds: usize,
    records: &[&LoadRecord],
    child_threads: &HashMap<ChildThreadKey, ChildThreadSummary>,
    storage: Option<&crate::private_storage::Storage>,
) -> io::Result<usize> {
    let epoch = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let Some((current, written_rows)) =
        format_history(epoch, history_rounds, sample_rounds, records, child_threads)?
    else {
        return Ok(0);
    };
    let resolved;
    let storage = match storage {
        Some(storage) => storage,
        None => { resolved = crate::private_storage::resolve()?; &resolved }
    };
    let root = storage.prepare_root()?;
    // 共享锁放在可删除的待导入目录之外，创建或删除该目录时均需持锁。
    let _lock = retention::HistoryDirectoryLock::acquire(&root)?;
    let directory = storage.prepare("history")?;
    let path = directory.join(format!("{}.log", safe_file_name(pkg)));

    // 单文件先限量，发布后在同一锁内收敛到所有应用合计最近 10 次。
    let old = match fs::read_to_string(&path) {
        Ok(old) => old,
        Err(err) if err.kind() == io::ErrorKind::NotFound => String::new(),
        Err(err) => return Err(err),
    };
    let recent = recent_history_tail(&old, HISTORY_MAX_SESSIONS.saturating_sub(1));
    let tmp = path.with_extension("log.rust.tmp");
    let mut output = fs::File::create(&tmp)?;
    if !recent.is_empty() {
        output.write_all(recent.as_bytes())?;
        if !recent.ends_with('\n') {
            output.write_all(b"\n")?;
        }
    }
    output.write_all(current.as_bytes())?;
    output.flush()?;
    output.sync_data()?;
    drop(output);
    fs::rename(tmp, path)?;
    if let Err(error) = retention::prune(&directory, HISTORY_MAX_SESSIONS) {
        log_warn!("[校准历史] 清理超额记录失败: {error}");
    }
    Ok(written_rows)
}

pub(super) fn format_history(
    epoch: u64,
    history_rounds: usize,
    sample_rounds: usize,
    records: &[&LoadRecord],
    child_threads: &HashMap<ChildThreadKey, ChildThreadSummary>,
) -> io::Result<Option<(String, usize)>> {
    let child_rows = select_history_child_threads(records, child_threads, sample_rounds);
    let mut current = String::new();
    // 第二列保持旧格式的“半秒单位”，但由真实有效时长换算，App 无需迁移数据库。
    writeln!(&mut current, "# {epoch} {history_rounds}").map_err(fmt_to_io)?;
    let mut written_rows = 0usize;
    for record in records.iter().copied() {
        if !record.activity.worth_saving(record.avg()) {
            continue;
        }
        let name = if record.is_process {
            &record.owner
        } else {
            &record.name
        };
        if record.sample_count == 0 {
            continue;
        }
        let details = if record.is_process {
            process_history_details(&record.owner, &child_rows, sample_rounds)
        } else {
            String::new()
        };
        write!(
            &mut current,
            "{:.1} {:.1} {}|",
            record.avg(),
            record.max_pct,
            safe_history_name(name)
        )
        .map_err(fmt_to_io)?;
        append_sample_series(&mut current, &record.series_values()).map_err(fmt_to_io)?;
        if details.is_empty() {
            current.push('\n');
        } else {
            writeln!(&mut current, "|{details}").map_err(fmt_to_io)?;
        }
        written_rows += 1;
    }

    if written_rows == 0 {
        return Ok(None);
    }

    Ok(Some((current, written_rows)))
}

pub(super) fn append_sample_series(out: &mut String, samples: &VecDeque<f32>) -> std::fmt::Result {
    for (index, value) in samples.iter().enumerate() {
        if index != 0 {
            out.push(',');
        }
        write!(out, "{value:.1}")?;
    }
    Ok(())
}

pub(super) fn recent_history_tail(old: &str, max_sessions: usize) -> &str {
    if old.trim().is_empty() || max_sessions == 0 {
        return "";
    }

    let mut starts = Vec::new();
    let mut line_start = 0usize;
    for line in old.split_inclusive('\n') {
        if line.starts_with('#') {
            starts.push(line_start);
        }
        line_start += line.len();
    }
    if line_start < old.len() && old[line_start..].starts_with('#') {
        starts.push(line_start);
    }

    if starts.len() <= max_sessions {
        return old;
    }
    let keep_from = starts[starts.len() - max_sessions];
    &old[keep_from..]
}

pub(super) fn fmt_to_io(_: std::fmt::Error) -> io::Error {
    io::Error::other("format history failed")
}

pub(super) fn child_thread_details(
    owner: &str,
    child_threads: &[&ChildThreadSummary],
    total_samples: usize,
) -> String {
    let rows = child_threads
        .iter()
        .filter(|summary| summary.owner == owner)
        .collect::<Vec<_>>();
    if rows.is_empty() {
        return String::new();
    }
    let body = rows
        .into_iter()
        .map(|summary| {
            format!(
                "{},{:.2},{:.2}",
                safe_history_name(&summary.name),
                summary.avg(total_samples),
                summary.max_pct
            )
        })
        .collect::<Vec<_>>()
        .join(";");
    // v3 的名称字段使用 e1:UTF-8 百分号编码；App 仍兼容旧 v2 下划线格式。
    format!("v3:{body}")
}

pub(super) fn process_history_details(
    owner: &str,
    child_threads: &[&ChildThreadSummary],
    total_samples: usize,
) -> String {
    let details = child_thread_details(owner, child_threads, total_samples);
    format!("v3p:{}", details.strip_prefix("v3:").unwrap_or(&details))
}

#[cfg(test)]
mod process_history_marker_tests {
    use super::*;

    #[test]
    pub(super) fn process_rows_are_marked_even_without_child_threads() {
        assert_eq!(
            process_history_details("com.example:worker", &[], 60),
            "v3p:"
        );
    }

    #[test]
    pub(super) fn history_tail_keeps_only_requested_complete_sessions() {
        let old = (0..8)
            .map(|index| format!("# {index} 60\n1.0 1.0 e1:t|1.0\n"))
            .collect::<String>();
        let tail = recent_history_tail(&old, 6);
        assert!(tail.starts_with("# 2 60\n"));
        assert_eq!(tail.lines().filter(|line| line.starts_with('#')).count(), 6);
    }

    #[test]
    pub(super) fn sample_series_is_serialized_in_queue_order() {
        let samples = VecDeque::from([3.0, 1.26, 9.0]);
        let mut out = String::new();
        append_sample_series(&mut out, &samples).unwrap();
        assert_eq!(out, "3.0,1.3,9.0");
    }
}
