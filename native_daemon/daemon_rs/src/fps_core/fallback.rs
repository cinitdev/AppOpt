    // SurfaceFlinger FPS fallback。
    //
    // eBPF 是逐帧最优路径，但部分内核/ROM 可能不支持或 libgui 符号变动。
    // fallback 通过 binder 直连 SurfaceFlinger dump：
    // - 优先 --latency <layer>，从图层帧时间戳计算 FPS。
    // - latency 连续失败后切 --timestats。
    //
    // 这条路径不是完美等价于 eBPF：它更接近 SurfaceFlinger 图层输出帧率，
    // 但胜在不依赖 BPF 能力，适合作为兼容兜底。
    struct SfFallback {
        pkg: String,
        // 优先直接 binder 调 SurfaceFlinger，避免 fork dumpsys。
        binder: Option<BinderSf>,
        // latency 模式锁定的图层名；失效后会清空并重新发现。
        main_layer: String,
        since: u64,
        miss: u32,
        probe_fail: u32,
        ts_mode: bool,
        ts_last: i64,
        ts_last_time: Option<Instant>,
    }

    impl SfFallback {
        fn new(pkg: String) -> Option<Self> {
            let binder = match BinderSf::new() {
                Ok(binder) => Some(binder),
                Err(err) => {
                    eprintln!("[Fallback] SurfaceFlinger binder 不可用: {err}");
                    None
                }
            };
            Some(Self {
                pkg,
                binder,
                main_layer: String::new(),
                since: 0,
                miss: 0,
                probe_fail: 0,
                ts_mode: false,
                ts_last: -1,
                ts_last_time: None,
            })
        }

        fn poll(&mut self) -> f64 {
            if self.ts_mode {
                return self.poll_timestats();
            }

            if self.main_layer.is_empty() {
                return self.discover_latency_layer();
            }

            let mut valid = 0usize;
            let mut latest = 0u64;
            let fps = self.layer_fps(&self.main_layer.clone(), &mut valid, &mut latest);
            if fps >= 0.0 {
                self.miss = 0;
                self.probe_fail = 0;
                return fps;
            }
            if valid > 0 && latency_ts_is_fresh(latest, now_monotonic_ns()) {
                self.miss = 0;
                self.probe_fail = 0;
                return 0.0;
            }
            self.miss += 1;
            if self.miss >= FPS_RELOCK_MISS {
                self.main_layer.clear();
                self.since = 0;
                self.miss = 0;
            }
            0.0
        }

        fn discover_latency_layer(&mut self) -> f64 {
            // 遍历 SurfaceFlinger 图层，挑目标包名相关且 latency 数据最新的图层。
            // 如果 latency 完全拿不到有效数据，多次失败后切 timestats。
            let candidates = self.list_candidates();
            let candidate_count = candidates.len();
            let candidate_preview = preview_layers(&candidates);
            let now_ns = now_monotonic_ns();
            let mut best_layer = String::new();
            let mut best_fps = -1.0f64;
            let mut best_since = 0u64;
            let mut valid_total = 0usize;

            for layer in candidates {
                let mut valid = 0usize;
                let mut latest = 0u64;
                let mut saved_since = self.since;
                let fps =
                    self.layer_fps_with_since(&layer, &mut saved_since, &mut valid, &mut latest);
                valid_total += valid;
                if !latency_ts_is_fresh(latest, now_ns) {
                    continue;
                }
                if fps > best_fps {
                    best_fps = fps;
                    best_layer = layer;
                    best_since = saved_since;
                }
            }

            if !best_layer.is_empty() && best_fps >= 0.0 {
                println!(
                    "[Fallback] 已锁定 SurfaceFlinger 图层: {} ({:.1} fps)",
                    best_layer, best_fps
                );
                self.main_layer = best_layer;
                self.since = best_since;
                self.miss = 0;
                self.probe_fail = 0;
                return best_fps;
            }

            if valid_total == 0 {
                self.probe_fail += 1;
                if candidate_count == 0 {
                    println!(
                        "[Fallback] SurfaceFlinger latency 未找到匹配图层: pkg={} 失败={}/{}",
                        self.pkg, self.probe_fail, FPS_PROBE_FAIL
                    );
                } else {
                    println!(
                        "[Fallback] SurfaceFlinger latency 候选图层无有效帧: pkg={} 候选={} [{}] 失败={}/{}",
                        self.pkg,
                        candidate_count,
                        candidate_preview,
                        self.probe_fail,
                        FPS_PROBE_FAIL
                    );
                }
                if self.probe_fail >= FPS_PROBE_FAIL {
                    self.enable_timestats();
                }
            } else {
                println!(
                    "[Fallback] SurfaceFlinger latency 有历史帧但不新鲜或不足: pkg={} 候选={} 有效帧={} [{}]",
                    self.pkg, candidate_count, valid_total, candidate_preview
                );
                self.probe_fail = 0;
            }
            0.0
        }

        fn list_candidates(&mut self) -> Vec<String> {
            let Some(out) = self.sf_dump(&["--list"]) else {
                return Vec::new();
            };
            let mut result = Vec::new();
            for raw in out.lines() {
                let mut line = raw.trim().trim_end_matches('\r').trim().to_string();
                if let Some((prefix, rest)) = line.split_once(':') {
                    if !prefix.is_empty() && prefix.bytes().all(|byte| byte.is_ascii_digit()) {
                        line = rest.trim().to_string();
                    }
                }
                line = unwrap_requested_layer_name(&line);
                if line.is_empty() || line.len() >= 256 {
                    continue;
                }
                if layer_matches_pkg(&line, &self.pkg) {
                    result.push(line);
                    if result.len() >= 16 {
                        break;
                    }
                }
            }
            result
        }

        fn layer_fps(&mut self, layer: &str, valid: &mut usize, latest: &mut u64) -> f64 {
            let mut since = self.since;
            let fps = self.layer_fps_with_since(layer, &mut since, valid, latest);
            if since > self.since {
                self.since = since;
            }
            fps
        }

        fn layer_fps_with_since(
            &mut self,
            layer: &str,
            since: &mut u64,
            valid_out: &mut usize,
            latest_out: &mut u64,
        ) -> f64 {
            *valid_out = 0;
            *latest_out = 0;
            let Some(out) = self.sf_dump(&["--latency", layer]) else {
                return -1.0;
            };
            let base = *since;
            let mut first = 0u64;
            let mut last = 0u64;
            let mut max_ts = base;
            let mut latest_valid = 0u64;
            let mut new_frames = 0usize;

            for (idx, line) in out.lines().enumerate() {
                if idx == 0 {
                    continue;
                }
                let nums = line
                    .split_whitespace()
                    .filter_map(|part| part.parse::<u64>().ok())
                    .collect::<Vec<_>>();
                if nums.len() != 3 {
                    continue;
                }
                let present = nums[1];
                if present == 0 || present == 9_223_372_036_854_775_807 {
                    continue;
                }
                *valid_out += 1;
                latest_valid = latest_valid.max(present);
                max_ts = max_ts.max(present);
                if base != 0 && present <= base {
                    continue;
                }
                if new_frames == 0 {
                    first = present;
                }
                last = present;
                new_frames += 1;
            }
            if max_ts > base {
                *since = max_ts;
            }
            *latest_out = latest_valid;
            if new_frames < 2 || last <= first {
                return -1.0;
            }
            let span_s = (last - first) as f64 / 1_000_000_000.0;
            if span_s <= 0.0 {
                -1.0
            } else {
                (new_frames.saturating_sub(1)) as f64 / span_s
            }
        }

        fn enable_timestats(&mut self) {
            let _ = self.sf_dump(&["--timestats", "-enable", "-clear"]);
            self.ts_mode = true;
            self.ts_last = -1;
            self.ts_last_time = None;
            println!("[Fallback] 切换到 SurfaceFlinger timestats");
        }

        fn poll_timestats(&mut self) -> f64 {
            let now_frames = self.timestats_frames();
            if now_frames < 0 {
                return 0.0;
            }
            let now = Instant::now();
            if self.ts_last >= 0 {
                if let Some(last_time) = self.ts_last_time {
                    let dt = now.duration_since(last_time).as_secs_f64();
                    if dt > 0.0 && now_frames >= self.ts_last {
                        let fps = (now_frames - self.ts_last) as f64 / dt;
                        self.ts_last = now_frames;
                        self.ts_last_time = Some(now);
                        return fps;
                    }
                }
            }
            self.ts_last = now_frames;
            self.ts_last_time = Some(now);
            0.0
        }

        fn timestats_frames(&mut self) -> i64 {
            let Some(out) = self.sf_dump(&["--timestats", "-dump"]) else {
                return -1;
            };
            let mut best = -1i64;
            let mut in_target = false;
            for line in out.lines() {
                if line.contains("layerName") {
                    in_target = layer_matches_pkg(line, &self.pkg);
                    continue;
                }
                if !in_target {
                    continue;
                }
                if let Some((_, value)) = line
                    .split_once("totalFrames")
                    .and_then(|(_, rest)| rest.split_once('='))
                {
                    if let Ok(frames) = value.trim().parse::<i64>() {
                        best = best.max(frames);
                    }
                    in_target = false;
                }
            }
            best
        }

        fn sf_dump(&mut self, args: &[&str]) -> Option<String> {
            let Some(binder) = self.binder.as_mut() else {
                println!("[Fallback] SurfaceFlinger binder 不可用, 无法执行 dump: {}", args.join(" "));
                return None;
            };
            match binder.dump(args) {
                Ok(out) => Some(out),
                Err(err) => {
                    println!(
                        "[Fallback] SurfaceFlinger dump 失败: args={} err={}",
                        args.join(" "),
                        err
                    );
                    None
                }
            }
        }
    }

    impl Drop for SfFallback {
        fn drop(&mut self) {
            if self.ts_mode {
                let _ = self.sf_dump(&["--timestats", "-disable"]);
            }
        }
    }

    fn unwrap_requested_layer_name(line: &str) -> String {
        const PREFIX: &str = "RequestedLayerState{";
        if !line.starts_with(PREFIX) {
            return line.to_string();
        }
        let mut name = &line[PREFIX.len()..];
        if let Some((_, rest)) = name.split_once(' ') {
            name = rest;
        }
        let mut end = name.len();
        for marker in [" parentId=", " z="] {
            if let Some(pos) = name.find(marker) {
                end = end.min(pos);
            }
        }
        if let Some(pos) = name.rfind('}') {
            end = end.min(pos);
        }
        name[..end].trim().to_string()
    }

    fn preview_layers(layers: &[String]) -> String {
        if layers.is_empty() {
            return "-".to_string();
        }
        let mut preview = layers.iter().take(3).cloned().collect::<Vec<_>>().join(" | ");
        if layers.len() > 3 {
            preview.push_str(" | ...");
        }
        preview
    }

    fn latency_ts_is_fresh(latest: u64, now: u64) -> bool {
        if latest == 0 || now == 0 {
            return false;
        }
        latest > now || now - latest <= FPS_FRESH_NS
    }

    fn now_monotonic_ns() -> u64 {
        let mut ts = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        let rc = unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
        if rc != 0 {
            return 0;
        }
        ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
    }

    fn layer_matches_pkg(text: &str, pkg: &str) -> bool {
        if pkg.is_empty() {
            return false;
        }
        let text_bytes = text.as_bytes();
        let pkg_bytes = pkg.as_bytes();
        if pkg_bytes.len() > text_bytes.len() {
            return false;
        }
        let mut start = 0usize;
        while start + pkg_bytes.len() <= text_bytes.len() {
            let Some(pos) = find_subslice(&text_bytes[start..], pkg_bytes) else {
                return false;
            };
            let off = start + pos;
            let before = if off == 0 { 0 } else { text_bytes[off - 1] };
            let after_idx = off + pkg_bytes.len();
            let after = if after_idx >= text_bytes.len() {
                0
            } else {
                text_bytes[after_idx]
            };
            if pkg_boundary(before) && pkg_boundary(after) {
                return true;
            }
            start = off + 1;
        }
        false
    }

    fn pkg_boundary(byte: u8) -> bool {
        byte == 0 || !(byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.'))
    }

    fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
        haystack
            .windows(needle.len())
            .position(|window| window == needle)
    }
