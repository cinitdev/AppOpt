use super::*;
// 仅用于同步已识别的拓扑元数据。
pub(super) struct PolicyLock;

impl PolicyLock {
    // 应用保存设置期间，后台补齐操作不能阻塞守护进程。
    pub(super) fn try_acquire() -> Option<Self> {
        match fs::create_dir(CALIB_POLICY_LOCK) {
            Ok(()) => Some(Self),
            Err(err) if err.kind() == io::ErrorKind::AlreadyExists => {
                if remove_stale_lock(CALIB_POLICY_LOCK, false) {
                    fs::create_dir(CALIB_POLICY_LOCK).ok().map(|_| Self)
                } else { None }
            }
            Err(_) => None,
        }
    }

}

impl Drop for PolicyLock {
    fn drop(&mut self) {
        let _ = fs::remove_dir(CALIB_POLICY_LOCK);
    }
}

pub(super) fn remove_stale_lock(path: &str, remove_owner: bool) -> bool {
    let Ok(metadata) = fs::metadata(path) else {
        return false;
    };
    let Ok(modified) = metadata.modified() else {
        return false;
    };
    let Ok(age) = SystemTime::now().duration_since(modified) else {
        return false;
    };
    if age <= Duration::from_secs(30) {
        return false;
    }
    if remove_owner {
        let _ = fs::remove_file(Path::new(path).join("owner"));
    }
    fs::remove_dir(path).is_ok()
}
