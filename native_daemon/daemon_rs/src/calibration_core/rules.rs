use super::*;
pub(super) fn finish_session(session: CalibSession) -> io::Result<()> {
    finish_session_inner(session, false)
}

pub(super) fn finish_interrupted_session(session: CalibSession) -> io::Result<()> {
    finish_session_inner(session, true)
}

fn finish_session_inner(session: CalibSession, interrupted: bool) -> io::Result<()> {
    let sampled_duration = session.sampled_duration();
    let CalibSession {
        pkg,
        storage,
        analyzer,
        records: session_records,
        child_threads,
        rounds,
        ..
    } = session;
    // 用真实的主进程活跃时间判断，不把 /proc 扫描耗时错误地当成固定 500ms 轮次。
    // 命令可能落在两个 500ms 采样点之间，允许一个采样间隔的边界误差。
    let too_short = sampled_duration.saturating_add(SAMPLE_INTERVAL) < CALIB_MIN_DURATION;
    if too_short && !interrupted {
        log_info!(
            "[CALIB] 采样时长不足: pkg={} 有效时长={:.1}秒 轮次={} 最少需要={:.0}秒",
            pkg,
            sampled_duration.as_secs_f64(),
            rounds,
            CALIB_MIN_DURATION.as_secs_f64()
        );
        write_state(&format!("done {pkg};reason=short"))?;
        return Ok(());
    }

    let mut records: Vec<LoadRecord> = session_records
        .into_values()
        .filter(|record| record.sample_count > 0)
        .collect();
    records.sort_by(|a, b| {
        b.avg()
            .partial_cmp(&a.avg())
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    if records.is_empty() {
        log_info!("[CALIB] 未检测到明显负载: pkg={pkg}");
        write_state(&format!("done {pkg};reason=no_load"))?;
        return Ok(());
    }

    // 历史保留活跃线程，不受核心建议的平均负载 5% 门槛影响。
    let history_records = select_history_records(&records);

    // 历史记录优先落盘；即使规则生成或写回失败，App 仍可导入这次采样数据辅助排查。
    let history_rounds = ((sampled_duration.as_secs_f64() * 2.0).round() as usize).max(1);
    let storage = storage.as_ref().ok_or_else(|| io::Error::other("calibration storage is unavailable"))?;
    match write_history(
        &pkg,
        history_rounds,
        rounds,
        &history_records,
        &child_threads,
        Some(storage),
    ) {
        Err(err) => log_error!("[CALIB] 历史记录写入失败: pkg={pkg} err={err}"),
        Ok(0) => log_info!("[CALIB] 无持续活跃线程，跳过空历史: pkg={pkg}"),
        Ok(written_rows) => log_info!(
            "[CALIB] 历史记录已写入: pkg={} 时长={:.1}秒 轮次={} 负载项={} 候选总数={} 子进程线程摘要={} Top=[{}]",
            pkg,
            sampled_duration.as_secs_f64(),
            rounds,
            written_rows,
            records.len(),
            child_threads.len(),
            top_record_summary(records.iter(), 8)
        ),
    }
    if too_short {
        // 设置变更触发的重启不能丢弃已采集数据，但短暂采集被打断时，
        // 已有样本仍可能不足以支持生成核心规则。
        write_state(&format!("done {pkg};reason=short"))?;
        return Ok(());
    }
    if !records.iter().any(|record| record.sum_pct > 0.0) {
        log_info!("[CALIB] 未生成规则: pkg={pkg} reason=no_load");
        write_state(&format!("done {pkg};reason=no_load"))?;
        return Ok(());
    }
    match write_calibration_draft(&pkg, sampled_duration, rounds, &records, &child_threads, &analyzer, storage) {
        Ok(Some(id)) => write_state(&format!("done {pkg};reason=review;draft={id}"))?,
        Ok(None) => write_state(&format!("done {pkg};reason=no_load"))?,
        Err(err) => {
            log_error!("[CALIB] 待确认结果保存失败: pkg={pkg} err={err}");
            write_state(&format!("done {pkg};reason=draft_fail"))?;
        }
    }
    Ok(())
}

pub(super) fn process_preview(processes: &[ProcInfo], limit: usize) -> String {
    if processes.is_empty() {
        return "-".to_string();
    }
    let mut rows = processes
        .iter()
        .take(limit)
        .map(|proc_info| format!("{}:{}", proc_info.owner, proc_info.pid))
        .collect::<Vec<_>>()
        .join(", ");
    if processes.len() > limit {
        rows.push_str(&format!(" ... +{}", processes.len() - limit));
    }
    rows
}

pub(super) fn top_record_summary<'a>(
    records: impl IntoIterator<Item = &'a LoadRecord>,
    limit: usize,
) -> String {
    let mut rows = records.into_iter().collect::<Vec<_>>();
    if rows.is_empty() {
        return "-".to_string();
    }
    rows.sort_by(|a, b| {
        load_score(b)
            .partial_cmp(&load_score(a))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut out = rows
        .iter()
        .take(limit)
        .map(|record| {
            let name = if record.is_process {
                record.owner.as_str()
            } else {
                record.name.as_str()
            };
            format!("{name} avg={:.1}% max={:.1}%", record.avg(), record.max_pct)
        })
        .collect::<Vec<_>>()
        .join("; ");
    if rows.len() > limit {
        out.push_str(&format!(" ... +{}", rows.len() - limit));
    }
    out
}

pub(super) fn load_score(record: &LoadRecord) -> f64 { record.avg() * 0.65 + record.max_pct * 0.35 }
