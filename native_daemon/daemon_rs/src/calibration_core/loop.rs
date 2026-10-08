use super::*;
use crate::command_protocol::{CalibrationCommand, CommandFile};

// 校准后台线程入口。
//
// App 通过写 config/calibrate.cmd 控制校准，不和 daemon 建立长连接：
// - start <pkg>：开始采样某个应用。
// - stop / stop <pkg>：停止当前采样并生成规则。
//
// 这么设计是为了兼容 App 被系统回收、Activity 重建、悬浮窗关闭等场景。daemon 只认命令文件
// 和状态文件，不依赖 App 进程一直活着。
pub fn start_calibration_thread() -> Option<thread::JoinHandle<()>> {
    match thread::Builder::new()
        .name("QiXiaRsCalibration".to_string())
        .spawn(move || {
        if let Err(err) = calibration_loop() {
            log_error!("[CALIB] 校准线程已停止: {err}");
        }
        }) {
        Ok(handle) => Some(handle),
        Err(err) => {
            log_error!("[CALIB] 校准线程创建失败: {err}");
            None
        }
    }
}

pub(super) fn calibration_loop() -> io::Result<()> {
    if let Err(err) = fs::create_dir_all(CONFIG_DIR) {
        log_error!("[CALIB] 初始化配置目录失败，将在后续命令中重试: {err}");
    }
    // 应用同时验证功能能力和当前运行的守护进程 PID。
    fs::write(DRAFT_CAPABILITY, format!("2 {}\n",std::process::id()))?;
    // 正常退出的前一守护进程可能刚完成会话；在应用读取回执，
    // 或新命令替换回执前，保留其完成确认。
    let previous = fs::read_to_string(CALIB_STATE_FILE).unwrap_or_default();
    if !completed_state(&previous) {
        if let Err(err) = write_state("idle") {
            log_error!("[CALIB] 初始状态写入失败，校准线程继续运行: {err}");
        }
    }

    let mut session: Option<CalibSession> = None;
    let mut commands = CommandFile::new(CALIB_CMD_FILE);
    let mut command_monitor =
        crate::CommandFileMonitor::new(&[CALIB_CMD_FILE, commands.claimed_path().to_str().expect("static command path")])
            .ok();
    let mut last_command_error_log: Option<Instant> = None;
    let mut last_progress_log_round: Option<usize> = None;
    while !crate::shutdown_requested() {
        // App 通过写 calibrate.cmd 控制开始/停止。
        // daemon 侧不直接和 Activity 通信，避免 App 被杀时校准线程状态丢失。
        let command = match commands.read(CalibrationCommand::parse) {
            Ok(command) => {
                last_command_error_log = None;
                command
            }
            Err(err) => {
                let now = Instant::now();
                if last_command_error_log
                    .is_none_or(|last| now.duration_since(last) >= Duration::from_secs(10))
                {
                    log_error!("[CALIB] 校准命令读取失败，将继续重试: {err}");
                    last_command_error_log = Some(now);
                }
                None
            }
        };
        if let Some(cmd) = command {
            match cmd {
                CalibrationCommand::Start(pkg) => {
                    if let Some(active) = session.as_ref() {
                        if active.pkg == pkg {
                            log_info!("[CALIB] 已在采样，忽略重复开始命令: {pkg}");
                            if let Err(err) = write_state(&format!("sampling {pkg}")) {
                                log_error!("[CALIB] 重复开始命令确认状态写入失败: {err}");
                            }
                        } else {
                            log_info!(
                                "[CALIB] 忽略开始命令: requested={} active={} reason=busy",
                                pkg, active.pkg
                            );
                            if let Err(err) = write_state(&format!(
                                "sampling {};reason=busy;requested={pkg}", active.pkg
                            )) {
                                log_error!("[CALIB] 忙碌状态写入失败: {err}");
                            }
                        }
                    } else {
                        let storage = match crate::private_storage::resolve() {
                            Ok(storage) => storage,
                            Err(error) => {
                            log_warn!("[CALIB] App 私有存储暂不可用，请解锁并打开 App: {error}");
                            if let Err(error) = write_state(&format!("rejected {pkg};reason=storage_unavailable")) {
                                log_error!("[CALIB] 存储不可用状态写入失败: {error}");
                            }
                            continue;
                            }
                        };
                        let processes = collect_pkg_processes(&pkg);
                        if processes.is_empty() {
                            log_info!("[CALIB] 忽略开始命令: {pkg} 没有运行中的进程");
                            if let Err(err) =
                                write_state(&format!("rejected {pkg};reason=no_process"))
                            {
                                log_error!("[CALIB] 拒绝状态写入失败: {err}");
                            }
                        } else {
                            log_info!(
                                "[CALIB] 开始采样: pkg={} 进程数={} 进程=[{}]",
                                pkg,
                                processes.len(),
                                process_preview(&processes, 8)
                            );
                            match write_state(&format!("sampling {pkg}")) {
                                Ok(()) => {
                                    let mut started = CalibSession::new(pkg.clone(), processes);
                                    started.storage = Some(storage);
                                    session = Some(started);
                                    last_progress_log_round = None;
                                }
                                Err(err) => {
                                    log_error!(
                                        "[CALIB] 采样状态写入失败，忽略本次开始命令: {err}"
                                    );
                                }
                            }
                        }
                    }
                }
                CalibrationCommand::Stop(requested) => {
                    let matches_active = session.as_ref().is_some_and(|active| {
                        requested.as_deref().is_none_or(|pkg| pkg == active.pkg)
                    });
                    if matches_active {
                        if let Some(done) = session.take() {
                            if let Err(err) = finish_session(done) {
                                log_error!("[CALIB] 校准收尾失败，后台线程将继续运行: {err}");
                            }
                        }
                    } else if let Some(active) = session.as_ref() {
                        let requested = requested.as_deref().unwrap_or_default();
                        log_info!(
                            "[CALIB] 忽略不属于当前会话的停止命令: requested={} active={}",
                            requested, active.pkg
                        );
                        if let Err(err) = write_state(&format!(
                            "sampling {};reason=stop_mismatch;requested={requested}", active.pkg
                        )) {
                            log_error!("[CALIB] 停止命令不匹配状态写入失败: {err}");
                        }
                    } else if let Some(pkg) = requested {
                        // App 可能在 daemon 已自动收尾后补发 stop。给出明确确认，避免无意义等待。
                        let previous = fs::read_to_string(CALIB_STATE_FILE).unwrap_or_default();
                        let completed = previous.trim().starts_with(&format!("done {pkg};"));
                        if !completed {
                            if let Err(err) = write_state(&format!("done {pkg};reason=no_session")) {
                                log_error!("[CALIB] 空会话停止确认写入失败: {err}");
                            }
                        }
                    } else if let Err(err) = write_state("idle") {
                        log_error!("[CALIB] 空会话停止状态写入失败: {err}");
                    }
                }
            }
        }

        let mut should_finish = false;
        let mut session_timed_out = false;
        if let Some(active) = session.as_mut() {
            if active.started_at.elapsed() >= CALIB_MAX_SESSION_DURATION {
                should_finish = true;
                session_timed_out = true;
            } else if !active.sample_once() {
                should_finish = true;
            } else if active.rounds > 0
                && active.rounds % CALIB_PROGRESS_LOG_ROUNDS == 0
                && last_progress_log_round != Some(active.rounds)
            {
                last_progress_log_round = Some(active.rounds);
                log_info!(
                    "[CALIB] 采样中: pkg={} 轮次={} 活跃进程={} 负载项={} 跟踪TID={} 子进程线程摘要={} Top=[{}]",
                    active.pkg,
                    active.rounds,
                    active.processes.len(),
                    active.records.len(),
                    active.prev_ticks.len(),
                    active.child_threads.len(),
                    top_record_summary(active.records.values(), 5)
                );
            }
        }
        if should_finish {
            last_progress_log_round = None;
            if let Some(done) = session.take() {
                if session_timed_out {
                    log_info!("[CALIB] 校准会话已达到 6 小时上限: {}", done.pkg);
                } else {
                    log_info!("[CALIB] 主进程已退出: {}", done.pkg);
                }
                if let Err(err) = finish_session(done) {
                    log_error!("[CALIB] 校准收尾失败，后台线程将继续运行: {err}");
                }
            }
        }

        if session.is_some() {
            thread::sleep(SAMPLE_INTERVAL);
        } else {
            // 空闲时不使用 500 毫秒定时轮询，由 inotify 在开始或停止命令到达时
            // 立即唤醒；30 秒间隔仅用于防止事件遗漏的兜底检查，
            // 不支持事件通知的特殊文件系统则使用 2 秒兜底轮询。
            crate::wait_for_command_files(
                &mut command_monitor,
                commands.wait_timeout(Duration::from_secs(30)),
                Duration::from_secs(2),
            );
        }
    }
    finish_on_shutdown(&mut session, |session| finish_interrupted_session(session))
}

