    // FPS 监测状态机。
    //
    // 一个 FpsMonitor 只监测一个包名：
    // - start 时先找目标 PID 并启动 eBPF。
    // - poll 时读取 eBPF 帧事件并输出 FPS。
    // - eBPF 连续失败时切 SurfaceFlinger fallback。
    // - 曾经有帧但停了，会输出 0 并尝试重新锁定目标 PID。
    //
    // 这里不要直接读 App UI 状态。FPS 模块只对 fps.cmd 负责，上层悬浮窗关闭时会写 stop。
    impl FpsMonitor {
        fn start(pkg: String, socket_name: Option<String>, socket_token: Option<String>) -> Self {
            // 和 C 版 FPS 逻辑保持一致：游戏刚被拉起时 /proc/cmdline 可能短暂不可见，
            // 先等一小段时间，并优先锁定 top-app/foreground_window 里的目标 PID。
            // 这能避免直接锁主进程，导致 Unity/游戏渲染进程的帧被漏计。
            let initial_choice = wait_pkg_pid(&pkg, 30, Duration::from_millis(100));
            let initial_pid = initial_choice.as_ref().map_or(-1, |choice| choice.pid);
            let initial_source = initial_choice
                .as_ref()
                .map_or("未找到", |choice| choice.source.as_str());
            println!(
                "[FPS] eBPF 启动准备: pkg={} 初始PID={} 来源={} bpf={} 输出={}",
                pkg,
                initial_pid,
                initial_source,
                FPS_BPF_OBJ,
                if socket_name.is_some() {
                    "socket"
                } else {
                    FPS_OUT_FILE
                }
            );
            if initial_pid < 0 {
                println!(
                    "[FPS] 等待约 3 秒仍未找到 {} 的进程, 暂不启动全局 eBPF 探测, 后续拿到真实 PID 再重试",
                    pkg
                );
            }
            let ctx = if initial_pid > 0 {
                start_ebpf_for_pkg(&pkg, initial_pid, initial_source)
            } else {
                ptr::null_mut()
            };
            let now = Instant::now();

            Self {
                pkg,
                ctx,
                ebpf_failures: 0,
                ebpf_seen_frames: false,
                ebpf_stale_zero_sent: false,
                ebpf_last_frame: now,
                ebpf_last_pid_check: None,
                ebpf_last_restart: now,
                ebpf_pid_reported: false,
                ebpf_first_fps: true,
                ebpf_stale_checks: 0,
                ebpf_alternate_pid_attempted: false,
                fallback: None,
                socket: FpsSocket::new(socket_name, socket_token),
                last_output: None,
            }
        }

        fn poll(&mut self) {
            if self.ctx.is_null() {
                let now = Instant::now();
                if now.duration_since(self.ebpf_last_restart) >= FPS_EBPF_RESTART_COOLDOWN {
                    if let Some(choice) = find_preferred_pkg_pid(&self.pkg, true) {
                        println!(
                            "[FPS] 找到目标进程, 从 fallback 切回 eBPF: pkg={} pid={} 来源={}",
                            self.pkg, choice.pid, choice.source
                        );
                        self.ebpf_alternate_pid_attempted = false;
                        self.restart_ebpf(choice.pid, &choice.source, now);
                        if !self.ctx.is_null() {
                            thread::sleep(Duration::from_millis(80));
                            return;
                        }
                    }
                }
                self.poll_fallback();
                return;
            }
            // appopt_ebpf_poll 会从 RingBuf/PerfEvent 读取帧事件并更新 ctx 当前 FPS。
            let rc = appopt_ebpf_poll(self.ctx);
            if rc < 0 {
                self.ebpf_failures += 1;
                eprintln!(
                    "[FPS] eBPF 轮询失败 #{}: pkg={} pid={} err={}",
                    self.ebpf_failures,
                    self.pkg,
                    appopt_ebpf_pid(self.ctx),
                    cstr_lossy(appopt_ebpf_last_error(self.ctx))
                );
                if self.ebpf_failures >= 3 {
                    eprintln!(
                        "[FPS] eBPF 连续失败达到阈值, 切换 fallback: pkg={} failures={}",
                        self.pkg, self.ebpf_failures
                    );
                    appopt_ebpf_stop(self.ctx);
                    self.ctx = std::ptr::null_mut();
                    self.start_fallback();
                }
                thread::sleep(Duration::from_millis(120));
                return;
            }
            self.ebpf_failures = 0;

            let now = Instant::now();
            if rc > 0 {
                // rc > 0 表示本轮接受到了目标帧，刷新停帧计时。
                self.ebpf_seen_frames = true;
                self.ebpf_stale_zero_sent = false;
                self.ebpf_last_frame = now;
                self.ebpf_stale_checks = 0;
            }
            let fps = appopt_ebpf_get(self.ctx);
            let active_pid = appopt_ebpf_pid(self.ctx);
            if !self.ebpf_pid_reported && active_pid > 0 {
                println!("[FPS] eBPF 当前帧事件 PID: {active_pid}");
                self.ebpf_pid_reported = true;
            }
            if self.ebpf_first_fps && fps > 0.0 {
                println!("[FPS] eBPF 首次捕获到帧率: {fps:.1} fps");
                self.ebpf_first_fps = false;
            }

            // 目标长时间没有新帧时输出 0，避免悬浮窗沿用旧 FPS 误导用户。
            // 这不是 eBPF 失败，只是当前目标 Surface 没继续提交新帧。
            let fps_is_stale =
                self.ebpf_seen_frames && now.duration_since(self.ebpf_last_frame) >= FPS_EBPF_STALE;
            let fps_has_no_valid_value = !self.ebpf_seen_frames
                && now.duration_since(self.ebpf_last_restart) >= FPS_EBPF_STALE;

            // 目标仍有帧也要偶尔核对前台 cgroup PID。部分游戏启动后主进程仍会提交少量 UI 帧，
            // 但真实 90/120Hz 渲染已经切到另一个目标进程；只靠“停帧后重锁”会漏掉这种情况。
            if !fps_is_stale
                && !fps_has_no_valid_value
                && now.duration_since(self.ebpf_last_restart) >= FPS_EBPF_RESTART_COOLDOWN
                && self
                    .ebpf_last_pid_check
                    .map(|last| now.duration_since(last) >= FPS_EBPF_PID_CHECK)
                    .unwrap_or(true)
            {
                self.ebpf_last_pid_check = Some(now);
                if let Some(choice) = find_foreground_pkg_pid(&self.pkg) {
                    if choice.pid > 0 && choice.pid != active_pid {
                        println!(
                            "[FPS] 前台目标进程已切换: pkg={} old={} new={} 来源={}, 重启 eBPF 监测",
                            self.pkg, active_pid, choice.pid, choice.source
                        );
                        self.ebpf_alternate_pid_attempted = false;
                        self.restart_ebpf(choice.pid, &choice.source, now);
                        thread::sleep(Duration::from_millis(120));
                        return;
                    }
                }
            }

            if fps_is_stale && !self.ebpf_stale_zero_sent {
                println!(
                    "[FPS] eBPF 目标暂无新帧 {:.1} 秒, 输出 0 FPS",
                    now.duration_since(self.ebpf_last_frame).as_secs_f64()
                );
                self.write_fps(0.0);
                self.last_output = Some(now);
                self.ebpf_stale_zero_sent = true;
            }

            // QQ/短视频等应用可能切换实际渲染进程；停帧后再查 PID 并重启 eBPF。
            // 例如启动时命中壳进程，真正播放/渲染后来换到另一个同包进程。
            if (fps_is_stale || fps_has_no_valid_value)
                && self
                    .ebpf_last_pid_check
                    .map(|last| now.duration_since(last) >= FPS_EBPF_PID_CHECK)
                    .unwrap_or(true)
                && now.duration_since(self.ebpf_last_restart) >= FPS_EBPF_RESTART_COOLDOWN
            {
                self.ebpf_last_pid_check = Some(now);
                match find_preferred_pkg_pid(&self.pkg, true) {
                    Some(choice) if choice.pid > 0 && choice.pid != active_pid => {
                        println!(
                            "[FPS] 目标进程已切换: pkg={} old={} new={} 来源={} stale={:.1}s, 重启 eBPF 监测",
                            self.pkg,
                            active_pid,
                            choice.pid,
                            choice.source,
                            now.duration_since(self.ebpf_last_frame).as_secs_f64()
                        );
                        self.ebpf_alternate_pid_attempted = false;
                        self.restart_ebpf(choice.pid, &choice.source, now);
                        thread::sleep(Duration::from_millis(120));
                        return;
                    }
                    Some(choice) => {
                        self.ebpf_stale_checks = self.ebpf_stale_checks.saturating_add(1);
                        println!(
                            "[FPS] eBPF PID检查: pkg={} 当前PID={} 检测PID={} 来源={} 未切换, 等待新帧 {}/{}",
                            self.pkg,
                            active_pid,
                            choice.pid,
                            choice.source,
                            self.ebpf_stale_checks,
                            FPS_EBPF_STALE_FALLBACK_CHECKS
                        );
                    }
                    None => {
                        self.ebpf_stale_checks = self.ebpf_stale_checks.saturating_add(1);
                        println!(
                            "[FPS] eBPF PID检查: pkg={} 未找到目标进程, 等待新帧 {}/{}",
                            self.pkg, self.ebpf_stale_checks, FPS_EBPF_STALE_FALLBACK_CHECKS
                        );
                    }
                }
                if self.ebpf_stale_checks >= FPS_EBPF_STALE_FALLBACK_CHECKS {
                    if !self.ebpf_alternate_pid_attempted && active_pid > 0 {
                        self.ebpf_alternate_pid_attempted = true;
                        if let Some(choice) = find_alternate_pkg_pid(&self.pkg, active_pid) {
                            println!(
                                "[FPS] eBPF 固定 PID 连续无目标新帧, 尝试同包其它进程: pkg={} old_pid={} new_pid={} 来源={}",
                                self.pkg, active_pid, choice.pid, choice.source
                            );
                            self.restart_ebpf(choice.pid, &choice.source, now);
                            thread::sleep(Duration::from_millis(300));
                            return;
                        }
                        println!(
                            "[FPS] eBPF 固定 PID 连续无目标新帧, 未找到同包其它进程: pkg={} pid={}",
                            self.pkg, active_pid
                        );
                    }
                    if self.ebpf_seen_frames && active_pid > 0 && process_exists(active_pid) {
                        println!(
                            "[FPS] eBPF 已捕获过目标帧但暂时停帧, 目标进程仍存在, 继续保持 eBPF 等待: pkg={} pid={}",
                            self.pkg, active_pid
                        );
                        self.ebpf_stale_checks = 0;
                        thread::sleep(Duration::from_millis(300));
                        return;
                    }
                    println!(
                        "[FPS] eBPF 连续无目标新帧且 PID 未切换, 切换到 SurfaceFlinger fallback: pkg={} pid={}",
                        self.pkg, active_pid
                    );
                    appopt_ebpf_stop(self.ctx);
                    self.ctx = std::ptr::null_mut();
                    self.start_fallback();
                    thread::sleep(Duration::from_millis(300));
                    return;
                }
            }

            let should_output = self
                .last_output
                .map(|last| now.duration_since(last) >= FPS_WINDOW)
                .unwrap_or(true);
            if should_output && !fps_is_stale {
                self.write_fps(fps);
                self.last_output = Some(now);
            }
            thread::sleep(Duration::from_millis(80));
        }

        fn start_fallback(&mut self) {
            if self.fallback.is_none() {
                // SurfaceFlinger 不依赖 eBPF，统计的是最终呈现图层的帧时间戳。
                println!("[FPS] 启用 SurfaceFlinger FPS 源: {}", self.pkg);
                self.fallback = SfFallback::new(self.pkg.clone());
            }
        }

        fn poll_fallback(&mut self) {
            self.start_fallback();
            let fps = self
                .fallback
                .as_mut()
                .map(|fallback| fallback.poll())
                .unwrap_or(0.0);
            let now = Instant::now();
            let should_output = self
                .last_output
                .map(|last| now.duration_since(last) >= FPS_WINDOW)
                .unwrap_or(true);
            if should_output {
                self.write_fps(fps);
                self.last_output = Some(now);
            }
            thread::sleep(Duration::from_millis(300));
        }

        fn restart_ebpf(&mut self, pid: i32, source: &str, now: Instant) {
            appopt_ebpf_stop(self.ctx);
            self.ctx = start_ebpf_for_pkg(&self.pkg, pid, source);
            self.ebpf_failures = 0;
            self.ebpf_seen_frames = false;
            self.ebpf_stale_zero_sent = false;
            self.ebpf_last_frame = now;
            self.ebpf_last_restart = now;
            self.ebpf_pid_reported = false;
            self.ebpf_first_fps = true;
            self.ebpf_stale_checks = 0;
            if self.ctx.is_null() {
                self.start_fallback();
            } else {
                self.fallback = None;
            }
        }

        fn stop(&mut self) {
            if !self.ctx.is_null() {
                appopt_ebpf_stop(self.ctx);
                self.ctx = std::ptr::null_mut();
            }
            self.write_fps(0.0);
            self.fallback = None;
            self.socket.close();
            println!("[FPS] 停止监测 {}", self.pkg);
        }

        fn write_fps(&mut self, fps: f64) {
            if self.socket.send_fps(fps).is_ok() {
                return;
            }
            write_fps_file(fps);
        }
    }

    fn process_exists(pid: i32) -> bool {
        pid > 0 && fs::metadata(format!("/proc/{pid}")).is_ok()
    }

    fn start_ebpf_for_pkg(pkg: &str, pid: i32, source: &str) -> *mut AppOptEbpfCtx {
        // 这里已经不走 C 版 FPS 逻辑，Rust 守护进程直接加载 bpf.o 并附加 libgui uprobe。
        // bridge 内部会优先尝试 RingBuf；RingBuf mmap 不可用时自动加载 PerfEvent 备用对象。
        let ctx = match (CString::new(FPS_BPF_OBJ), CString::new(pkg)) {
            (Ok(bpf_path), Ok(c_pkg)) => {
                appopt_ebpf_start_for_package(pid, bpf_path.as_ptr(), c_pkg.as_ptr())
            }
            _ => {
                eprintln!("[FPS] eBPF 启动跳过: 参数包含无效字符");
                ptr::null_mut()
            }
        };
        if ctx.is_null() {
            eprintln!(
                "[FPS] eBPF 启动失败: {}",
                cstr_lossy(appopt_ebpf_last_start_error())
            );
        } else {
            println!(
                "[FPS] eBPF 已激活: pkg={} pid={} 来源={} 后端={} 符号={} 备注={}",
                pkg,
                appopt_ebpf_pid(ctx),
                source,
                cstr_lossy(appopt_ebpf_backend(ctx)),
                cstr_lossy(appopt_ebpf_symbol(ctx)),
                cstr_lossy(appopt_ebpf_startup_note(ctx))
            );
        }
        ctx
    }

    impl Drop for FpsMonitor {
        fn drop(&mut self) {
            if !self.ctx.is_null() {
                appopt_ebpf_stop(self.ctx);
                self.ctx = std::ptr::null_mut();
            }
            self.fallback = None;
            self.socket.close();
        }
    }
