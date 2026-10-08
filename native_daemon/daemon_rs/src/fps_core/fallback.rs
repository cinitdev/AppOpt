use super::*;
// SurfaceFlinger 帧率备用采集。
//
// eBPF 是逐帧最优路径，但部分内核/ROM 可能不支持或 libgui 符号变动。
// fallback 优先通过 binder 直连 SurfaceFlinger dump：
// - 优先 --latency <layer>，从图层帧时间戳计算 FPS。
// - latency 连续失败后切 --timestats。
// - binder 不可用时最终降级到有硬超时的低频 dumpsys timestats。
//
// 这条路径不是完美等价于 eBPF：它更接近 SurfaceFlinger 图层输出帧率，
// 但胜在不依赖 BPF 能力，适合作为兼容兜底。
pub(super) struct SfFallback {
    pub(super) pkg: String,
    // 优先直接 binder 调 SurfaceFlinger，避免 fork dumpsys。
    pub(super) binder: Option<BinderSf>,
    // latency 模式锁定的图层名；失效后会清空并重新发现。
    pub(super) main_layer: String,
    pub(super) since: u64,
    pub(super) miss: u32,
    pub(super) probe_fail: u32,
    pub(super) ts_mode: bool,
    pub(super) ts_enabled: bool,
    pub(super) ts_owned: bool,
    pub(super) ts_last: i64,
    pub(super) ts_last_source: Option<String>,
    pub(super) ts_last_time: Option<Instant>,
    pub(super) ts_last_fps: f64,
    pub(super) ts_last_targeted: bool,
    pub(super) ts_layer_frames: Vec<(String, i64)>,
    pub(super) ts_selected_layer: Option<String>,
    pub(super) ts_global_reported: bool,
    pub(super) sample_targeted: bool,
    pub(super) cli_mode: bool,
    pub(super) cli_next: Option<Instant>,
    pub(super) cli_last_error_log: Option<Instant>,
    pub(super) latency_next_probe: Option<Instant>,
    pub(super) latency_last_log: Option<Instant>,
}

pub(super) struct TimestatsSample {
    pub(super) source: String,
    pub(super) frames: i64,
    pub(super) targeted: bool,
}

impl SfFallback {
    pub(super) fn new(pkg: String) -> Option<Self> {
        let (binder, cli_mode) = match BinderSf::new() {
            Ok(binder) => (Some(binder), false),
            Err(err) => {
                log_warn!(
                    "[Fallback] SurfaceFlinger binder 不可用，切换低频 CLI timestats: {err}"
                );
                (None, true)
            }
        };
        Some(Self {
            pkg,
            binder,
            main_layer: String::new(),
            since: 0,
            miss: 0,
            probe_fail: 0,
            ts_mode: cli_mode,
            ts_enabled: false,
            ts_owned: false,
            ts_last: -1,
            ts_last_source: None,
            ts_last_time: None,
            ts_last_fps: 0.0,
            ts_last_targeted: false,
            ts_layer_frames: Vec::new(),
            ts_selected_layer: None,
            ts_global_reported: false,
            sample_targeted: false,
            cli_mode,
            cli_next: None,
            cli_last_error_log: None,
            latency_next_probe: None,
            latency_last_log: None,
        })
    }

