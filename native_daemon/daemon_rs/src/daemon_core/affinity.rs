// CPU affinity 写入与读回验证。
//
// daemon 最终只做一件事：把命中的 TID 写到目标 CPU mask。
// 写入前先读取 /proc/<pid>/task/<tid>/status 里的 Cpus_allowed_list，已经一致就跳过；
// 写入后再读回一次，如果 expected != actual，就把它计为 mismatched。
//
// mismatched 对移植系统很关键：有些 ROM/厂商服务会反复把线程绑回 4-7、6-7 之类的范围，
// 这时不是 AppOpt 规则没命中，而是外部调度服务在抢写。
fn apply_hits(hits: &[ProcHit], detail_log: bool) -> ApplyStats {
    let mut stats = ApplyStats::default();
    let mut invalid_details = 0usize;
    let mut read_failed_details = 0usize;
    let mut cpuset_failed_details = 0usize;
    let mut affinity_failed_details = 0usize;
    let mut mismatch_details = 0usize;

    for hit in hits {
        for action in &hit.actions {
            // cpus 解析失败不终止整个 daemon，只统计并打印错误，避免一条坏规则影响其他应用。
            let Some(mask) = CpuMask::parse(&action.cpus) else {
                stats.invalid_rules += 1;
                if should_log_detail(detail_log, &mut invalid_details) {
                    eprintln!(
                        "[RS] 无效CPU规则 进程={} 线程={} 线程名={} 规则={}",
                        hit.pid, action.tid, action.name, action.rule
                    );
                }
                continue;
            };

            // 已经在目标核心上就不重复写 affinity，减少长期守护进程对系统的打扰。
            match read_allowed_mask(hit.pid, action.tid) {
                Ok(Some(current)) if current == mask => {
                    stats.skipped += 1;
                    continue;
                }
                Ok(_) => {}
                Err(err) if is_thread_gone_error(&err) => {
                    stats.skipped += 1;
                    continue;
                }
                Err(err) => {
                    if should_log_detail(detail_log, &mut read_failed_details) {
                        eprintln!(
                            "[RS] 读取绑核状态失败 进程={} 线程={} 线程名={} 错误={}",
                            hit.pid,
                            action.tid,
                            action.name,
                            error_text_zh(&err)
                        );
                    }
                }
            }

            if let Err(err) = move_tid_to_cpuset(action.tid, &mask) {
                if is_thread_gone_error(&err) {
                    stats.skipped += 1;
                    continue;
                }
                if !is_cpuset_expected_reject(&err) {
                    stats.cpuset_failed += 1;
                }
                if !is_cpuset_expected_reject(&err)
                    && should_log_detail(detail_log, &mut cpuset_failed_details)
                {
                    eprintln!(
                        "[RS] cpuset辅助写入失败 进程={} 线程={} 线程名={} 规则={} 核心={} 错误={}",
                        hit.pid,
                        action.tid,
                        action.name,
                        action.rule,
                        action.cpus,
                        error_text_zh(&err)
                    );
                }
            }

            match set_affinity(action.tid, &mask) {
                Ok(()) => {
                    stats.applied += 1;
                    // 写入后读回一次，用于发现移植系统或厂商服务把线程核心抢写回去的情况。
                    match read_allowed_mask(hit.pid, action.tid) {
                        Ok(Some(current)) if current != mask => {
                            if current.is_subset_of(&mask) {
                                // Android cpuset/cgroup 可能会把有效核心收窄成规则的子集。
                                // C 版不会记录这种读回差异；这里也不把它算成异常。
                            } else {
                                stats.mismatched += 1;
                                if should_log_detail(detail_log, &mut mismatch_details) {
                                    eprintln!(
                                        "[RS] 绑核被系统改写 进程={} 线程={} 线程名={} 规则={} 期望={} 实际={}",
                                        hit.pid,
                                        action.tid,
                                        action.name,
                                        action.rule,
                                        action.cpus,
                                        current.to_list()
                                    );
                                }
                            }
                        }
                        _ => {}
                    }
                }
                Err(err) => {
                    if is_thread_gone_error(&err) {
                        stats.skipped += 1;
                    } else if is_affinity_restricted_error(&err) {
                        stats.restricted += 1;
                    } else {
                        stats.failed += 1;
                        if should_log_detail(detail_log, &mut affinity_failed_details) {
                            eprintln!(
                                "[RS] 绑核失败 进程={} 线程={} 线程名={} 规则={} 错误={}",
                                hit.pid,
                                action.tid,
                                action.name,
                                action.rule,
                                error_text_zh(&err)
                            );
                        }
                    }
                }
            }
        }
    }

    if detail_log {
        log_limited_detail_count("无效CPU规则", invalid_details);
        log_limited_detail_count("读取绑核状态失败", read_failed_details);
        log_limited_detail_count("cpuset辅助写入失败", cpuset_failed_details);
        log_limited_detail_count("绑核失败", affinity_failed_details);
        log_limited_detail_count("绑核被系统改写", mismatch_details);
    }

    stats
}

