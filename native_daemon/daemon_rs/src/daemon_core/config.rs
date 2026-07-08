// 配置解析与扫描计划构建。
//
// applist.conf 的规则语法保持 AppOpt 旧格式：
// - com.pkg=0-3                         主进程/包名兜底规则
// - com.pkg:push=0-3                    子进程规则
// - com.pkg{RenderThread}=7             主进程线程规则
// - com.pkg{Thread-*}=0-3               线程通配符规则
// - com.pkg=auto                        等待校准，占位但不执行绑核
//
// package_uid.map 由 App/前台 helper 通过 PackageManager 写入，daemon 只读取，不自己解析系统包数据库，
// 也不 fork cmd/pm/dumpsys。这样长期运行更稳，ROM 差异也少一点。
fn parse_config(path: &Path) -> io::Result<Vec<Rule>> {
    let text = fs::read_to_string(path)?;
    let mut rules = Vec::new();

    for raw in text.lines() {
        let line = raw.trim();
        // 配置文件里保留原作者规则注释；daemon 只解析有效规则行，不改写这里的格式。
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((left, right)) = line.split_once('=') else {
            continue;
        };

        let left = left.trim();
        let cpus = right.trim();
        if cpus.is_empty() {
            continue;
        }

        if !cpus.eq_ignore_ascii_case("auto") && CpuMask::parse(cpus).is_none() {
            continue;
        }

        if let Some(rule) = parse_rule_key(left, cpus) {
            rules.push(rule);
        }
    }

    Ok(rules)
}

fn parse_rule_key(left: &str, cpus: &str) -> Option<Rule> {
    if let Some(open) = left.find('{') {
        // 线程规则：com.pkg{thread-pattern}=0-3。
        // pattern 后续用 glob_match 支持 *、?、[0-9] 这类 AppOpt 规则写法。
        let close = left.rfind('}')?;
        if close <= open {
            return None;
        }
        let owner = left[..open].trim();
        let thread = left[open + 1..close].trim();
        if owner.is_empty() || thread.is_empty() {
            return None;
        }
        return Some(Rule {
            owner: owner.to_string(),
            thread: Some(thread.to_string()),
            cpus: cpus.to_string(),
            auto: cpus.eq_ignore_ascii_case("auto"),
        });
    }

    if left.is_empty() {
        return None;
    }

    Some(Rule {
        owner: left.to_string(),
        thread: None,
        cpus: cpus.to_string(),
        auto: cpus.eq_ignore_ascii_case("auto"),
    })
}

fn parse_uid_map(path: &Path) -> io::Result<HashMap<String, u32>> {
    // package_uid.map 由 App/前台 helper 写入，格式为 com.example.app=10123。
    // 让 Android Framework 负责包名到 UID 的真实映射，daemon 不解析 packages.list，也不 fork cmd/pm。
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(err) if err.kind() == io::ErrorKind::NotFound => String::new(),
        Err(err) => return Err(err),
    };
    let mut map = HashMap::new();

    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }

        let Some((pkg, uid)) = line.split_once('=') else {
            continue;
        };
        if let Ok(uid) = uid.trim().parse::<u32>() {
            map.insert(pkg.trim().to_string(), uid);
        }
    }

    Ok(map)
}

fn build_scan_plan(
    rules: &[Rule],
    uid_map: &HashMap<String, u32>,
    target_pkg: Option<&str>,
) -> ScanPlan {
    let mut plan = ScanPlan::default();

    // 把规则整理成“扫描计划”：
    // - 有 UID 的包走 by_uid，扫描 /proc 时先比较目录 owner uid。
    // - 没 UID 的包走 fallback_pkgs，只能读 cmdline 判断。
    // - --pkg 只用于调试/单包扫描，不影响正常守护模式。
    //
    // 规则 owner 可能是主包名或子进程名；UID 映射只按基础包名建立。
    for rule in rules {
        let Some(base_pkg) = base_package(&rule.owner) else {
            continue;
        };
        if let Some(target) = target_pkg {
            if base_pkg != target {
                continue;
            }
        }
        if let Some(uid) = uid_map.get(base_pkg) {
            plan.by_uid
                .entry(*uid)
                .or_default()
                .insert(base_pkg.to_string());
        } else {
            plan.fallback_pkgs.insert(base_pkg.to_string());
        }
    }

    plan
}

fn base_package(owner: &str) -> Option<&str> {
    let base = owner.split_once(':').map_or(owner, |(pkg, _)| pkg);
    if base.is_empty() {
        None
    } else {
        Some(base)
    }
}

fn file_key(path: &Path) -> Option<FileKey> {
    let meta = fs::metadata(path).ok()?;
    let modified_ms = meta
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis())
        .unwrap_or(0);
    Some(FileKey {
        len: meta.len(),
        modified_ms,
    })
}

fn build_rules_by_owner(rules: &[Rule]) -> HashMap<&str, Vec<&Rule>> {
    let mut rules_by_owner: HashMap<&str, Vec<&Rule>> = HashMap::new();
    for rule in rules {
        // auto 是校准生成占位，真正核心范围由策略生成后写回，不在这里执行。
        if !rule.auto {
            rules_by_owner
                .entry(rule.owner.as_str())
                .or_default()
                .push(rule);
        }
    }
    rules_by_owner
}