    pub(super) fn poll(&mut self) -> f64 {
        self.sample_targeted = false;
        if self.ts_mode {
            return self.poll_timestats();
        }

        if self.main_layer.is_empty() {
            return self.discover_latency_layer();
        }

        let mut valid = 0usize;
        let mut latest = 0u64;
        let fps = self.layer_fps(&self.main_layer.clone(), &mut valid, &mut latest);
        if self.cli_mode {
            let _ = self.enable_timestats();
            return 0.0;
        }
        if fps >= 0.0 {
            self.sample_targeted = true;
            self.miss = 0;
            self.probe_fail = 0;
            return fps;
        }
        if valid > 0 && latency_ts_is_fresh(latest, now_monotonic_ns()) {
            self.sample_targeted = true;
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

    pub(super) fn discover_latency_layer(&mut self) -> f64 {
        // 遍历 SurfaceFlinger 图层，挑目标包名相关且 latency 数据最新的图层。
        // 如果 latency 完全拿不到有效数据，多次失败后切 timestats。
        let now = Instant::now();
        if self.latency_next_probe.is_some_and(|next| now < next) {
            return 0.0;
        }
        self.latency_next_probe = None;
        let candidates = self.list_candidates();
        if self.cli_mode {
            let _ = self.enable_timestats();
            return 0.0;
        }
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
            if self.cli_mode {
                let _ = self.enable_timestats();
                return 0.0;
            }
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
            log_info!(
                "[Fallback] 已锁定 SurfaceFlinger 图层: {} ({:.1} fps)",
                best_layer, best_fps
            );
            self.main_layer = best_layer;
            self.since = best_since;
            self.miss = 0;
            self.probe_fail = 0;
            self.latency_next_probe = None;
            self.sample_targeted = true;
            return best_fps;
        }

        self.probe_fail = self.probe_fail.saturating_add(1);
        let should_log = self
            .latency_last_log
            .is_none_or(|last| now.duration_since(last) >= FPS_ERROR_LOG_INTERVAL);
        if valid_total == 0 {
            if should_log && candidate_count == 0 {
                log_info!(
                    "[Fallback] SurfaceFlinger latency 未找到匹配图层: pkg={} 失败={}/{}",
                    self.pkg, self.probe_fail, FPS_PROBE_FAIL
                );
            } else if should_log {
                log_info!(
                    "[Fallback] SurfaceFlinger latency 候选图层无有效帧: pkg={} 候选={} [{}] 失败={}/{}",
                    self.pkg,
                    candidate_count,
                    candidate_preview,
                    self.probe_fail,
                    FPS_PROBE_FAIL
                );
            }
        } else if should_log {
            log_info!(
                "[Fallback] SurfaceFlinger latency 有历史帧但不新鲜或不足: pkg={} 候选={} 有效帧={} [{}]",
                self.pkg, candidate_count, valid_total, candidate_preview
            );
        }
        if should_log {
            self.latency_last_log = Some(now);
        }

        match super::latency_probe_delay(self.probe_fail) {
            Some(delay) if !delay.is_zero() => {
                self.latency_next_probe = Some(now + delay);
            }
            Some(_) => {}
            None => {
                self.latency_next_probe = None;
                let _ = self.enable_timestats();
            }
        }
        0.0
    }

    pub(super) fn list_candidates(&mut self) -> Vec<String> {
        let Some(out) = self.sf_dump(&["--list"], false) else {
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

    pub(super) fn layer_fps(&mut self, layer: &str, valid: &mut usize, latest: &mut u64) -> f64 {
        let mut since = self.since;
        let fps = self.layer_fps_with_since(layer, &mut since, valid, latest);
        if since > self.since {
            self.since = since;
        }
        fps
    }

    pub(super) fn layer_fps_with_since(
        &mut self,
        layer: &str,
        since: &mut u64,
        valid_out: &mut usize,
        latest_out: &mut u64,
    ) -> f64 {
        *valid_out = 0;
        *latest_out = 0;
        let Some(out) = self.sf_dump(&["--latency", layer], false) else {
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
            super::valid_fps_sample((new_frames.saturating_sub(1)) as f64 / span_s)
                .unwrap_or(-1.0)
        }
    }

    pub(super) fn enable_timestats(&mut self) -> bool {
        if self.ts_enabled {
            self.ts_mode = true;
            return true;
        }
        self.ts_mode = true;
        self.ts_last = -1;
        self.ts_last_source = None;
        self.ts_last_time = None;
        self.ts_last_fps = 0.0;
        self.ts_last_targeted = false;
        self.ts_layer_frames.clear();
        self.ts_selected_layer = None;
        self.ts_global_reported = false;

        // timestats 是 SurfaceFlinger 的全局状态。先探测是否已有其他工具启用；
        // 已启用时只复用，既不清空统计，也不会在 QixiaThreads 结束时替对方关闭。
        let already_enabled = self
            .sf_dump(&["--timestats", "-dump"], true)
            .is_some_and(|output| timestats_dump_is_active(&output));
        if self.cli_mode {
            // 控制命令不能被 1 秒采样限频挡住。
            self.cli_next = None;
        }
        if already_enabled {
            self.ts_enabled = true;
            self.ts_owned = false;
            log_info!(
                "[Fallback] 复用已启用的 SurfaceFlinger timestats ({})，不会清空或关闭",
                if self.cli_mode { "CLI" } else { "binder 直连" }
            );
            return true;
        }

        let enabled = self.sf_dump(&["--timestats", "-enable"], true).is_some();
        self.ts_enabled = enabled;
        self.ts_owned = enabled;
        if enabled {
            log_info!(
                "[Fallback] 切换到 SurfaceFlinger timestats ({})",
                if self.cli_mode { "CLI" } else { "binder 直连" }
            );
        }
        enabled
    }

    pub(super) fn poll_timestats(&mut self) -> f64 {
        if !self.ts_enabled && !self.enable_timestats() {
            return 0.0;
        }
        let Some(sample) = self.timestats_sample() else {
            let cached = self
                .ts_last_time
                .filter(|last| last.elapsed() <= Duration::from_millis(2500))
                .map_or(0.0, |_| self.ts_last_fps);
            self.sample_targeted = cached > 0.0 && self.ts_last_targeted;
            return cached;
        };
        self.sample_targeted = sample.targeted;
        let now_frames = sample.frames;
        let now = Instant::now();
        if self.ts_last >= 0 && self.ts_last_source.as_deref() != Some(sample.source.as_str()) {
            // 图层或全局计数器切换时先重建差分基准，不能把两个独立累加器
            // 相减生成一次虚假的高/低 FPS。
            self.ts_last = now_frames;
            self.ts_last_source = Some(sample.source);
            self.ts_last_time = Some(now);
            self.ts_last_fps = 0.0;
            self.ts_last_targeted = self.sample_targeted;
            return 0.0;
        }
        if self.ts_last >= 0 {
            if let Some(last_time) = self.ts_last_time {
                let dt = now.duration_since(last_time).as_secs_f64();
                if dt > 0.0 && now_frames >= self.ts_last {
                    let raw_fps = (now_frames - self.ts_last) as f64 / dt;
                    self.ts_last = now_frames;
                    self.ts_last_source = Some(sample.source);
                    self.ts_last_time = Some(now);
                    if let Some(fps) = super::valid_fps_sample(raw_fps) {
                        self.ts_last_fps = fps;
                        self.ts_last_targeted = self.sample_targeted;
                        return fps;
                    }
                    self.ts_last_fps = 0.0;
                    self.ts_last_targeted = self.sample_targeted;
                    return 0.0;
                }
            }
        }
        self.ts_last = now_frames;
        self.ts_last_source = Some(sample.source);
        self.ts_last_time = Some(now);
        self.ts_last_targeted = self.sample_targeted;
        0.0
    }

    pub(super) fn timestats_sample(&mut self) -> Option<TimestatsSample> {
        let Some(out) = self.sf_dump(&["--timestats", "-dump"], true) else {
            return None;
        };
        let mut global = -1i64;
        let mut target_layer: Option<String> = None;
        let mut target_layers = Vec::<(String, i64)>::new();
        let mut reached_layers = false;
        for line in out.lines() {
            if line.contains("layerName") {
                reached_layers = true;
                target_layer = layer_matches_pkg(line, &self.pkg)
                    .then(|| timestats_layer_identity(line));
                continue;
            }
            if !reached_layers && line.trim_start().starts_with("totalFrames") {
                if let Some(frames) = parse_timestats_frames(line) {
                    global = global.max(frames);
                }
            }
            let Some(layer) = target_layer.as_ref() else {
                continue;
            };
            if let Some(frames) = parse_timestats_frames(line) {
                if let Some((_, current)) = target_layers
                    .iter_mut()
                    .find(|(name, _)| name == layer)
                {
                    *current = (*current).max(frames);
                } else {
                    target_layers.push((layer.clone(), frames));
                }
                target_layer = None;
            }
        }

        let selected = super::select_active_timestats_layer(
            &self.ts_layer_frames,
            &target_layers,
            self.ts_selected_layer.as_deref(),
        );
        self.ts_layer_frames = target_layers;
        if let Some(index) = selected {
            let (source, frames) = self.ts_layer_frames[index].clone();
            self.ts_selected_layer = Some(source.clone());
            return Some(TimestatsSample {
                source,
                frames,
                targeted: true,
            });
        }
        self.ts_selected_layer = None;
        if self.cli_mode && global >= 0 {
            if !self.ts_global_reported {
                log_info!(
                    "[Fallback] CLI timestats 未提供目标应用图层，全局合成帧率仅供悬浮显示，不参与动态调度: pkg={}",
                    self.pkg
                );
                self.ts_global_reported = true;
            }
            return Some(TimestatsSample {
                source: "__surfaceflinger_global__".to_string(),
                frames: global,
                targeted: false,
            });
        }
        None
    }

    pub(super) fn sample_is_targeted(&self) -> bool {
        self.sample_targeted
    }

    pub(super) fn sf_dump(&mut self, args: &[&str], allow_cli: bool) -> Option<String> {
        if !self.cli_mode {
            if let Some(binder) = self.binder.as_mut() {
                match binder.dump(args) {
                    Ok(out) => return Some(out),
                    Err(err) => {
                        log_warn!(
                            "[Fallback] SurfaceFlinger binder dump 失败，切换低频 CLI timestats: args={} err={}",
                            args.join(" "),
                            err
                        );
                    }
                }
            }
            self.binder = None;
            self.cli_mode = true;
            self.ts_mode = true;
        }
        if !allow_cli {
            return None;
        }

        let now = Instant::now();
        if self.cli_next.is_some_and(|next| now < next) {
            return None;
        }
        self.cli_next = Some(now + Duration::from_secs(1));
        match sf_dump_cli_bounded(args) {
            Ok(out) => Some(out),
            Err(err) => {
                self.log_cli_error(args, &err);
                None
            }
        }
    }

    pub(super) fn log_cli_error(&mut self, args: &[&str], err: &io::Error) {
        let now = Instant::now();
        if self
            .cli_last_error_log
            .is_some_and(|last| now.duration_since(last) < Duration::from_secs(30))
        {
            return;
        }
        self.cli_last_error_log = Some(now);
        log_warn!(
            "[FPS][CLI] dumpsys SurfaceFlinger 执行失败，已中止本次采样: args={} err={}",
            args.join(" "),
            err
        );
    }

    pub(super) fn disable_timestats(&mut self) {
        if !self.ts_owned {
            self.ts_enabled = false;
            return;
        }
        let args = ["--timestats", "-disable"];
        let binder_disabled = if self.cli_mode {
            false
        } else {
            self.binder
                .as_mut()
                .is_some_and(|binder| binder.dump(&args).is_ok())
        };
        if !binder_disabled {
            if let Err(err) = sf_dump_cli_bounded(&args) {
                self.log_cli_error(&args, &err);
            }
        }
        self.ts_enabled = false;
        self.ts_owned = false;
    }
}

impl Drop for SfFallback {
    fn drop(&mut self) {
        if self.ts_owned {
            self.disable_timestats();
        }
    }
}

pub(super) fn timestats_dump_is_active(output: &str) -> bool {
    let lower = output.to_ascii_lowercase();
    if lower.contains("timestats is disabled")
        || lower.contains("time stats is disabled")
        || lower.contains("enabled: false")
        || lower.contains("enabled=false")
    {
        return false;
    }
    output.lines().any(|line| line.contains("layerName"))
        && output
            .lines()
            .any(|line| parse_timestats_frames(line).is_some())
}

pub(super) fn timestats_layer_identity(line: &str) -> String {
    let value = line
        .split_once('=')
        .or_else(|| line.split_once(':'))
        .map_or(line, |(_, value)| value)
        .trim()
        .trim_matches(|ch| matches!(ch, '"' | ',' | '{' | '}'))
        .trim();
    if value.is_empty() {
        line.trim().to_string()
    } else {
        value.to_string()
    }
}

pub(super) fn parse_timestats_frames(line: &str) -> Option<i64> {
    let (_, rest) = line.split_once("totalFrames")?;
    let (_, value) = rest.split_once('=')?;
    let digits = value
        .trim_start()
        .bytes()
        .take_while(|byte| byte.is_ascii_digit())
        .collect::<Vec<_>>();
    if digits.is_empty() {
        return None;
    }
    std::str::from_utf8(&digits).ok()?.parse::<i64>().ok()
}

pub(super) fn sf_dump_cli_bounded(args: &[&str]) -> io::Result<String> {
    const MAX_OUTPUT: usize = 1 << 20;
    const TIMEOUT: Duration = Duration::from_millis(1500);

    let mut child = Command::new("/system/bin/dumpsys")
        .arg("SurfaceFlinger")
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut stdout = child.stdout.take().ok_or_else(|| {
        io::Error::new(io::ErrorKind::BrokenPipe, "dumpsys stdout unavailable")
    })?;
    let (reader_tx, reader_rx) = mpsc::sync_channel(1);
    let _reader = match thread::Builder::new()
        .name("QixiaThreadsSfCli".to_string())
        .spawn(move || {
            let result = (|| {
                let mut output = Vec::with_capacity(MAX_OUTPUT.min(256 * 1024));
                let mut chunk = [0u8; 8192];
                loop {
                    let read = stdout.read(&mut chunk)?;
                    if read == 0 {
                        break;
                    }
                    let remaining = MAX_OUTPUT.saturating_sub(output.len());
                    output.extend_from_slice(&chunk[..read.min(remaining)]);
                }
                Ok::<Vec<u8>, io::Error>(output)
            })();
            let _ = reader_tx.send(result);
        }) {
        Ok(reader) => reader,
        Err(err) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err(err);
        }
    };

    let started = Instant::now();
    let (status, timed_out) = loop {
        match child.try_wait() {
            Ok(Some(status)) => break (status, false),
            Ok(None) if started.elapsed() < TIMEOUT => {
                thread::sleep(Duration::from_millis(20));
            }
            Ok(None) => {
                let _ = child.kill();
                let status = child.wait()?;
                break (status, true);
            }
            Err(err) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(err);
            }
        }
    };
    let output = reader_rx
        .recv_timeout(Duration::from_millis(750))
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::TimedOut,
                "dumpsys stdout reader did not close after process exit",
            )
        })??;
    if timed_out {
        return Err(io::Error::new(
            io::ErrorKind::TimedOut,
            "dumpsys SurfaceFlinger timeout",
        ));
    }
    if !status.success() {
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!("dumpsys SurfaceFlinger exit status {status}"),
        ));
    }
    Ok(String::from_utf8_lossy(&output).into_owned())
}

pub(super) fn unwrap_requested_layer_name(line: &str) -> String {
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

pub(super) fn preview_layers(layers: &[String]) -> String {
    if layers.is_empty() {
        return "-".to_string();
    }
    let mut preview = layers.iter().take(3).cloned().collect::<Vec<_>>().join(" | ");
    if layers.len() > 3 {
        preview.push_str(" | ...");
    }
    preview
}

pub(super) fn latency_ts_is_fresh(latest: u64, now: u64) -> bool {
    if latest == 0 || now == 0 {
        return false;
    }
    latest > now || now - latest <= FPS_FRESH_NS
}

pub(super) fn now_monotonic_ns() -> u64 {
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

pub(super) fn layer_matches_pkg(text: &str, pkg: &str) -> bool {
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

pub(super) fn pkg_boundary(byte: u8) -> bool {
    byte == 0 || !(byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.'))
}

pub(super) fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}