fn should_log_detail(detail_log: bool, detail_count: &mut usize) -> bool {
    if !detail_log {
        return false;
    }
    let should_log = *detail_count < MAX_ERROR_DETAILS_PER_ROUND;
    *detail_count += 1;
    should_log
}

fn log_limited_detail_count(kind: &str, detail_count: usize) {
    if detail_count > MAX_ERROR_DETAILS_PER_ROUND {
        eprintln!(
            "[RS] {kind}: 本轮共 {} 条, 仅显示前 {} 条明细",
            detail_count, MAX_ERROR_DETAILS_PER_ROUND
        );
    }
}

fn is_cpuset_expected_reject(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(1 | 13 | 22))
        || matches!(
            err.kind(),
            io::ErrorKind::PermissionDenied | io::ErrorKind::InvalidInput
        )
}

fn is_affinity_restricted_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(1 | 13 | 22))
        || matches!(
            err.kind(),
            io::ErrorKind::PermissionDenied | io::ErrorKind::InvalidInput
        )
}

fn is_thread_gone_error(err: &io::Error) -> bool {
    matches!(err.raw_os_error(), Some(2 | 3)) || err.kind() == io::ErrorKind::NotFound
}

fn error_text_zh(err: &io::Error) -> String {
    match err.raw_os_error() {
        Some(1) => "权限不足(EPERM/1), 内核或安全策略拒绝操作".to_string(),
        Some(2) => "路径不存在(ENOENT/2), 目标进程或线程可能已经退出".to_string(),
        Some(3) => "线程不存在(ESRCH/3), 目标线程可能已经结束".to_string(),
        Some(13) => "权限不足(EACCES/13), 无法访问目标文件或线程".to_string(),
        Some(16) => "资源忙(EBUSY/16), 系统暂时无法完成操作".to_string(),
        Some(19) => "设备不存在(ENODEV/19), 目标 cpuset 或系统节点不可用".to_string(),
        Some(22) => "无效参数(EINVAL/22), CPU 核心范围对当前线程不可用或 CPU mask 非法".to_string(),
        Some(code) => format!("系统错误(OS {code})"),
        None => match err.kind() {
            io::ErrorKind::NotFound => "路径不存在, 目标进程或线程可能已经退出".to_string(),
            io::ErrorKind::PermissionDenied => "权限不足, 内核或安全策略拒绝操作".to_string(),
            io::ErrorKind::InvalidInput => "无效参数, CPU 核心范围对当前线程不可用或 CPU mask 非法".to_string(),
            _ => "I/O 操作失败".to_string(),
        },
    }
}

impl CpuMask {
    fn empty() -> Self {
        Self {
            words: [0; CPU_MASK_WORDS],
        }
    }

    fn parse(input: &str) -> Option<Self> {
        let mut mask = Self::empty();
        let mut any = false;

        for part in input.split(',') {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }

            let (start, end) = if let Some((left, right)) = part.split_once('-') {
                let start = left.trim().parse::<usize>().ok()?;
                let end = right.trim().parse::<usize>().ok()?;
                if start > end {
                    return None;
                }
                (start, end)
            } else {
                let cpu = part.parse::<usize>().ok()?;
                (cpu, cpu)
            };

            for cpu in start..=end {
                mask.set(cpu)?;
                any = true;
            }
        }

