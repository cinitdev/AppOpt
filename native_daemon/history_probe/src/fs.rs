use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
};

/// 所有路径均为固定内核接口；替代根目录仅用于测试样例。
pub struct KernelFs(pub PathBuf);
impl KernelFs {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self(root.into())
    }
    pub fn path(&self, path: &str) -> PathBuf {
        self.0.join(path.trim_start_matches('/'))
    }
    pub fn read(&self, path: &str) -> Option<String> {
        self.read_limit(path, 8192)
    }
    pub fn read_limit(&self, path: &str, limit: usize) -> Option<String> {
        let mut options = fs::OpenOptions::new();
        options.read(true);
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC);
        }
        let mut value = String::new();
        options
            .open(self.path(path))
            .ok()?
            .take(limit as u64 + 1)
            .read_to_string(&mut value)
            .ok()?;
        (value.len() <= limit).then(|| value.trim().to_owned())
    }
    pub fn children(&self, dir: &str, limit: usize) -> Vec<String> {
        let Some(entries) = fs::read_dir(self.path(dir)).ok() else {
            return vec![];
        };
        let mut paths: Vec<_> = entries
            .take(1024)
            .filter_map(Result::ok)
            .filter_map(|entry| {
                let name = entry.file_name().into_string().ok()?;
                Some(format!("{dir}/{name}"))
            })
            .collect();
        paths.sort();
        paths.truncate(limit);
        paths
    }
    pub fn exists(&self, path: &str) -> bool {
        self.path(path).exists()
    }
}
pub fn number(value: &str) -> Option<f64> {
    value.trim().parse::<f64>().ok().filter(|v| v.is_finite())
}
pub fn name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}
pub fn cpu_ids(value: &str) -> Vec<u32> {
    let mut ids = Vec::new();
    for part in value
        .split(|c: char| c.is_whitespace() || c == ',')
        .filter(|s| !s.is_empty())
    {
        let mut range = part.split('-');
        let Some(first) = range.next().and_then(|s| s.parse::<u32>().ok()) else {
            return vec![];
        };
        let last = match range.next() {
            Some(s) => match s.parse::<u32>() {
                Ok(v) => v,
                _ => return vec![],
            },
            None => first,
        };
        if range.next().is_some() || first > last || last >= 64 {
            return vec![];
        }
        ids.extend(first..=last);
    }
    ids.sort_unstable();
    ids.dedup();
    ids
}
pub fn ids_key(ids: &[u32]) -> String {
    ids.iter().map(u32::to_string).collect::<Vec<_>>().join("_")
}
pub fn canonical(path: &Path) -> PathBuf {
    fs::canonicalize(path).unwrap_or_else(|_| path.to_owned())
}
