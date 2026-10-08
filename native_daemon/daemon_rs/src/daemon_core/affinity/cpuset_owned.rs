//! 只回收有创建凭据的空 cpuset。已有 ROM 组不收养，恢复基线引用的组始终保留。
use super::mask::CpuMask;
use std::{collections::{BTreeMap, BTreeSet}, fs, io::{self, Read, Write}, path::Path, sync::{Mutex, OnceLock}, time::Duration};

const MAGIC: &str = "qixia_cpuset_owned_v1";
const MAX_BYTES: usize = 256 * 1024;
const MAX_GROUPS: usize = 2048;
const ROOT: &str = "/dev/cpuset";
const STATE_NAME: &str = "cpuset_owned.tsv";
const AUTO_RESTORE_NAME: &str = "auto_affinity.restore";
static MUTATION: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Identity { device: u64, inode: u64 }

#[derive(Default)]
struct Registry { entries: BTreeMap<String, Identity> }

#[derive(Default)]
struct CleanupPass {
    changed: bool,
    retryable: BTreeSet<String>,
    last_error: Option<String>,
}

#[derive(Default)]
struct OwnershipCache {
    loaded: bool,
    entries: BTreeMap<String, Identity>,
}

impl OwnershipCache {
    fn replace(&mut self, registry: &Registry) {
        self.entries.clone_from(&registry.entries);
        self.loaded = true;
    }

    fn owns(&self, relative: &str, mut identity: impl FnMut(&str) -> io::Result<Identity>) -> bool {
        let Some(parts) = relative.strip_prefix('/') else { return false; };
        let mut parts = parts.split('/');
        let name = parts.next().unwrap_or_default();
        let tail = parts.next();
        if parts.next().is_some() || tail.is_some_and(|mask| CpuMask::parse(mask).is_none()) {
            return false;
        }
        let base = format!("/{name}");
        for path in std::iter::once(base.as_str()).chain((relative != base).then_some(relative)) {
            let Some(expected) = self.entries.get(path) else { return false; };
            // 只缓存创建凭据。实际接管/恢复前重新核对 inode，不能短暂认领被外部重建的同名组。
            if !identity(path).is_ok_and(|actual| actual == *expected) { return false; }
        }
        true
    }
}

fn ownership_cache() -> &'static Mutex<OwnershipCache> {
    static CACHE: OnceLock<Mutex<OwnershipCache>> = OnceLock::new();
    CACHE.get_or_init(Default::default)
}

pub(super) fn owns_restore_group(relative: &str) -> bool {
    let mut cache = ownership_cache().lock().unwrap_or_else(|error| error.into_inner());
    if !cache.loaded {
        // 只读加载一次。创建和回收会同步缓存，线程恢复不逐条读取登记文件。
        let registry = super::journal::read_managed_tid_boot_id()
            .and_then(|boot| Registry::load(&Path::new(crate::STATE_DIR).join(STATE_NAME), &boot));
        cache.loaded = true;
        if let Ok(registry) = registry { cache.replace(&registry); }
    }
    cache.owns(relative, |path|
        directory_identity(&Path::new(ROOT).join(path.trim_start_matches('/'))))
}

fn valid_relative(relative: &str) -> bool {
    let Some(relative) = relative.strip_prefix('/') else { return false; };
    let mut parts = relative.split('/');
    let name = parts.next().unwrap_or_default();
    if !crate::entry::validate_cpuset_name(name).is_ok_and(|value| value == name) { return false; }
    match (parts.next(), parts.next()) {
        (None, None) => true,
        (Some("auto"), None) => true,
        (Some(mask), None) => CpuMask::parse(mask).is_some_and(|value| value.to_list() == mask),
        _ => false,
    }
}

impl Registry {
    fn parse(text: &str, boot: &str) -> io::Result<Self> {
        let invalid = || io::Error::new(io::ErrorKind::InvalidData, "cpuset 创建登记无效");
        if text.len() > MAX_BYTES { return Err(invalid()); }
        let mut lines = text.lines();
        let (magic, stored_boot) = lines.next().and_then(|line| line.split_once('\t')).ok_or_else(invalid)?;
        if magic != MAGIC || stored_boot.is_empty() || stored_boot.chars().any(char::is_whitespace) {
            return Err(invalid());
        }
        // cgroup 目录不跨设备重启存在，旧 boot 的 inode 不能用作所有权证据。
        if stored_boot != boot { return Ok(Self::default()); }
        let mut registry = Self::default();
        for line in lines {
            let mut fields = line.split('\t');
            let relative = fields.next().ok_or_else(invalid)?;
            let device = fields.next().and_then(|value| value.parse().ok()).ok_or_else(invalid)?;
            let inode = fields.next().and_then(|value| value.parse().ok()).ok_or_else(invalid)?;
            if fields.next().is_some() || !valid_relative(relative) || inode == 0
                || registry.entries.len() >= MAX_GROUPS
                || registry.entries.insert(relative.to_owned(), Identity { device, inode }).is_some() {
                return Err(invalid());
            }
        }
        Ok(registry)
    }

