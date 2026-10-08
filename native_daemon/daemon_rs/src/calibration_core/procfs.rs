use super::*;
// 校准模块专用 procfs 工具。
//
// 校准和常驻绑核扫描的目标不同：
// - 常驻扫描只关心规则命中后该把线程绑到哪里。
// - 校准扫描要收集主进程/子进程 CPU 使用率，所以会读取 stat 的 utime/stime。
//
// /proc 读取失败很常见：进程可能刚退出，线程可能刚结束。这里统一选择跳过，不把它当异常。
pub(super) fn collect_pkg_processes(pkg: &str) -> Vec<ProcInfo> {
    crate::package_processes::discover(pkg, [], false).into_iter()
        .map(|(pid, owner)| ProcInfo { pid, owner }).collect()
}
pub(super) fn write_state(state: &str) -> io::Result<()> {
    fs::create_dir_all(CONFIG_DIR)?;
    fs::write(CALIB_STATE_FILE, state)
}



pub(super) fn read_thread_stat(path: &str) -> Option<(String, u64, u64)> {
    let stat = qixia_kernel_info::proc::read_stat(path).ok()?;
    Some((stat.name, stat.ticks, stat.start))
}

#[cfg(test)]
pub(super) fn parse_thread_stat(text: &str) -> Option<(String, u64, u64)> {
    let stat = qixia_kernel_info::proc::parse_stat(text.as_bytes())?;
    Some((stat.name, stat.ticks, stat.start))
}
pub(super) fn parse_pid_text(text: &str) -> Option<i32> {
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

pub(super) fn safe_file_name(name: &str) -> String {
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

pub(super) fn safe_history_name(name: &str) -> String {
    // e1 明确标记可逆格式；旧历史没有此前缀，App 可继续按原样读取。
    // 仅保留安全可见 ASCII，其余 UTF-8 字节统一百分号编码，避免 | , ; 和控制字符
    // 破坏主行/子线程详情的分隔结构，也不会把真实线程名永久改成下划线。
    let mut encoded = String::with_capacity(name.len().saturating_add(3));
    encoded.push_str("e1:");
    for byte in name.as_bytes() {
        if (0x20..=0x7e).contains(byte) && !matches!(*byte, b'%' | b'|' | b',' | b';') {
            encoded.push(*byte as char);
        } else {
            const HEX: &[u8; 16] = b"0123456789ABCDEF";
            encoded.push('%');
            encoded.push(HEX[(byte >> 4) as usize] as char);
            encoded.push(HEX[(byte & 0x0f) as usize] as char);
        }
    }
    encoded
}

#[cfg(test)]
mod history_name_tests {
    use super::{parse_thread_stat, safe_history_name};

    pub(super) fn stat_line(name: &str) -> String {
        format!(
            "123 ({name}) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20"
        )
    }

    #[test]
    pub(super) fn thread_stat_reuses_comm_and_cpu_fields() {
        let (name, ticks, starttime) = parse_thread_stat(&stat_line("RenderThread")).unwrap();
        assert_eq!(name, "RenderThread");
        assert_eq!(ticks, 23);
        assert_eq!(starttime, 19);
    }

    #[test]
    pub(super) fn thread_stat_accepts_spaces_and_closing_parentheses_in_name() {
        let (name, ticks, starttime) =
            parse_thread_stat(&stat_line("Render ) Thread 2")).unwrap();
        assert_eq!(name, "Render ) Thread 2");
        assert_eq!(ticks, 23);
        assert_eq!(starttime, 19);
    }

    #[test]
    pub(super) fn thread_stat_rejects_empty_or_incomplete_input() {
        assert!(parse_thread_stat("123 () S 1 2 3").is_none());
        assert!(parse_thread_stat("123 RenderThread S 1 2 3").is_none());
        assert!(parse_thread_stat("123 (RenderThread) S 1 2 3").is_none());
    }

    #[test]
    pub(super) fn history_name_uses_versioned_reversible_encoding() {
        assert_eq!(safe_history_name("RenderThread"), "e1:RenderThread");
        assert_eq!(safe_history_name("a|b,c;d%"), "e1:a%7Cb%2Cc%3Bd%25");
        assert_eq!(safe_history_name("线程"), "e1:%E7%BA%BF%E7%A8%8B");
    }
}
