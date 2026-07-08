// daemon 主流程共用的 procfs 小工具。
//
// 这些函数都保持“失败即返回 None/Err，由上层跳过”的风格。
// /proc 是瞬时视图，进程和线程随时可能退出，不能把读取失败当成严重错误。
fn parse_pid(file_name: &OsStr) -> Option<i32> {
    file_name.to_str()?.parse::<i32>().ok()
}

#[cfg(unix)]
fn metadata_uid(path: &Path) -> Option<u32> {
    fs::metadata(path).ok().map(|metadata| metadata.uid())
}

#[cfg(not(unix))]
fn metadata_uid(_path: &Path) -> Option<u32> {
    None
}

fn read_cmdline(pid: i32) -> io::Result<String> {
    let data = fs::read(format!("/proc/{pid}/cmdline"))?;
    let first = data.split(|byte| *byte == 0).next().unwrap_or_default();
    Ok(String::from_utf8_lossy(first).trim().to_string())
}

fn read_comm(task_path: &Path) -> io::Result<String> {
    let comm = fs::read_to_string(task_path.join("comm"))?;
    Ok(comm.trim().to_string())
}

fn process_belongs_to_uid_package(cmdline: &str, pkg: &str) -> bool {
    cmdline == pkg
        || cmdline
            .strip_prefix(pkg)
            .is_some_and(|rest| rest.starts_with(':'))
}
