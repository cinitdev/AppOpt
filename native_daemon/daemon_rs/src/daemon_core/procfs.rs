use super::*;
// 守护进程主流程共用的 procfs 小工具。
//
// 这些函数都保持“失败即返回 None/Err，由上层跳过”的风格。
// /proc 是瞬时视图，进程和线程随时可能退出，不能把读取失败当成严重错误。
pub(super) fn parse_pid(file_name: &OsStr) -> Option<i32> {
    file_name.to_str()?.parse::<i32>().ok()
}

#[cfg(unix)]
pub(super) fn metadata_uid(path: &Path) -> io::Result<u32> {
    fs::metadata(path).map(|metadata| metadata.uid())
}

#[cfg(not(unix))]
pub(super) fn metadata_uid(_path: &Path) -> io::Result<u32> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "proc metadata UID is only available on Unix",
    ))
}

pub(super) use qixia_kernel_info::proc::read_cmdline;

pub(super) fn read_comm(task_path: &Path) -> io::Result<String> {
    // /proc/<pid>/task/<tid>/comm 是内核字节序列，厂商线程名偶尔不是合法 UTF-8。
    // 有损字符转换可保留扫描流程，不让一个异常线程名跳过整个目标线程集合。
    let comm = fs::read(task_path.join("comm"))?;
    Ok(String::from_utf8_lossy(&comm).trim().to_string())
}

pub(super) fn read_proc_starttime(proc_or_task_path: &Path) -> io::Result<u64> {
    qixia_kernel_info::proc::read_stat(proc_or_task_path.join("stat")).map(|stat| stat.start)
}

pub(super) fn proc_thread_identity_matches(hit: &ProcHit, action: &ThreadAction) -> io::Result<bool> {
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

pub(super) fn process_belongs_to_uid_package(cmdline: &str, pkg: &str) -> bool {
    cmdline == pkg
        || cmdline
            .strip_prefix(pkg)
            .is_some_and(|rest| rest.starts_with(':'))
}
