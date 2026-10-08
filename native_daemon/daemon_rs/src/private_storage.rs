//! App 私有采集收件目录；解析路径时不创建目录，也不回退到模块目录。
use std::{fs, io, path::{Path, PathBuf}};

pub(crate) const REGISTRATION: &str = "/data/adb/modules/QixiaThreads/config/state/app_storage.conf";

#[derive(Clone)]
pub(crate) struct Storage {
    files: PathBuf,
    uid: u32,
}

fn parse(text: &str) -> io::Result<Storage> {
    let invalid = || io::Error::new(io::ErrorKind::InvalidData, "invalid app storage registration");
    let mut fields = std::collections::BTreeMap::new();
    for line in text.lines() {
        let (key, value) = line.split_once('=').ok_or_else(invalid)?;
        if fields.insert(key, value).is_some() { return Err(invalid()); }
    }
    if fields.get("version") != Some(&"1") { return Err(invalid()); }
    let uid: u32 = fields.get("uid").ok_or_else(invalid)?.parse().map_err(|_| invalid())?;
    if uid % 100_000 < 10_000 { return Err(invalid()); }
    let files = *fields.get("files").ok_or_else(invalid)?;
    // 注册信息由 App 提供，不能根据用户 0 或前台游戏的 UID 推断。
    let expected = format!("/data/user/{}/top.qixia.threads/files", uid / 100_000);
    let adopted_suffix = format!("/user/{}/top.qixia.threads/files", uid / 100_000);
    let adopted = files.strip_prefix("/mnt/expand/").is_some_and(|rest| {
        rest.split_once('/').is_some_and(|(volume, suffix)| {
            !volume.is_empty() && volume.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
                && format!("/{suffix}") == adopted_suffix
        })
    });
    let primary_alias = uid / 100_000 == 0 && files == "/data/data/top.qixia.threads/files";
    if files != expected && !adopted && !primary_alias { return Err(invalid()); }
    Ok(Storage { files: files.into(), uid })
}

pub(crate) fn resolve() -> io::Result<Storage> {
    use std::io::Read;
    let mut text = String::new();
    fs::File::open(REGISTRATION)?.take(4097).read_to_string(&mut text)?;
    if text.len() > 4096 { return Err(io::Error::other("storage registration too large")); }
    let storage = parse(&text)?;
    storage.validate()?;
    Ok(storage)
}

impl Storage {
    pub(crate) fn read_recent_usage(&self, limit: usize) -> io::Result<String> {
        use std::io::Read;
        self.validate()?;
        let root = self.files.join("capture");
        if !fs::symlink_metadata(&root)?.is_dir() { return Err(io::Error::other("invalid capture root")); }
        let path = root.join("recent_usage.tsv");
        if !fs::symlink_metadata(&path)?.is_file() { return Err(io::Error::other("invalid recent usage file")); }
        let mut text = String::new();
        fs::File::open(path)?.take(limit as u64 + 1).read_to_string(&mut text)?;
        if text.len() > limit { return Err(io::Error::new(io::ErrorKind::InvalidData, "recent usage file too large")); }
        Ok(text)
    }

