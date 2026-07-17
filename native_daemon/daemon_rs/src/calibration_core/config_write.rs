// 校准规则写回 applist.conf。
//
// 写回必须非常保守：
// - 使用 applist.conf.lock，避免 App 设置页和 daemon 同时写配置。
// - 只替换目标应用对应的规则块，尽量保留其他应用、注释、原作者默认规则。
// - 临时文件写完后 rename，避免中途崩溃留下半个配置文件。
//
// 这里不要顺手“美化”整个配置文件，用户可能手写了分组注释和旧路径兼容规则。
fn write_rules_back(config_file: &Path, pkg: &str, rules: &[String]) -> bool {
    let _lock = match ConfigLock::acquire() {
        Some(lock) => lock,
        None => return false,
    };

    let old = match fs::read_to_string(config_file) {
        Ok(content) => content,
        Err(err) => {
            eprintln!(
                "[校准] 读取规则配置失败，已取消写回: {} err={err}",
                config_file.display()
            );
            return false;
        }
    };
    if !crate::rule_syntax::has_valid_block_structure(&old) {
        eprintln!("[校准] 规则配置中存在未闭合或格式错误的区块，已取消写回");
        return false;
    }
    let mut out = String::new();
    let mut block_target: Option<bool> = None;
    for raw in old.lines() {
        if let Some(target) = block_target {
            if !target {
                out.push_str(raw);
                out.push('\n');
            }
            if crate::rule_syntax::is_block_close(raw) {
                block_target = None;
            }
            continue;
        }

        let line = raw.trim();
        if let Some(owner) = crate::rule_syntax::block_header_owner(line) {
            let target = owner == pkg || owner.starts_with(&format!("{pkg}:"));
            if !target {
                out.push_str(raw);
                out.push('\n');
            }
            block_target = Some(target);
            continue;
        }
        if config_line_owner(line)
            .as_deref()
            .is_some_and(|owner| owner == pkg || owner.starts_with(&format!("{pkg}:")))
        {
            continue;
        }
        out.push_str(raw);
        out.push('\n');
    }
    if !out.ends_with("\n\n") {
        out.push('\n');
    }
    for rule in rules {
        out.push_str(rule);
        out.push('\n');
    }

    let tmp = config_file.with_extension("conf.rust.tmp");
    if fs::write(&tmp, out).is_err() {
        return false;
    }
    fs::rename(&tmp, config_file).is_ok()
}

fn config_line_owner(line: &str) -> Option<String> {
    if line.is_empty() || line.starts_with('#') {
        return None;
    }
    let left = line.split_once('=')?.0.trim();
    let owner = left.split_once('{').map_or(left, |(owner, _)| owner).trim();
    if owner.is_empty() {
        None
    } else {
        Some(owner.to_string())
    }
}

struct ConfigLock;

impl ConfigLock {
    fn acquire() -> Option<Self> {
        for _ in 0..80 {
            match fs::create_dir(CALIB_CONFIG_LOCK) {
                Ok(()) => return Some(Self),
                Err(err) if err.kind() == io::ErrorKind::AlreadyExists => {
                    if remove_stale_lock(CALIB_CONFIG_LOCK, true) {
                        eprintln!("[校准] 已清理过期规则配置锁: {CALIB_CONFIG_LOCK}");
                        continue;
                    }
                    thread::sleep(Duration::from_millis(50));
                }
                Err(_) => return None,
            }
        }
        None
    }
}

impl Drop for ConfigLock {
    fn drop(&mut self) {
        let _ = fs::remove_dir(CALIB_CONFIG_LOCK);
    }
}

struct PolicyLock;

impl PolicyLock {
    fn acquire() -> Option<Self> {
        for _ in 0..80 {
            match fs::create_dir(CALIB_POLICY_LOCK) {
                Ok(()) => return Some(Self),
                Err(err) if err.kind() == io::ErrorKind::AlreadyExists => {
                    if remove_stale_lock(CALIB_POLICY_LOCK, false) {
                        eprintln!("[校准策略] 已清理过期配置锁: {CALIB_POLICY_LOCK}");
                        continue;
                    }
                    thread::sleep(Duration::from_millis(50));
                }
                Err(_) => return None,
            }
        }
        None
    }
}

impl Drop for PolicyLock {
    fn drop(&mut self) {
        let _ = fs::remove_dir(CALIB_POLICY_LOCK);
    }
}

fn remove_stale_lock(path: &str, remove_owner: bool) -> bool {
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
