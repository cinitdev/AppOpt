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

    let old = fs::read_to_string(config_file).unwrap_or_default();
    let mut out = String::new();
    for raw in old.lines() {
        let line = raw.trim();
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
