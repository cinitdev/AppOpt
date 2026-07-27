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
    let claimed = format!("{CALIB_CMD_FILE}.processing");
    match fs::metadata(&claimed) {
        Ok(_) => {}
        Err(err) if err.kind() == io::ErrorKind::NotFound => {
            match fs::rename(CALIB_CMD_FILE, &claimed) {
                Ok(()) => {}
                Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
                Err(err) => return Err(err),
            }
        }
        Err(err) => return Err(err),
    }

    let before = fs::metadata(&claimed)?;
    let bytes = match fs::read(&claimed) {
        Ok(bytes) => bytes,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(err) => return Err(err),
    };
    let after = match fs::metadata(&claimed) {
        Ok(metadata) => metadata,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(err) => return Err(err),
    };
    let stable = before.len() == after.len()
        && before.modified().ok() == after.modified().ok();
    if !stable {
        return Ok(None);
    }

    let text = String::from_utf8(bytes).ok().map(|text| text.trim().to_string());
    let valid = text.as_deref().is_some_and(|text| {
        text.starts_with("start ") || text == "stop" || text.starts_with("stop ")
    });
    if !valid {
        let stale = after
            .modified()
            .ok()
            .and_then(|modified| SystemTime::now().duration_since(modified).ok())
            .is_some_and(|age| age >= Duration::from_secs(2));
        if stale {
            match fs::remove_file(&claimed) {
                Ok(()) => {}
                Err(err) if err.kind() == io::ErrorKind::NotFound => {}
                Err(err) => return Err(err),
            }
        }
        return Ok(None);
    }

    match fs::remove_file(&claimed) {
        Ok(()) => {}
        Err(err) if err.kind() == io::ErrorKind::NotFound => {}
        Err(err) => return Err(err),
    }
    Ok(text)
}

fn write_state(state: &str) -> io::Result<()> {
    fs::create_dir_all(CONFIG_DIR)?;
    fs::write(CALIB_STATE_FILE, state)
}

fn read_cmdline(pid: i32) -> io::Result<String> {
    let data = fs::read(format!("/proc/{pid}/cmdline"))?;
    let first = data.split(|byte| *byte == 0).next().unwrap_or_default();
    let basename = first
        .rsplit(|byte| *byte == b'/')
        .next()
        .unwrap_or_default();
    Ok(String::from_utf8_lossy(basename).trim().to_string())
}

fn read_thread_stat(path: &str) -> Option<(u64, u64)> {
    let text = fs::read_to_string(path).ok()?;
    let end = text.rfind(')')?;
    let rest = text.get(end + 2..)?;
    let fields: Vec<&str> = rest.split_whitespace().collect();
    let utime = fields.get(11)?.parse::<u64>().ok()?;
    let stime = fields.get(12)?.parse::<u64>().ok()?;
    let starttime = fields.get(19)?.parse::<u64>().ok()?;
    Some((utime + stime, starttime))
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