    fn load(path: &Path, boot: &str) -> io::Result<Self> {
        let file = match fs::File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Self::default()),
            Err(error) => return Err(error),
        };
        let mut text = String::new();
        file.take(MAX_BYTES as u64 + 1).read_to_string(&mut text)?;
        Self::parse(&text, boot)
    }

    fn save(&self, path: &Path, boot: &str) -> io::Result<()> {
        let mut text = format!("{MAGIC}\t{boot}\n");
        for (relative, identity) in &self.entries {
            text.push_str(&format!("{relative}\t{}\t{}\n", identity.device, identity.inode));
        }
        if text.len() > MAX_BYTES { return Err(io::Error::other("cpuset 创建登记过大")); }
        let temporary = path.with_extension("tmp");
        let result = (|| {
            let mut options = fs::OpenOptions::new();
            options.create(true).truncate(true).write(true);
            #[cfg(unix)] {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(&temporary)?;
            file.write_all(text.as_bytes())?;
            file.sync_all()?;
            drop(file);
            fs::rename(&temporary, path)?;
            #[cfg(unix)]
            fs::File::open(path.parent().ok_or_else(|| io::Error::other("登记目录无效"))?)?.sync_all()?;
            Ok(())
        })();
        if result.is_err() { let _ = fs::remove_file(&temporary); }
        result
    }

    fn create(
        &mut self, path: &Path, relative: &str, identity: impl FnOnce(&Path) -> io::Result<Identity>,
        persist: impl FnOnce(&Self) -> io::Result<()>,
    ) -> io::Result<()> {
        if self.entries.len() >= MAX_GROUPS && !self.entries.contains_key(relative) {
            return Err(io::Error::other("cpuset 创建登记已满"));
        }
        match fs::create_dir(path) {
            Ok(()) => (),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => return existing_directory(path),
            Err(error) => return Err(error),
        }
        let previous = self.entries.get(relative).copied();
        let result = identity(path).and_then(|identity| {
            self.entries.insert(relative.to_owned(), identity);
            persist(self)
        });
        if result.is_err() {
            match previous {
                Some(identity) => { self.entries.insert(relative.to_owned(), identity); }
                None => { self.entries.remove(relative); }
            }
            // 凭据未落盘时还没有迁入线程，只尝试 rmdir；失败也不能递归清理未知内容。
            let _ = fs::remove_dir(path);
        }
        result
    }

    #[cfg(test)]
    fn cleanup(
        &mut self, root: &Path, blocked: bool,
        identity: impl FnMut(&Path) -> io::Result<Identity>,
        remove: impl FnMut(&Path) -> io::Result<()>,
    ) -> bool {
        self.cleanup_pass(root, blocked, None, identity, remove).changed
    }

    fn cleanup_pass(
        &mut self, root: &Path, blocked: bool, selected: Option<&BTreeSet<String>>,
        identity: impl FnMut(&Path) -> io::Result<Identity>,
        remove: impl FnMut(&Path) -> io::Result<()>,
    ) -> CleanupPass {
        if blocked { return CleanupPass::default(); }
        let paths = self.entries.keys().filter(|relative| selected.is_none_or(|paths|
            paths.contains(*relative) || paths.iter().any(|child| child.starts_with(&format!("{relative}/")))))
            .cloned().collect::<Vec<_>>();
        self.cleanup_paths(root, paths, identity, remove)
    }

    fn old_paths(&self, current_name: &str) -> Vec<String> {
        let current = format!("/{current_name}");
        self.entries.keys().filter(|path| *path != &current && !path.starts_with(&format!("{current}/")))
            .cloned().collect()
    }

    fn unreferenced_old_paths(&self, current_name: &str, references: &BTreeSet<String>) -> Vec<String> {
        self.old_paths(current_name).into_iter().filter(|path| !protects_path(references, path)).collect()
    }

    fn cleanup_paths(
        &mut self, root: &Path, mut paths: Vec<String>,
        mut identity: impl FnMut(&Path) -> io::Result<Identity>,
        mut remove: impl FnMut(&Path) -> io::Result<()>,
    ) -> CleanupPass {
        let mut result = CleanupPass::default();
        paths.sort_by_key(|path| std::cmp::Reverse(path.matches('/').count()));
        for relative in paths {
            let path = root.join(relative.trim_start_matches('/'));
            match identity(&path) {
                Ok(actual) if actual == self.entries[&relative] => {
                    // 内核 rmdir 会拒绝仍有线程或子组的 cgroup；不移动外部线程，也不递归删除。
                    match remove(&path) {
                        Ok(()) => { self.entries.remove(&relative); result.changed = true; }
                        Err(error) if error.kind() == io::ErrorKind::NotFound => {
                            self.entries.remove(&relative); result.changed = true;
                        }
                        Err(error) => {
                            if (error.raw_os_error() == Some(16) || error.kind() == io::ErrorKind::DirectoryNotEmpty)
                                && empty_leaf(&path) {
                                result.retryable.insert(relative.clone());
                            }
                            result.last_error = Some(format!("{relative}: {error}"));
                        }
                    }
                }
                Ok(_) => { self.entries.remove(&relative); result.changed = true; }
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    self.entries.remove(&relative); result.changed = true;
                }
                Err(error) => { result.last_error = Some(format!("{relative}: {error}")); }
            }
        }
        result
    }
}

