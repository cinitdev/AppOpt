// 校准后台线程入口。
//
// App 通过写 config/calibrate.cmd 控制校准，不和 daemon 建立长连接：
// - start <pkg>：开始采样某个应用。
// - stop / stop <pkg>：停止当前采样并生成规则。
//
// 这么设计是为了兼容 App 被系统回收、Activity 重建、悬浮窗关闭等场景。daemon 只认命令文件
// 和状态文件，不依赖 App 进程一直活着。
pub fn start_calibration_thread(config_file: PathBuf) {
    thread::spawn(move || {
        if let Err(err) = calibration_loop(config_file) {
            eprintln!("[CALIB] 校准线程已停止: {err}");
        }
    });
}

fn calibration_loop(config_file: PathBuf) -> io::Result<()> {
    fs::create_dir_all(CONFIG_DIR)?;
    fs::create_dir_all(HISTORY_DIR)?;
    write_state("idle")?;
    let startup_topo = CpuTiers::detect();
    log_detected_topology(&startup_topo);
    sync_policy_topology(&startup_topo);

    let mut session: Option<CalibSession> = None;
    loop {
        // App 通过写 calibrate.cmd 控制开始/停止。
        // daemon 侧不直接和 Activity 通信，避免 App 被杀时校准线程状态丢失。
        if let Some(cmd) = read_command()? {
            if let Some(pkg) = cmd.strip_prefix("start ").map(str::trim) {
                if !pkg.is_empty() {
                    let processes = collect_pkg_processes(pkg);
                    if processes.is_empty() {
                        println!("[CALIB] 忽略开始命令: {pkg} 没有运行中的进程");
                    } else {
                        println!(
                            "[CALIB] 开始采样: pkg={} 进程数={} 进程=[{}]",
                            pkg,
                            processes.len(),
                            process_preview(&processes, 8)
                        );
                        write_state(&format!("sampling {pkg}"))?;
                        session = Some(CalibSession::new(pkg.to_string(), processes));
                    }
                }
            } else if cmd == "stop" || cmd.starts_with("stop ") {
                if let Some(done) = session.take() {
                    finish_session(done, &config_file)?;
                }
            }
        }

        let mut should_finish = false;
        if let Some(active) = session.as_mut() {
            if !active.sample_once() {
                should_finish = true;
            } else if active.rounds % 20 == 0 {
                println!(
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
            if let Some(done) = session.take() {
                println!("[CALIB] 目标进程已退出: {}", done.pkg);
                finish_session(done, &config_file)?;
            }
        }

        thread::sleep(SAMPLE_INTERVAL);
    }
}
