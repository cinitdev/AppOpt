use super::mask::CpuMask;
use crate::DEFAULT_CPUSET_NAME;
use std::collections::HashSet;
#[cfg(unix)]
use std::ffi::CString;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::{fs, io};

const CPUSET_ROOT: &str = "/dev/cpuset";

pub(crate) fn controller() -> io::Result<()> {
    let root = Path::new(CPUSET_ROOT);
    if !root.join("tasks").exists() || !root.join("cpus").exists() {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "需要 /dev/cpuset v1 控制器",
        ));
    }
    Ok(())
}

pub(crate) fn cpuset_path(relative: &str) -> io::Result<PathBuf> {
    if !valid_cpuset_relative_path(relative) {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "无效 cpuset 路径"));
    }
    Ok(Path::new(CPUSET_ROOT).join(relative.trim_start_matches('/')))
}

fn read_cpuset_value(path: &Path, names: &[&str]) -> io::Result<String> {
    for name in names {
        match fs::read_to_string(path.join(name)) {
            Ok(value) => {
                let value = value.trim();
                if CpuMask::parse(value).is_none() {
                    return Err(io::Error::other("cpuset 节点范围无效或为空"));
                }
                return Ok(value.to_owned());
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => (),
            Err(error) => return Err(error),
        }
    }
    Err(io::Error::new(io::ErrorKind::NotFound, "cpuset 节点范围不可读"))
}

pub(crate) fn read_cpuset_mask_exact(relative: &str) -> io::Result<CpuMask> {
    controller()?;
    read_cpuset_mask_from(&cpuset_path(relative)?)
}

fn read_cpuset_mask_from(path: &Path) -> io::Result<CpuMask> {
    let value = read_cpuset_value(path, &["effective_cpus", "cpus.effective", "cpus"])?;
    CpuMask::parse(&value).ok_or_else(|| io::Error::other("cpuset 没有有效核心"))
}

fn read_cpuset_mems_from(path: &Path) -> io::Result<String> {
    read_cpuset_value(path, &["effective_mems", "mems.effective", "mems"])
}

pub(crate) fn prepare_base_cpuset(name: &str) -> io::Result<(CpuMask, String)> {
    let name = crate::entry::validate_cpuset_name(name)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    controller()?;
    prepare_base_at(Path::new(CPUSET_ROOT), &name)
}

fn prepare_base_at(root: &Path, name: &str) -> io::Result<(CpuMask, String)> {
    let base = root.join(name);
    // 已有自定义名称可能就是 ROM 的父组，不改写它的范围或权限。
    if base.exists() && name != DEFAULT_CPUSET_NAME {
        return Ok((read_cpuset_mask_from(&base)?, read_cpuset_mems_from(&base)?));
    }
    let mask = read_cpuset_mask_from(root)?;
    let mems = read_cpuset_mems_from(root)?;
    ensure_cpuset_dir(&base, &mask.to_list(), &mems)?;
    Ok((mask, mems))
}

pub(crate) fn read_existing_cpuset_mask(cpuset: &str) -> Option<CpuMask> {
    if !valid_cpuset_relative_path(cpuset) {
        return None;
    }
    let root = Path::new("/dev/cpuset");
    let mut path = root.join(cpuset.trim_start_matches('/'));
    loop {
        for name in ["effective_cpus", "cpus.effective", "cpus"] {
            if let Some(mask) = fs::read_to_string(path.join(name))
                .ok()
                .and_then(|value| CpuMask::parse(value.trim()))
            {
                return Some(mask);
            }
        }
        if path == root || !path.pop() || !path.starts_with(root) {
            return None;
        }
    }
}

pub(crate) fn read_online_cpu_mask() -> Option<CpuMask> {
    fs::read_to_string("/sys/devices/system/cpu/online")
        .ok()
        .and_then(|value| CpuMask::parse(value.trim()))
}

pub(crate) fn normalized_restore_cpuset(original: &str, cpuset_name: &str) -> String {
    normalized_restore_cpuset_with(original, cpuset_name, super::cpuset_owned::owns_restore_group)
}

pub(crate) fn normalized_owned_restore_cpuset(original: &str) -> String {
    if super::cpuset_owned::owns_restore_group(original) { "/".to_owned() } else { original.to_owned() }
}

fn normalized_restore_cpuset_with(original: &str, cpuset_name: &str, owned: impl FnOnce(&str) -> bool) -> String {
    // 继承自建组的新线程没有更早的 ROM 基线，退回根组；旧名字已写入恢复日志时也适用。
    // 已有 ROM 组没有创建凭据，仍保留其原父组语义。
    if owned(original) { return "/".to_owned(); }
    let mut parts = original
        .split('/')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    if parts.len() >= 2
        && parts[parts.len() - 2] == cpuset_name
        && CpuMask::parse(parts[parts.len() - 1]).is_some()
    {
        // 守护进程异常重启后可能只能看到上一次留下的 QixiaThreads 子组。至少退回父组，
        // 避免继续被旧 CPU 范围限制；自定义 cpuset 的父组仍保留 ROM 原有语义。
        if cpuset_name == DEFAULT_CPUSET_NAME {
            parts.clear();
        } else {
            parts.pop();
        }
    }
    if parts.is_empty() {
        "/".to_string()
    } else {
        format!("/{}", parts.join("/"))
    }
}

pub(crate) fn valid_cpuset_relative_path(path: &str) -> bool {
    path.starts_with('/')
        && path.len() <= 256
        && !path.bytes().any(|byte| byte == 0 || byte.is_ascii_control())
        && path
            .split('/')
            .all(|part| part.is_empty() || (part != "." && part != ".."))
}

pub(crate) fn move_tid_to_existing_cpuset(tid: i32, cpuset: &str) -> io::Result<()> {
    controller()?;
    write_tid_to_tasks(&cpuset_path(cpuset)?.join("tasks"), tid)
}

fn write_tid_to_tasks(path: &Path, tid: i32) -> io::Result<()> {
    let mut tasks = fs::OpenOptions::new().write(true).open(path)?;
    write_tid(&mut tasks, tid)
}

fn write_tid(tasks: &mut impl Write, tid: i32) -> io::Result<()> {
    // cgroup 将每次 write 作为一条命令。格式化写入会拆开数字和换行，
    // 导致线程已迁入后又因空命令返回 EINVAL。
    tasks.write_all(format!("{tid}\n").as_bytes())
}

pub(crate) fn move_tid_to_cpuset(
    tid: i32,
    mask: &CpuMask,
    base_cpuset: &Path,
    cpuset_name: &str,
    cache: &mut CpusetRoundCache,
) -> io::Result<()> {
    let cpuset_root = Path::new("/dev/cpuset");
    if !cpuset_root.exists() {
        return Ok(());
    }

    let cpus = mask.to_list();
    if cpus.is_empty() {
        return Ok(());
    }

    // 自定义名称可能指向 ROM 已有的 cpuset。已有自定义目录只作为父组使用，
    // 不改写其 cpus/mems/权限；QixiaThreads 自己的默认目录仍按旧逻辑维护。
    if cache.base_mems.is_none() {
        let (_, mems) = prepare_base_cpuset(cpuset_name)?;
        cache.base_mems = Some(mems);
    }

    let target = base_cpuset.join(&cpus);
    if !cache.ready_masks.contains(&cpus) {
        ensure_cpuset_dir(&target, &cpus, cache.base_mems.as_deref().unwrap())?;
        cache.ready_masks.insert(cpus.clone());
    }

    let tasks_path = target.join("tasks");
    let mut result = write_tid_to_tasks(&tasks_path, tid);
    if result
        .as_ref()
        .is_err_and(|err| err.kind() == io::ErrorKind::NotFound)
    {
        // ROM 可能在本轮中途清理自建 cpuset；清掉本轮缓存并重建一次。
        cache.ready_masks.remove(&cpus);
        if !base_cpuset.exists() {
            let (_, mems) = prepare_base_cpuset(cpuset_name)?;
            cache.base_mems = Some(mems);
        }
        ensure_cpuset_dir(&target, &cpus, cache.base_mems.as_deref().unwrap())?;
        cache.ready_masks.insert(cpus);
        result = write_tid_to_tasks(&tasks_path, tid);
    }
    result
}

#[derive(Default)]
pub(crate) struct CpusetRoundCache {
    base_mems: Option<String>,
    ready_masks: HashSet<String>,
}

pub(crate) fn read_present_cpus() -> Option<String> {
    fs::read_to_string("/sys/devices/system/cpu/present")
        .ok()
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
}

pub(crate) fn ensure_cpuset_dir(path: &Path, cpus: &str, mems: &str) -> io::Result<()> {
    super::cpuset_owned::create_directory(path)?;
    set_cpuset_dir_owner_mode(path);
    write_if_changed(&path.join("mems"), mems)?;
    write_if_changed(&path.join("cpus"), cpus)?;
    Ok(())
}

fn write_if_changed(path: &Path, value: &str) -> io::Result<()> {
    match fs::read_to_string(path) {
        Ok(current) if current.trim() == value => Ok(()),
        Ok(_) => fs::write(path, value),
        Err(error) if error.kind() == io::ErrorKind::NotFound => fs::write(path, value),
        Err(error) => Err(error),
    }
}

#[cfg(unix)]
pub(crate) fn set_cpuset_dir_owner_mode(path: &Path) {
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
pub(crate) fn set_cpuset_dir_owner_mode(_path: &Path) {}