fn empty_leaf(path: &Path) -> bool {
    let empty_tasks = fs::File::open(path.join("tasks"))
        .and_then(|mut file| file.read(&mut [0u8; 1])).is_ok_and(|count| count == 0);
    empty_tasks && fs::read_dir(path).is_ok_and(|entries| entries.into_iter().all(|entry|
        entry.and_then(|entry| entry.file_type()).is_ok_and(|kind| !kind.is_dir() && !kind.is_symlink())))
}

fn bounded_cleanup(
    mut safe: impl FnMut() -> bool,
    mut sweep: impl FnMut(Option<&BTreeSet<String>>) -> CleanupPass,
    mut pause: impl FnMut(Duration),
) -> CleanupPass {
    let mut result = CleanupPass::default();
    let mut selected = None;
    for delay_ms in [0, 100, 250, 500] {
        if !safe() { break; }
        if delay_ms > 0 { pause(Duration::from_millis(delay_ms)); }
        if !safe() { break; }
        let pass = sweep(selected.as_ref());
        result.changed |= pass.changed;
        result.last_error = pass.last_error;
        if pass.retryable.is_empty() { break; }
        selected = Some(pass.retryable);
    }
    result
}

fn existing_directory(path: &Path) -> io::Result<()> {
    if fs::symlink_metadata(path)?.is_dir() { Ok(()) }
    else { Err(io::Error::other("cpuset 路径不是目录")) }
}

#[cfg(unix)]
fn directory_identity(path: &Path) -> io::Result<Identity> {
    use std::os::unix::fs::MetadataExt;
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_dir() { return Err(io::Error::other("cpuset 路径不是目录")); }
    Ok(Identity { device: metadata.dev(), inode: metadata.ino() })
}

#[cfg(not(unix))]
fn directory_identity(_path: &Path) -> io::Result<Identity> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "cpuset 目录身份需要 Unix inode"))
}

fn with_registry<T>(operation: impl FnOnce(&mut Registry, &Path, &str) -> io::Result<T>) -> io::Result<T> {
    let _guard = MUTATION.lock().unwrap_or_else(|error| error.into_inner());
    let state = Path::new(crate::STATE_DIR);
    fs::create_dir_all(state)?;
    // 锁稳定的 state 目录 inode，避免 rename 登记文件后锁失效，也不新增每组锁文件。
    #[cfg(unix)]
    let _directory_lock = DirectoryLock::lock(state)?;
    let boot = super::journal::read_managed_tid_boot_id()?;
    let path = state.join(STATE_NAME);
    let mut registry = Registry::load(&path, &boot)?;
    let result = operation(&mut registry, &path, &boot);
    ownership_cache().lock().unwrap_or_else(|error| error.into_inner()).replace(&registry);
    result
}

#[cfg(unix)]
struct DirectoryLock(fs::File);
#[cfg(unix)]
impl DirectoryLock {
    fn lock(path: &Path) -> io::Result<Self> {
        use std::os::fd::AsRawFd;
        let file = fs::File::open(path)?;
        loop {
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 { return Ok(Self(file)); }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted { return Err(error); }
        }
    }
}
#[cfg(unix)]
impl Drop for DirectoryLock {
    fn drop(&mut self) {
        use std::os::fd::AsRawFd;
        unsafe { libc::flock(self.0.as_raw_fd(), libc::LOCK_UN); }
    }
}