fn completed_state(value: &str) -> bool {
    let Some((package, _)) = value.trim().strip_prefix("done ").and_then(|v| v.split_once(';')) else {
        return false;
    };
    matches!(CalibrationCommand::parse(&format!("start {package}")), Some(CalibrationCommand::Start(_)))
}

fn finish_on_shutdown(
    session: &mut Option<CalibSession>,
    finish: impl FnOnce(CalibSession) -> io::Result<()>,
) -> io::Result<()> {
    if let Some(done) = session.take() {
        log_info!("[CALIB] 守护正常停止，保存当前采集: {}", done.pkg);
        finish(done)?;
    }
    Ok(())
}

#[cfg(test)]
mod shutdown_tests {
    use super::*;

    #[test]
    fn shutdown_transfers_all_samples_to_finalization_exactly_once() {
        let mut collected = CalibSession::new("com.example.game".into(), Vec::new());
        let key = TrackKey { owner: collected.pkg.clone(), name: "RenderThread".into(), is_process: false };
        for round in 0..80 {
            collected.rounds = round;
            collected.record_pct(key.clone(), 25.0);
            collected.fill_missing_record_samples();
        }
        collected.rounds = 80;
        collected.active_duration = Duration::from_secs(40);
        let mut session = Some(collected);
        let mut output = None;
        finish_on_shutdown(&mut session, |done| {
            assert_eq!(done.pkg, "com.example.game");
            assert_eq!(done.sampled_duration(), Duration::from_secs(40));
            assert_eq!(done.rounds, 80);
            let records: Vec<_> = done.records.into_values().collect();
            assert_eq!(records[0].sample_count, 80);
            assert_eq!(records[0].sum_pct, 2_000.0);
            let rows = select_history_records(&records);
            output = format_history(1_700_000_000, 80, done.rounds, &rows, &done.child_threads)?;
            Ok(())
        }).unwrap();
        let (history, rows) = output.unwrap();
        assert_eq!(rows, 1);
        assert!(history.starts_with("# 1700000000 80\n"));
        assert!(history.contains("RenderThread"));
        assert!(session.is_none());
        finish_on_shutdown(&mut session, |_| panic!("completed sessions must not be published twice")).unwrap();
    }

    #[test]
    fn finalization_errors_are_reported_and_completed_ack_survives_restart() {
        let mut session = Some(CalibSession::new("com.example.game".into(), Vec::new()));
        let error = finish_on_shutdown(&mut session,
            |_| Err(io::Error::new(io::ErrorKind::WriteZero, "failed final write"))).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::WriteZero);
        for state in ["done com.example.game;reason=review;draft=123-4", "done com.example.game;reason=short"] {
            assert!(completed_state(state), "{state}");
        }
        for state in ["idle", "sampling com.example.game", "done ;reason=review", "done /invalid;reason=review"] {
            assert!(!completed_state(state), "{state}");
        }
    }
}
