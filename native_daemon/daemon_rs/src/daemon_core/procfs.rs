// daemon 主流程共用的 procfs 小工具。
//
// 这些函数都保持“失败即返回 None/Err，由上层跳过”的风格。
// /proc 是瞬时视图，进程和线程随时可能退出，不能把读取失败当成严重错误。
fn parse_pid(file_name: &OsStr) -> Option<i32> {
    file_name.to_str()?.parse::<i32>().ok()
}

#[cfg(unix)]
fn metadata_uid(path: &Path) -> io::Result<u32> {
    fs::metadata(path).map(|metadata| metadata.uid())
}

#[cfg(not(unix))]
fn metadata_uid(_path: &Path) -> io::Result<u32> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "proc metadata UID is only available on Unix",
    ))
}

fn read_cmdline(pid: i32) -> io::Result<String> {
    let data = fs::read(format!("/proc/{pid}/cmdline"))?;
    let first = data.split(|byte| *byte == 0).next().unwrap_or_default();
    // C 版按 argv[0] 的 basename 匹配系统进程；Android 应用进程名本身不含路径。
    let basename = first
        .rsplit(|byte| *byte == b'/')
        .next()
        .unwrap_or_default();
    Ok(String::from_utf8_lossy(basename).trim().to_string())
}

fn read_comm(task_path: &Path) -> io::Result<String> {
    // /proc/<pid>/task/<tid>/comm 是内核字节序列，厂商线程名偶尔不是合法 UTF-8。
    // lossy 转换可保留扫描流程，不让一个异常线程名跳过整个目标线程集合。
    let comm = fs::read(task_path.join("comm"))?;
    Ok(String::from_utf8_lossy(&comm).trim().to_string())
}

fn read_proc_starttime(proc_or_task_path: &Path) -> io::Result<u64> {
    let stat = fs::read(proc_or_task_path.join("stat"))?;
    // comm 位于括号中且允许包含空格和右括号，所以必须从最后一个 ')' 后开始数。
    // 后续第 20 个字段对应 /proc stat 的 field 22 (starttime)。
    let close = stat.iter().rposition(|byte| *byte == b')').ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidData, "proc stat 缺少 comm 结束括号")
    })?;
    let tail = String::from_utf8_lossy(&stat[close + 1..]);
    let value = tail
        .split_whitespace()
        .nth(19)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "proc stat 缺少 starttime"))?;
    value
        .parse::<u64>()
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

fn proc_thread_identity_matches(hit: &ProcHit, action: &ThreadAction) -> io::Result<bool> {
    let (Some(expected_pid), Some(expected_tid)) = (hit.pid_starttime, action.tid_starttime) else {
        return Ok(false);
    };
    let proc_path = PathBuf::from(format!("/proc/{}", hit.pid));
    if read_proc_starttime(&proc_path)? != expected_pid {
        return Ok(false);
    }
    let task_path = proc_path.join("task").join(action.tid.to_string());
    Ok(read_proc_starttime(&task_path)? == expected_tid)
}

fn process_belongs_to_uid_package(cmdline: &str, pkg: &str) -> bool {
    cmdline == pkg
        || cmdline
            .strip_prefix(pkg)
            .is_some_and(|rest| rest.starts_with(':'))
}
