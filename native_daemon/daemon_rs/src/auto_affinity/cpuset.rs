//! 自动分配与静态规则共用配置的 cpuset，接管目录位于其下。
//! 控制器 IO 位于 affinity::cpuset，本模块仅负责租约和身份布局。
use crate::affinity::cpuset as shared;
use std::fs;
use std::io;
use std::sync::OnceLock;

static NAME: OnceLock<String> = OnceLock::new();
const CORE_CTL_ISOLATED: &str = "/sys/devices/system/cpu/core_ctl_isolated";

pub(super) use shared::{cpuset_path as path, valid_cpuset_relative_path as valid_path};

pub(super) fn configure(name: &str) -> io::Result<()> {
    let name = crate::entry::validate_cpuset_name(name).map_err(io::Error::other)?;
    if NAME.get().is_some_and(|current| current != &name) {
        return Err(io::Error::other("cpuset 名称变更需要先退出旧守护"));
    }
    let _ = NAME.set(name);
    Ok(())
}
fn name() -> &'static str { NAME.get().map_or(crate::DEFAULT_CPUSET_NAME, String::as_str) }
pub(super) fn root() -> String { format!("/{}/auto", name()) }
pub(super) fn valid_root(value: &str) -> bool {
    let Some(name) = value.strip_prefix('/').and_then(|v| v.strip_suffix("/auto")) else { return false; };
    crate::entry::validate_cpuset_name(name).is_ok_and(|normalized| normalized == name)
}
pub(super) fn mask(relative: &str) -> io::Result<u64> {
    shared::read_cpuset_mask_exact(relative)?.to_low64().filter(|mask| *mask != 0)
        .ok_or_else(|| io::Error::other("cpuset 没有有效核心或超出支持范围"))
}
pub(super) fn available(online: u64) -> io::Result<u64> {
    let parent = format!("/{}", name());
    let allowed = match fs::metadata(path(&parent)?) {
        Ok(_) => mask(&parent)?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => mask("/")?,
        Err(error) => return Err(error),
    };
    let available = schedulable(allowed, online, parked()?);
    if available == 0 { Err(io::Error::other("cpuset 没有可调度核心")) } else { Ok(available) }
}
fn schedulable(root: u64, online: u64, parked: u64) -> u64 {
    root & online & !parked
}
fn parse_parked(value: &str) -> io::Result<u64> {
    let value = value.trim();
    if value.is_empty() { return Ok(0); }
    // 此可选高通接口返回 CPU 列表，不是十六进制掩码。
    if value.split(',').any(|part| part.trim().is_empty()) {
        return Err(io::Error::other("核心停放列表无效"));
    }
    crate::CpuMask::parse(value).and_then(|mask| mask.to_low64())
        .ok_or_else(|| io::Error::other("核心停放列表无效或超出支持范围"))
}
pub(super) fn parked() -> io::Result<u64> {
    match fs::read_to_string(CORE_CTL_ISOLATED) {
        Ok(value) => parse_parked(&value),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(0),
        Err(error) => Err(io::Error::new(error.kind(), format!("核心停放状态不可读: {error}"))),
    }
}
pub(super) fn restored_mask_matches(requested: u64, actual: u64, parked: u64) -> bool {
    // 保留完整恢复请求，只允许内核过滤；回读范围变小时，
    // 必须由确实观察到的停核策略解释。
    requested & !parked != 0 && actual != 0
        && (actual == requested || actual == requested & !parked)
}
#[cfg(test)]
pub(super) fn assert_restored_for_test(requested: u64, actual: u64) {
    let parked = parked().unwrap();
    assert!(restored_mask_matches(requested, actual, parked),
        "restore readback: requested={requested:x} actual={actual:x} parked={parked:x}");
}
pub(super) fn owner(key: &(i32, i32, u64)) -> String {
    owner_at(&root(), key)
}
fn owner_at(root: &str, key: &(i32, i32, u64)) -> String {
    format!("{root}/{}-{}-{}", key.0, key.1, key.2)
}
pub(super) fn target(key: &(i32, i32, u64), mask: u64) -> String {
    target_at(&root(), key, mask)
}
pub(super) fn target_at(root: &str, key: &(i32, i32, u64), mask: u64) -> String {
    format!("{}/{}", owner_at(root, key), crate::CpuMask::from_low64(mask).to_list())
}
pub(super) fn identity(text: &str) -> Option<(i32, i32, u64)> {
    let mut key = text.split('-');
    let parsed: (i32, i32, u64) = (key.next()?.parse().ok()?, key.next()?.parse().ok()?, key.next()?.parse().ok()?);
    (key.next().is_none() && parsed.0 > 0 && parsed.1 > 0 && parsed.2 > 0).then_some(parsed)
}
pub(super) fn owned_root(group: &str) -> Option<&str> {
    if let Some(end) = group.strip_prefix('/').and_then(|tail| tail.find('/')).map(|end| end + 1) {
        let tail = &group[end..];
        if tail == "/auto" || tail.starts_with("/auto/") {
            let root = &group[..end + 5];
            if valid_root(root) { return Some(root); }
        }
    }
    None
}
pub(super) fn origin(group: &str) -> Option<(i32, i32, u64)> {
    let root = owned_root(group)?;
    let mut parts = group.strip_prefix(root)?.strip_prefix('/')?.split('/');
    let parsed = identity(parts.next()?)?;
    crate::CpuMask::parse(parts.next()?)?;
    parts.next().is_none().then_some(parsed)
}
pub(super) fn owned_path(value: &str) -> bool {
    owned_root(value).is_some()
}
pub(in crate::auto_affinity) fn current(pid: i32, tid: i32) -> io::Result<String> {
    let value = fs::read_to_string(format!("/proc/{pid}/task/{tid}/cpuset"))?;
    let value = value.trim();
    if !valid_path(value) { return Err(io::Error::other("线程 cpuset 不可识别")); }
    Ok(value.into())
}
pub(super) fn prepare(key: &(i32, i32, u64), requested: u64) -> io::Result<()> {
    let (base_cpus, mems) = shared::prepare_base_cpuset(name())?;
    let root_cpus = base_cpus.to_low64().ok_or_else(|| io::Error::other("cpuset 核心超出支持范围"))?;
    if requested == 0 || requested & !root_cpus != 0 {
        return Err(io::Error::other("建议核心超出 cpuset 根组范围"));
    }
    for (relative, cpus) in [(root(), root_cpus), (owner(key), root_cpus), (target(key, requested), requested)] {
        let dir = path(&relative)?;
        shared::ensure_cpuset_dir(&dir, &crate::CpuMask::from_low64(cpus).to_list(), &mems)?;
    }
    Ok(())
}
#[cfg(test)]
pub(super) fn groups(key: &(i32, i32, u64)) -> io::Result<Vec<String>> {
    groups_at(&root(), key)
}
pub(super) fn groups_at(root: &str, key: &(i32, i32, u64)) -> io::Result<Vec<String>> {
    if !valid_root(root) { return Err(io::Error::other("自动分配根组无效")); }
    let directory = match fs::read_dir(path(&owner_at(root, key))?) {
        Ok(directory) => directory,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    let mut groups = Vec::new();
    for entry in directory {
        let entry = entry?;
        if !entry.file_type()?.is_dir() { continue; }
        let name = entry.file_name();
        let name = name.to_str().ok_or_else(|| io::Error::other("专属组目录不可识别"))?;
        if crate::CpuMask::parse(name).is_none() { return Err(io::Error::other("专属组目录异常")); }
        groups.push(format!("{}/{name}", owner_at(root, key)));
        if groups.len() >= 256 { break; }
    }
    Ok(groups)
}
pub(super) fn members(group: &str) -> io::Result<Vec<i32>> {
    use std::io::Read;
    let mut text = String::new();
    fs::File::open(path(group)?.join("tasks"))?.take(128 * 1024).read_to_string(&mut text)?;
    if text.len() >= 128 * 1024 { return Err(io::Error::other("专属组成员过多")); }
    text.split_whitespace().map(|tid| tid.parse().map_err(|_| io::Error::other("专属组线程编号异常"))).collect()
}
pub(super) fn empty_and_cleanup(root: &str, key: &(i32, i32, u64)) -> io::Result<bool> {
    cleanup_empty_groups(root, key, None)?;
    if !groups_at(root, key)?.is_empty() { return Ok(false); }
    match fs::remove_dir(path(&owner_at(root, key))?) {
        Ok(()) => (), Err(error) if error.kind() == io::ErrorKind::NotFound => (), Err(error) => return Err(error),
    }
    Ok(true)
}
pub(super) fn cleanup_empty_groups(root: &str, key: &(i32, i32, u64), preserve: Option<&str>) -> io::Result<()> {
    let groups = groups_at(root, key)?;
    for group in groups {
        if preserve == Some(group.as_str()) || !members(&group)?.is_empty() { continue; }
        match fs::remove_dir(path(&group)?) {
            Ok(()) => (), Err(error) if error.kind() == io::ErrorKind::NotFound => (), Err(error) => return Err(error),
        }
    }
    Ok(())
}
pub(super) fn move_thread(tid: i32, relative: &str) -> io::Result<()> {
    shared::move_tid_to_existing_cpuset(tid, relative)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paths_cannot_escape_controller_and_owned_names_are_unambiguous() {
        for bad in ["relative", "/../x", "/x/./y", "/x\n", "/x\0"] { assert!(!valid_path(bad)); }
        assert!(valid_path("/top-app"));
        assert!(owned_path(&target(&(1, 2, 3), 0xc0)));
        assert_eq!(origin(&target(&(1, 2, 3), 0xc0)), Some((1, 2, 3)));
        assert!(origin("/QiXiaRs/auto/1-2-3/0/extra").is_none());
        assert!(!owned_path("/QiXiaRs/automatic/0-7"));
    }
    #[test]
    fn configured_roots_remain_distinct_from_rom_groups() {
        assert_eq!(root(), format!("/{}/auto", name()));
        for root in ["/GameCpu/auto", "/top-app/auto", "/QiXiaRs/auto"] {
            assert!(valid_root(root));
            let group = target_at(root, &(1, 2, 3), 0x30);
            assert_eq!(owned_root(&group), Some(root));
            assert_eq!(origin(&group), Some((1, 2, 3)));
        }
        for bad in ["/top-app", "/GameCpu/automatic", "/../auto", "/a/b/auto", "/GameCpu/auto/"] {
            assert!(!valid_root(bad), "{bad}");
        }
        assert!(!owned_path("/top-app/0-3"));
        assert!(!owned_path("/GameCpu/automatic/1-2-3/0"));
        assert!(origin("/GameCpu/auto/1-2-3/0/extra").is_none());
    }
    #[test]
    fn core_ctl_cpu_lists_distinguish_empty_parking_from_invalid_data() {
        assert_eq!(parse_parked("\n").unwrap(), 0);
        assert_eq!(parse_parked("4,7\n").unwrap(), 0x90);
        assert_eq!(parse_parked("0").unwrap(), 1);
        assert_eq!(parse_parked("4-7").unwrap(), 0xf0);
        for invalid in ["ff", "0x90", "7-4", "4,,7", "4,", "64", "-1"] {
            assert!(parse_parked(invalid).is_err(), "{invalid}");
        }
    }
    #[test]
    fn online_but_parked_cores_are_not_admitted() {
        assert_eq!(schedulable(0xff, 0xff, 0x90), 0x6f);
        assert_eq!(schedulable(0xff, 0x3f, 0x90), 0x2f);
        assert_eq!(schedulable(0x0f, 0xff, 0x90), 0x0f);
        assert_eq!(schedulable(0xff, 0xff, 0xff), 0);
        assert_eq!(schedulable(0xff, 0xff, 0), 0xff);
    }
    #[test]
    fn restore_accepts_only_exact_nonempty_parking_filter() {
        assert!(restored_mask_matches(0xff, 0x6f, 0x90));
        assert!(restored_mask_matches(0xff, 0xff, 0x90));
        assert!(!restored_mask_matches(0xff, 0x2f, 0x90));
        assert!(!restored_mask_matches(0xff, 0x7f, 0x90));
        assert!(!restored_mask_matches(0xff, 0x6f, 0));
        assert!(!restored_mask_matches(0xff, 0x1ff, 0));
        assert!(!restored_mask_matches(0x90, 0, 0x90));
        assert!(!restored_mask_matches(0x90, 0x90, 0x90));
    }
}