        if any {
            Some(mask)
        } else {
            None
        }
    }

    fn set(&mut self, cpu: usize) -> Option<()> {
        let word = cpu / 64;
        if word >= CPU_MASK_WORDS {
            return None;
        }
        let bit = cpu % 64;
        self.words[word] |= 1u64 << bit;
        Some(())
    }

    fn or_assign(&mut self, other: &Self) {
        for (left, right) in self.words.iter_mut().zip(other.words.iter()) {
            *left |= *right;
        }
    }

    fn to_list(&self) -> String {
        let mut ranges = Vec::new();
        let mut cpu = 0usize;
        let max = CPU_MASK_WORDS * 64;

        while cpu < max {
            if !self.contains(cpu) {
                cpu += 1;
                continue;
            }

            let start = cpu;
            while cpu + 1 < max && self.contains(cpu + 1) {
                cpu += 1;
            }
            let end = cpu;
            if start == end {
                ranges.push(start.to_string());
            } else {
                ranges.push(format!("{start}-{end}"));
            }
            cpu += 1;
        }

        ranges.join(",")
    }

    fn contains(&self, cpu: usize) -> bool {
        let word = cpu / 64;
        if word >= CPU_MASK_WORDS {
            return false;
        }
        (self.words[word] & (1u64 << (cpu % 64))) != 0
    }

    fn is_subset_of(&self, other: &Self) -> bool {
        self.words
            .iter()
            .zip(other.words.iter())
            .all(|(left, right)| (left & !right) == 0)
    }
}

fn read_allowed_mask(pid: i32, tid: i32) -> io::Result<Option<CpuMask>> {
    let status = fs::read_to_string(format!("/proc/{pid}/task/{tid}/status"))?;
    for line in status.lines() {
        let Some(value) = line.strip_prefix("Cpus_allowed_list:") else {
            continue;
        };
        return Ok(CpuMask::parse(value.trim()));
    }
    Ok(None)
}

fn move_tid_to_cpuset(tid: i32, mask: &CpuMask) -> io::Result<()> {
    let cpuset_root = Path::new("/dev/cpuset");
    if !cpuset_root.exists() {
        return Ok(());
    }

    let cpus = mask.to_list();
    if cpus.is_empty() {
        return Ok(());
    }

    let present = read_present_cpus().unwrap_or_else(|| cpus.clone());
    ensure_cpuset_dir(Path::new(BASE_CPUSET), &present, "0")?;

    let target = Path::new(BASE_CPUSET).join(&cpus);
    ensure_cpuset_dir(&target, &cpus, "0")?;

    let mut tasks = fs::OpenOptions::new()
        .append(true)
        .open(target.join("tasks"))?;
    write!(tasks, "{tid}\n")?;
    Ok(())
}

fn read_present_cpus() -> Option<String> {
    fs::read_to_string("/sys/devices/system/cpu/present")
        .ok()
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
}

fn ensure_cpuset_dir(path: &Path, cpus: &str, mems: &str) -> io::Result<()> {
    fs::create_dir_all(path)?;
    set_cpuset_dir_owner_mode(path);
    fs::write(path.join("cpus"), cpus)?;
    fs::write(path.join("mems"), mems)?;
    Ok(())
}

#[cfg(unix)]
fn set_cpuset_dir_owner_mode(path: &Path) {
    let Some(path) = path.to_str() else {
        return;
    };
    let Ok(c_path) = CString::new(path) else {
        return;
    };
    unsafe {
        libc::chmod(c_path.as_ptr(), 0o755);
        libc::chown(c_path.as_ptr(), 0, 0);
    }
}

#[cfg(not(unix))]
fn set_cpuset_dir_owner_mode(_path: &Path) {}

#[cfg(any(target_os = "android", target_os = "linux"))]
unsafe extern "C" {
    fn sched_setaffinity(pid: i32, cpusetsize: usize, mask: *const u8) -> i32;
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn set_affinity(tid: i32, mask: &CpuMask) -> io::Result<()> {
    let rc = unsafe {
        sched_setaffinity(
            tid,
            std::mem::size_of_val(&mask.words),
            mask.words.as_ptr().cast::<u8>(),
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn set_affinity(_tid: i32, _mask: &CpuMask) -> io::Result<()> {
    Ok(())
}
