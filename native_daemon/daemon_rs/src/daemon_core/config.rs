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
    parse_config_with_key(path).map(|(rules, _)| rules)
}

fn parse_config_with_key(path: &Path) -> io::Result<(Vec<Rule>, FileKey)> {
    let bytes = fs::read(path)?;
    let key = content_file_key(&bytes);
    let text =
        String::from_utf8(bytes).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    Ok((parse_config_text(&text), key))
}

fn parse_config_text(text: &str) -> Vec<Rule> {
    let mut rules = Vec::new();

    for group in rule_syntax::parse_config_groups(text) {
        let expected_count = group.rules.len();
        let parsed: Vec<Rule> = group
            .rules
            .into_iter()
            .filter_map(|canonical| {
                let cpus = canonical.cpus.as_str();
                if !cpus.eq_ignore_ascii_case("auto") && CpuMask::parse(cpus).is_none() {
                    return None;
                }
                parse_rule_key(&canonical.key, cpus).filter(|rule| !(rule.auto && rule.thread.is_some()))
            })
            .collect();
        if !group.block || parsed.len() == expected_count {
            rules.extend(parsed);
        }
    }

    deduplicate_config_rules(rules)
}

/* 同一线程、子进程或主进程兜底只保留覆盖核心最多的一条。 */
fn deduplicate_config_rules(rules: Vec<Rule>) -> Vec<Rule> {
    let mut selected = Vec::<Rule>::new();
    let mut indices = HashMap::<(String, Option<String>), usize>::new();
    for rule in rules {
        let key = (rule.owner.clone(), rule.thread.clone());
        if let Some(index) = indices.get(&key).copied() {
            if rule_cpu_preference(&rule) > rule_cpu_preference(&selected[index]) {
                selected[index] = rule;
            }
        } else {
            indices.insert(key, selected.len());
            selected.push(rule);
        }
    }
    selected
}

fn rule_cpu_preference(rule: &Rule) -> (u8, u32, usize, usize) {
    if rule.auto {
        return (1, 0, 0, 0);
    }
    let Some(mask) = CpuMask::parse(&rule.cpus) else {
        return (0, 0, 0, 0);
    };
    let count = mask.words.iter().map(|word| word.count_ones()).sum();
    let limit = CPU_MASK_WORDS * 64;
    let highest = (0..limit).rev().find(|cpu| mask.contains(*cpu)).unwrap_or(0);
    let lowest = (0..limit).find(|cpu| mask.contains(*cpu)).unwrap_or(limit);
    (2, count, highest, limit.saturating_sub(lowest))
}

fn parse_rule_key(left: &str, cpus: &str) -> Option<Rule> {
    if let Some(open) = left.find('{') {
        // 线程规则：com.pkg{thread-pattern}=0-3。
        // pattern 后续用 glob_match 支持 *、?、[0-9] 这类 AppOpt 规则写法。
        let close = left[open + 1..].find('}')? + open + 1;
        if close <= open {
            return None;
        }
        let owner = left[..open].trim();
        let thread = left[open + 1..close].trim();
        if thread.contains('{') || !left[close + 1..].trim().is_empty() {
            return None;
        }
        if owner.is_empty()
            || owner.len() > MAX_CONFIG_OWNER_BYTES
            || thread.is_empty()
            || thread.len() > MAX_CONFIG_THREAD_BYTES
        {
            return None;
        }
        return Some(Rule {
            owner: owner.to_string(),
            thread: Some(thread.to_string()),
            cpus: cpus.to_string(),
            auto: cpus.eq_ignore_ascii_case("auto"),
        });
    }

    if left.is_empty() || left.contains('}') || left.len() > MAX_CONFIG_OWNER_BYTES {
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
    parse_uid_map_with_key(path).map(|(map, _)| map)
}

fn parse_uid_map_with_key(path: &Path) -> io::Result<(HashMap<String, u32>, Option<FileKey>)> {
    // package_uid.map 由 App/前台 helper 写入，格式为 com.example.app=10123。
    // 让 Android Framework 负责包名到 UID 的真实映射，daemon 不解析 packages.list，也不 fork cmd/pm。
    let bytes = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok((HashMap::new(), None)),
        Err(err) => return Err(err),
    };
    let key = content_file_key(&bytes);
    let text =
        String::from_utf8(bytes).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
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

    Ok((map, Some(key)))
}

fn build_scan_plan(
    rules: &[Rule],
    uid_map: &HashMap<String, u32>,
    target_pkg: Option<&str>,
) -> ScanPlan {
    let mut plan = ScanPlan::default();

    // 把规则整理成“扫描计划”：
    // - 有 UID 的包按 appId 建索引，同包名的 Android 多用户/应用分身共享 appId。
    // - all_pkgs 为所有包提供严格 cmdline 兜底；fallback_pkgs 只记录缺少映射的包。
    // - --pkg 只用于调试/单包扫描，不影响正常守护模式。
    //
    // 规则 owner 可能是主包名或子进程名；UID 映射只按基础包名建立。
    for rule in rules.iter().filter(|rule| !rule.auto) {
        let Some(base_pkg) = base_package(&rule.owner) else {
            continue;
        };
        if let Some(target) = target_pkg {
            if base_pkg != target {
                continue;
            }
        }
        plan.all_pkgs.insert(base_pkg.to_string());
        if let Some(uid) = uid_map.get(base_pkg) {
            plan.by_app_id
                .entry(android_app_id(*uid))
                .or_default()
                .insert(base_pkg.to_string());
        } else {
            plan.fallback_pkgs.insert(base_pkg.to_string());
        }
    }

    plan
}

fn android_app_id(uid: u32) -> u32 {
    uid % ANDROID_UID_USER_RANGE
}

fn base_package(owner: &str) -> Option<&str> {
    let base = owner.split_once(':').map_or(owner, |(pkg, _)| pkg);
    if base.is_empty() {
        None
    } else {
        Some(base)
    }
}

fn content_file_key(bytes: &[u8]) -> FileKey {
    // 固定 FNV-1a 指纹足以检测本地配置变化，且不会受到文件系统 mtime 精度影响。
    let mut hash = 0xcbf29ce484222325u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    FileKey {
        len: bytes.len() as u64,
        content_hash: hash,
    }
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