    pub(crate) fn write_recent_usage(&self, data: &[u8]) -> io::Result<()> {
        if data.len() > crate::recent_usage::MAX_BYTES { return Err(io::Error::new(io::ErrorKind::InvalidInput, "recent usage file too large")); }
        let root = self.prepare_root()?;
        atomic_recent_usage(&root, data, |file| {
            #[cfg(unix)] {
                use std::os::fd::AsRawFd;
                if unsafe { libc::fchown(file.as_raw_fd(), self.uid, self.uid) } != 0
                    || unsafe { libc::fchmod(file.as_raw_fd(), 0o600) } != 0 { return Err(io::Error::last_os_error()); }
                #[cfg(target_os = "android")] {
                    use std::{ffi::CString, os::unix::ffi::OsStrExt};
                    let parent = CString::new(self.files.as_os_str().as_bytes())?;
                    let mut label = [0u8; 512];
                    let size = unsafe { libc::getxattr(parent.as_ptr(), c"security.selinux".as_ptr(), label.as_mut_ptr().cast(), label.len()) };
                    if size <= 0 { return Err(io::Error::last_os_error()); }
                    if unsafe { libc::fsetxattr(file.as_raw_fd(), c"security.selinux".as_ptr(), label.as_ptr().cast(), size as usize, 0) } != 0 {
                        return Err(io::Error::last_os_error());
                    }
                }
            }
            #[cfg(not(unix))]
            let _ = file;
            Ok(())
        })
    }

    fn validate(&self) -> io::Result<()> {
        let meta = fs::symlink_metadata(&self.files)?;
        let canonical = fs::canonicalize(&self.files)?;
        if !meta.is_dir() || parse(&format!("version=1\nuid={}\nfiles={}\n", self.uid, canonical.display())).is_err() {
            return Err(io::Error::other("app data is unavailable or redirected"));
        }
        #[cfg(unix)] {
            use std::os::unix::fs::MetadataExt;
            if meta.uid() != self.uid { return Err(io::Error::other("app storage UID changed")); }
        }
        Ok(())
    }

    pub(crate) fn directory(&self, name: &str) -> PathBuf { self.files.join("capture").join(name) }

    pub(crate) fn prepare_root(&self) -> io::Result<PathBuf> {
        self.validate()?;
        let root = self.files.join("capture");
        self.ensure_directory(&root)?;
        Ok(root)
    }

    pub(crate) fn prepare(&self, name: &str) -> io::Result<PathBuf> {
        if !matches!(name, "history" | "auto_history" | "calibration_drafts") {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "unknown collection directory"));
        }
        self.prepare_root()?;
        let path = self.directory(name);
        self.ensure_directory(&path)?;
        Ok(path)
    }

    fn ensure_directory(&self, path: &Path) -> io::Result<()> {
        match fs::create_dir(path) {
            Ok(()) => (),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => (),
            Err(error) => return Err(error),
        }
        if !fs::symlink_metadata(path)?.is_dir() { return Err(io::Error::other("invalid collection directory")); }
        #[cfg(unix)] {
            use std::{ffi::CString, os::unix::{ffi::OsStrExt, fs::PermissionsExt}};
            let cpath = CString::new(path.as_os_str().as_bytes())?;
            if unsafe { libc::chown(cpath.as_ptr(), self.uid, self.uid) } != 0 {
                return Err(io::Error::last_os_error());
            }
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
            #[cfg(target_os = "android")] {
                // 保留 App 的 SELinux 类别集合，避免生成带模块标签的数据。
                let parent = CString::new(self.files.as_os_str().as_bytes())?;
                let mut label = [0u8; 512];
                let size = unsafe { libc::getxattr(parent.as_ptr(), c"security.selinux".as_ptr(), label.as_mut_ptr().cast(), label.len()) };
                if size <= 0 { return Err(io::Error::last_os_error()); }
                if unsafe { libc::setxattr(cpath.as_ptr(), c"security.selinux".as_ptr(), label.as_ptr().cast(), size as usize, 0) } != 0 {
                    return Err(io::Error::last_os_error());
                }
            }
        }
        Ok(())
    }
}

fn atomic_recent_usage(root: &Path, data: &[u8], prepare: impl FnOnce(&fs::File) -> io::Result<()>) -> io::Result<()> {
    use std::io::Write;
    let temporary = root.join(".recent_usage.tsv.tmp");
    // 只有当前守护写入方拥有此固定临时文件名。上次崩溃可能留下文件，
    // 但 create_new 仍能防止跟随外部插入的符号链接。
    match fs::symlink_metadata(&temporary) {
        Ok(meta) if meta.is_file() => fs::remove_file(&temporary)?,
        Ok(_) => return Err(io::Error::other("invalid recent usage temporary file")),
        Err(error) if error.kind() == io::ErrorKind::NotFound => (),
        Err(error) => return Err(error),
    }
    let mut file = fs::OpenOptions::new().write(true).create_new(true).open(&temporary)?;
    let result = (|| {
        prepare(&file)?;
        file.write_all(data)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, root.join("recent_usage.tsv"))?;
        #[cfg(unix)]
        fs::File::open(root)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() { let _ = fs::remove_file(temporary); }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn atomic_summary_replacement_keeps_old_file_on_prepare_failure() {
        let root = std::env::temp_dir().join(format!("qixia-summary-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        fs::create_dir(&root).unwrap();
        let path = root.join("recent_usage.tsv");
        atomic_recent_usage(&root, b"old", |_| Ok(())).unwrap();
        assert!(atomic_recent_usage(&root, b"partial", |_| Err(io::Error::other("label denied"))).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"old");
        assert!(!root.join(".recent_usage.tsv.tmp").exists());
        atomic_recent_usage(&root, b"complete", |_| Ok(())).unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"complete");
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_file(path).unwrap();
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn storage_is_scoped_to_registered_app_and_user() {
        for (uid, path) in [(10123, "/data/user/0/top.qixia.threads/files"), (10123, "/data/data/top.qixia.threads/files"), (1010123, "/data/user/10/top.qixia.threads/files"),
            (10123, "/mnt/expand/abcd-1234/user/0/top.qixia.threads/files")] {
            assert!(parse(&format!("version=1\nuid={uid}\nfiles={path}\n")).is_ok());
        }
        for path in ["/data/user/10/top.qixia.threads/files", "/data/user/0/other.app/files", "/data/user/0/top.qixia.threads/files/../databases", "/data/adb/modules/QixiaThreads"] {
            assert!(parse(&format!("version=1\nuid=10123\nfiles={path}\n")).is_err());
        }
        assert!(parse("version=1\nversion=1\nuid=10123\nfiles=/data/user/0/top.qixia.threads/files\n").is_err());
        assert!(parse("version=1\nuid=0\nfiles=/data/user/0/top.qixia.threads/files\n").is_err());
    }
}
