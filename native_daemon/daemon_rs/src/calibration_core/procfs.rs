// 校准模块专用 procfs 工具。
//
// 校准和常驻绑核扫描的目标不同：
// - 常驻扫描只关心规则命中后该把线程绑到哪里。
// - 校准扫描要收集主进程/子进程 CPU 使用率，所以会读取 stat 的 utime/stime。
//
// /proc 读取失败很常见：进程可能刚退出，线程可能刚结束。这里统一选择跳过，不把它当异常。
fn collect_pkg_processes(pkg: &str) -> Vec<ProcInfo> {
    let mut out = Vec::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return out;
    };
    for entry in entries.flatten() {
        let Some(pid) = entry.file_name().to_str().and_then(parse_pid_text) else {
            continue;
        };
        let Ok(cmdline) = read_cmdline(pid) else {
            continue;
        };
        if cmdline == pkg
            || cmdline
                .strip_prefix(pkg)
                .is_some_and(|rest| rest.starts_with(':'))
        {
            out.push(ProcInfo {
                pid,
                owner: cmdline,
            });
        }
    }
    out
}

fn read_command() -> io::Result<Option<String>> {
    let text = match fs::read_to_string(CALIB_CMD_FILE) {
        Ok(text) => text.trim().to_string(),
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(err) => return Err(err),
    };
    if text.is_empty() {
        return Ok(None);
    }
    let valid = text.starts_with("start ") || text == "stop" || text.starts_with("stop ");
    fs::write(CALIB_CMD_FILE, "")?;
    if valid {
        Ok(Some(text))
    } else {
        Ok(None)
    }
}

fn write_state(state: &str) -> io::Result<()> {
    fs::create_dir_all(CONFIG_DIR)?;
    fs::write(CALIB_STATE_FILE, state)
}

fn read_cmdline(pid: i32) -> io::Result<String> {
    let data = fs::read(format!("/proc/{pid}/cmdline"))?;
    let first = data.split(|byte| *byte == 0).next().unwrap_or_default();
    Ok(String::from_utf8_lossy(first).trim().to_string())
}

fn read_stat_ticks(path: &str) -> Option<u64> {
    let text = fs::read_to_string(path).ok()?;
    let end = text.rfind(')')?;
    let rest = text.get(end + 2..)?;
    let fields: Vec<&str> = rest.split_whitespace().collect();
    let utime = fields.get(11)?.parse::<u64>().ok()?;
    let stime = fields.get(12)?.parse::<u64>().ok()?;
    Some(utime + stime)
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

fn safe_file_name(name: &str) -> String {
    name.chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
                ch
            } else {
                '_'
            }
        })
        .collect()
}

fn safe_history_name(name: &str) -> String {
    name.chars()
        .map(|ch| match ch {
            '|' | ',' | ';' | '\n' | '\r' => '_',
            ch if ch < ' ' => '_',
            _ => ch,
        })
        .collect()
}
