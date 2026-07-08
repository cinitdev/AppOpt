// 校准历史写入。
//
// history/<pkg>.log 是 App 历史记录页面的数据源，不只是调试日志。
// 每次校准写一个 session：
// - 第一行：# <epoch> <rounds>
// - 后续行：avg max name|series[,series...]|child-thread-detail
//
// 子进程线程明细跟在子进程整体负载后面，方便 App 展开查看“哪个线程贡献了子进程负载”，
// 但生成规则仍只看子进程整体负载。
fn write_history(
    pkg: &str,
    rounds: usize,
    records: &[LoadRecord],
    child_threads: &HashMap<ChildThreadKey, ChildThreadSummary>,
) -> io::Result<()> {
    fs::create_dir_all(HISTORY_DIR)?;
    let path = PathBuf::from(HISTORY_DIR).join(format!("{}.log", safe_file_name(pkg)));
    let epoch = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let mut current = String::new();
    writeln!(&mut current, "# {epoch} {rounds}").map_err(fmt_to_io)?;
    for record in records {
        let name = if record.is_process {
            &record.owner
        } else {
            &record.name
        };
        let series = record
            .samples
            .iter()
            .map(|value| format!("{value:.1}"))
            .collect::<Vec<_>>()
            .join(",");
        if series.is_empty() {
            continue;
        }
        let details = if record.is_process {
            child_thread_details(&record.owner, child_threads)
        } else {
            String::new()
        };
        if details.is_empty() {
            writeln!(
                &mut current,
                "{:.1} {:.1} {}|{}",
                record.avg(),
                record.max_pct,
                safe_history_name(name),
                series
            )
            .map_err(fmt_to_io)?;
        } else {
            writeln!(
                &mut current,
                "{:.1} {:.1} {}|{}|{}",
                record.avg(),
                record.max_pct,
                safe_history_name(name),
                series,
                details
            )
            .map_err(fmt_to_io)?;
        }
    }

    if current.lines().count() <= 1 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "history has no load rows",
        ));
    }

    // 每个包只保留最近几次历史，避免长期校准后 history 目录无限增长。
    let old = fs::read_to_string(&path).unwrap_or_default();
    let mut next = keep_recent_history(&old, HISTORY_MAX_SESSIONS.saturating_sub(1));
    if !next.is_empty() && !next.ends_with('\n') {
        next.push('\n');
    }
    next.push_str(&current);

    let tmp = path.with_extension("log.rust.tmp");
    fs::write(&tmp, next)?;
    fs::rename(tmp, path)
}

fn keep_recent_history(old: &str, max_sessions: usize) -> String {
    if old.trim().is_empty() || max_sessions == 0 {
        return String::new();
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
        return old.to_string();
    }
    let keep_from = starts[starts.len() - max_sessions];
    old[keep_from..].to_string()
}

fn fmt_to_io(_: std::fmt::Error) -> io::Error {
    io::Error::new(io::ErrorKind::Other, "format history failed")
}

fn child_thread_details(
    owner: &str,
    child_threads: &HashMap<ChildThreadKey, ChildThreadSummary>,
) -> String {
    let mut rows = child_threads
        .values()
        .filter(|summary| summary.owner == owner)
        .filter(|summary| summary.max_pct >= 0.05 || summary.avg() >= 0.05)
        .collect::<Vec<_>>();
    if rows.is_empty() {
        return String::new();
    }
    rows.sort_by(|a, b| {
        b.avg()
            .partial_cmp(&a.avg())
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                b.max_pct
                    .partial_cmp(&a.max_pct)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
    });
    let body = rows
        .into_iter()
        .map(|summary| {
            format!(
                "{},{:.2},{:.2}",
                safe_history_name(&summary.name),
                summary.avg(),
                summary.max_pct
            )
        })
        .collect::<Vec<_>>()
        .join(";");
    format!("v2:{body}")
}