pub(super) fn create_directory(path: &Path) -> io::Result<()> {
    let relative = path.strip_prefix(ROOT).ok().and_then(|relative| relative.to_str())
        .map(|relative| format!("/{}", relative.replace('\\', "/")))
        .filter(|relative| valid_relative(relative));
    let Some(relative) = relative else { return fs::create_dir_all(path); };
    match fs::symlink_metadata(path) {
        Ok(_) => return existing_directory(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => (),
        Err(error) => return Err(error),
    }
    with_registry(|registry, state, boot| registry.create(path, &relative, directory_identity,
        |registry| registry.save(state, boot)))
}

fn restore_journals_absent(static_journal: &Path, auto_journal: &Path) -> bool {
    [static_journal, auto_journal].into_iter().all(|path|
        fs::symlink_metadata(path).is_err_and(|error| error.kind() == io::ErrorKind::NotFound))
}

fn static_restore_paths(path: &Path, boot: &str, current_name: &str) -> io::Result<BTreeSet<String>> {
    let file = match fs::File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(BTreeSet::new()),
        Err(error) => return Err(error),
    };
    let mut content = String::new();
    const LIMIT: u64 = 32 * 1024 * 1024;
    file.take(LIMIT + 1).read_to_string(&mut content)?;
    if content.len() as u64 > LIMIT { return Err(io::Error::other("线程恢复基线过大，暂缓回收旧组")); }
    Ok(super::journal_format::parse_managed_tid_journal(&content, boot, current_name)?
        .into_values().filter_map(|entry| entry.original_cpuset).collect())
}

fn protects_path(references: &BTreeSet<String>, candidate: &str) -> bool {
    // 保护原路径及祖先；原目标为 / 不代表必须保留所有空子组。
    references.iter().any(|reference| reference == candidate || reference.starts_with(&format!("{candidate}/")))
}

fn after_scan_attempt(
    attempts: &mut u8, complete: bool, cleanup: impl FnOnce() -> io::Result<bool>,
) -> io::Result<()> {
    if !complete || *attempts >= 3 { return Ok(()); }
    *attempts += 1;
    if !cleanup()? { *attempts = 3; }
    Ok(())
}

/// 在现有自动分配锁内、实际完整扫描和静态应用之后调用，不休眠也不增加唤醒。
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn cleanup_after_scan(state: &mut crate::DaemonState, current_name: &str, complete: bool) -> io::Result<()> {
    let complete = complete && state.managed_tid_journal_loaded
        && state.managed_tid_quarantine_before_starttime.is_none();
    after_scan_attempt(&mut state.owned_cpuset_cleanup_attempts, complete, || {
        with_registry(|registry, storage, boot| {
            let old = registry.old_paths(current_name);
            if old.is_empty() { return Ok(false); }
            // 保留原始基线，不能只保护归一后的 /；删掉原组会同时失去 inode 归属证据。
            let mut references = static_restore_paths(Path::new(crate::MANAGED_TID_STATE_FILE), boot, current_name)?;
            references.extend(state.managed_tids.values().filter_map(|entry| entry.original_cpuset.clone()));
            references.extend(crate::auto_affinity::platform::recovery_cpuset_paths()?);
            let candidates = registry.unreferenced_old_paths(current_name, &references);
            let before = registry.entries.len();
            let result = registry.cleanup_paths(Path::new(ROOT), candidates, directory_identity, |path| fs::remove_dir(path));
            if result.changed { registry.save(storage, boot)?; }
            let remaining = registry.old_paths(current_name).len();
            log_info!("[RS] 扫描后 cpuset 旧组回收: 已清理登记={} 保留旧组={}{}", before - registry.entries.len(),
                remaining, result.last_error.map(|error| format!(" 最后原因={error}")).unwrap_or_default());
            Ok(remaining > 0)
        })
    })
}

/// 仅在工作线程启动前，或全部退出并保存恢复日志后调用。
pub(crate) fn cleanup() -> io::Result<()> {
    let auto_journal = Path::new(crate::STATE_DIR).join(AUTO_RESTORE_NAME);
    if !restore_journals_absent(Path::new(crate::MANAGED_TID_STATE_FILE), &auto_journal) { return Ok(()); }
    with_registry(|registry, state, boot| {
        let before = registry.entries.len();
        let result = bounded_cleanup(
            || restore_journals_absent(Path::new(crate::MANAGED_TID_STATE_FILE), &auto_journal),
            |selected| registry.cleanup_pass(Path::new(ROOT), false, selected, directory_identity,
                |path| fs::remove_dir(path)),
            std::thread::sleep,
        );
        if result.changed {
            registry.save(state, boot)?;
        }
        if before > 0 {
            log_info!("[RS] cpuset 空组回收: 已清理登记={} 保留={}{}", before - registry.entries.len(),
                registry.entries.len(), result.last_error.map(|error| format!(" 最后原因={error}")).unwrap_or_default());
        }
        Ok(())
    })
}

#[cfg(test)]
#[path = "cpuset_owned_tests.rs"]
mod tests;
