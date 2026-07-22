// 前台 cgroup 检测辅助。
//
// App 侧前台识别主路径是 ActivityTaskManager helper + UsageStats，Rust/C 里的 --app-state
// 是兜底：扫描 top-app / foreground_window cgroup，输出当前前台组里的包名列表。
//
// 这个结果不直接决定守护绑核，只给悬浮球判断“目标是否还在前台”时使用。
// Android/ROM cgroup 路径不完全一致，所以路径列表定义在 preamble.rs 里集中维护。
fn app_state_print_cli(pkg: &str) -> io::Result<()> {
    let state = app_top_state_check(pkg);
    println!("ok={}", if state.ok { 1 } else { 0 });
    println!(
        "target_top_app={}",
        if state.target_top_app { 1 } else { 0 }
    );
    println!("top_app={}", if state.target_top_app { 1 } else { 0 });
    println!("pid={}", state.target_pid);
    println!("scanned={}", state.scanned);
    println!("package_count={}", state.packages.len());
    println!("packages={}", state.packages.join(","));
    Ok(())
}

fn app_top_state_check(target_pkg: &str) -> AppTopState {
    let mut state = AppTopState::default();
    let mut seen_pids = BTreeSet::new();
    let mut seen_packages = BTreeSet::new();

    for path in TOP_APP_GROUP_PATHS {
        scan_top_app_path(
            path,
            target_pkg,
            &mut state,
            &mut seen_pids,
            &mut seen_packages,
        );
    }

    state.ok = state.target_top_app || !state.packages.is_empty();
    state
}

fn scan_top_app_path(
    path: &str,
    target_pkg: &str,
    state: &mut AppTopState,
    seen_pids: &mut BTreeSet<i32>,
    seen_packages: &mut BTreeSet<String>,
) {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(_) => return,
    };
    let is_tasks = path.ends_with("/tasks");

    for raw in text.lines() {
        let Some(id) = parse_pid_text(raw.trim()) else {
            continue;
        };
        let pid = if is_tasks {
            read_tgid(id).unwrap_or(id)
        } else {
            id
        };
        if pid <= 0 || !seen_pids.insert(pid) {
            continue;
        }

        state.scanned += 1;
        let Ok(proc_name) = read_cmdline(pid) else {
            continue;
        };
        if proc_name.is_empty() {
            continue;
        }

        if proc_matches_target(&proc_name, target_pkg) {
            let is_main = proc_name == target_pkg;
            state.target_top_app = true;
            if is_main {
                if !state.target_pid_is_main {
                    state.target_pid = pid;
                    state.target_pid_is_main = true;
                }
            } else if state.target_pid <= 0 {
                state.target_pid = pid;
            }
        }

        let Some(pkg) = normalize_package(&proc_name) else {
            continue;
        };
        if seen_packages.insert(pkg.clone()) {
            state.packages.push(pkg);
        }
    }
}

fn parse_pid_text(text: &str) -> Option<i32> {
    if text.is_empty() || !text.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let pid = text.parse::<i32>().ok()?;
    if pid > 0 && pid <= 4_194_304 {
        Some(pid)
    } else {
        None
    }
}

fn read_tgid(tid: i32) -> Option<i32> {
    let status = fs::read_to_string(format!("/proc/{tid}/status")).ok()?;
    for line in status.lines() {
        let Some(value) = line.strip_prefix("Tgid:") else {
            continue;
        };
        return parse_pid_text(value.trim());
    }
    None
}

fn proc_matches_target(proc_name: &str, target_pkg: &str) -> bool {
    if target_pkg.is_empty() {
        return false;
    }
    proc_name == target_pkg
        || proc_name
            .strip_prefix(target_pkg)
            .is_some_and(|rest| rest.starts_with(':'))
}

fn normalize_package(proc_name: &str) -> Option<String> {
    if proc_name.is_empty() || proc_name.contains('/') || !proc_name.contains('.') {
        return None;
    }
    if matches!(
        proc_name,
        "system_server"
            | "surfaceflinger"
            | "zygote"
            | "zygote64"
            | "servicemanager"
            | "hwservicemanager"
            | "vndservicemanager"
    ) {
        return None;
    }
    Some(
        proc_name
            .split_once(':')
            .map_or(proc_name, |(pkg, _)| pkg)
            .to_string(),
    )
}
