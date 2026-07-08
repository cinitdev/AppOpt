// 校准结束、规则生成与线程名通配符推导。
//
// 规则生成原则：
// - 主进程最重线程可进入 best_thread 档。
// - 主进程其他重负载线程进入 group_high/group_mid 档。
// - 子进程只生成 com.pkg:proc=cpus，不生成 com.pkg:proc{thread}=cpus。
// - 最后总是追加主包名兜底规则，避免没有单独命中的线程跑到未指定核心。
//
// 这里的目标不是“生成越多规则越好”，而是生成用户能理解、C/Rust daemon 都能执行的规则。
fn finish_session(session: CalibSession, config_file: &Path) -> io::Result<()> {
    // 60 轮 * 500ms 约等于 30 秒，采样太短时负载峰值很容易误判。
    if session.rounds < CALIB_MIN_ROUNDS {
        println!(
            "[CALIB] 采样时长不足: pkg={} 轮次={} 最少需要={}",
            session.pkg, session.rounds, CALIB_MIN_ROUNDS
        );
        write_state(&format!("done {};reason=short", session.pkg))?;
        return Ok(());
    }

    let mut records: Vec<LoadRecord> = session
        .records
        .values()
        .filter(|record| !record.samples.is_empty())
        .cloned()
        .collect();
    records.sort_by(|a, b| {
        b.avg()
            .partial_cmp(&a.avg())
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    if records.is_empty() {
        println!("[CALIB] 未检测到明显负载: pkg={}", session.pkg);
        write_state(&format!("done {};reason=no_load", session.pkg))?;
        return Ok(());
    }

    // 历史记录优先落盘；即使规则生成或写回失败，App 仍可导入这次采样数据辅助排查。
    if let Err(err) = write_history(
        &session.pkg,
        session.rounds,
        &records,
        &session.child_threads,
    ) {
        eprintln!("[CALIB] 历史记录写入失败: pkg={} err={}", session.pkg, err);
    } else {
        println!(
            "[CALIB] 历史记录已写入: pkg={} 轮次={} 负载项={} 子进程线程摘要={} Top=[{}]",
            session.pkg,
            session.rounds,
            records.len(),
            session.child_threads.len(),
            top_record_summary(records.iter(), 8)
        );
    }
    let rules = generate_rules(&session.pkg, &records);
    if rules.is_empty() {
        println!("[CALIB] 未生成规则: pkg={} reason=no_load", session.pkg);
        write_state(&format!("done {};reason=no_load", session.pkg))?;
        return Ok(());
    }

    if write_rules_back(config_file, &session.pkg, &rules) {
        println!(
            "[CALIB] 已生成规则: pkg={} 行数={}\n{}",
            session.pkg,
            rules.len(),
            rules.join("\n")
        );
        write_state(&format!("done {}", session.pkg))?;
    } else {
        eprintln!("[CALIB] 规则写回配置文件失败: pkg={}", session.pkg);
        write_state(&format!("done {};reason=write_fail", session.pkg))?;
    }
    Ok(())
}

fn generate_rules(pkg: &str, records: &[LoadRecord]) -> Vec<String> {
    let topo = CpuTiers::detect();
    let policy = CalibPolicy::load(&topo);
    let mut rules = Vec::new();
    let mut used = HashSet::new();

    // 主进程按线程负载生成线程规则；子进程只在下面生成进程级规则。
    let mut main_threads: Vec<&LoadRecord> = records
        .iter()
        .filter(|record| !record.is_process && record.owner == pkg)
        .collect();
    main_threads.sort_by(|a, b| {
        load_score(b)
            .partial_cmp(&load_score(a))
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    if let Some(best) = main_threads
        .iter()
        .copied()
        .find(|record| record.avg() >= policy.best_avg && record.max_pct >= policy.best_max)
    {
        // 第一档只挑最重的一个线程，用最高性能核心，避免把一堆线程都推到超大核。
        push_rule(
            &mut rules,
            &mut used,
            format!(
                "{pkg}{{{}}}={}",
                rule_base_for_thread(&best.name, &main_threads),
                policy.best_cores
            ),
        );
    }

    for tier in [RuleTier::High, RuleTier::Mid] {
        // 第二/第三档继续从主进程线程里挑负载达标项，最多生成 max_thread_rules 条。
        for record in &main_threads {
            if rules.len() >= policy.max_thread_rules {
                break;
            }
            let avg = record.avg();
            let max = record.max_pct;
            let pass = match tier {
                RuleTier::High => avg >= policy.high_avg && max >= policy.high_max,
                RuleTier::Mid => avg >= policy.mid_avg && max >= policy.mid_max,
            };
            if !pass {
                continue;
            }
            let cpus = match tier {
                RuleTier::High => &policy.high_cores,
                RuleTier::Mid => &policy.mid_cores,
            };
            push_rule(
                &mut rules,
                &mut used,
                format!(
                    "{pkg}{{{}}}={}",
                    rule_base_for_thread(&record.name, &main_threads),
                    cpus
                ),
            );
        }
    }

    // 子进程线程名通常过碎且规则语法不支持 com.app:proc{thread}，这里只绑定子进程整体。
    for tier in [RuleTier::High, RuleTier::Mid] {
        for record in records
            .iter()
            .filter(|record| record.is_process && record.owner != pkg)
        {
            let avg = record.avg();
            let max = record.max_pct;
            let pass = match tier {
                RuleTier::High => avg >= policy.high_avg && max >= policy.high_max,
                RuleTier::Mid => avg >= policy.mid_avg && max >= policy.mid_max,
            };
            if !pass {
                continue;
            }
            let cpus = match tier {
                RuleTier::High => &policy.high_cores,
                RuleTier::Mid => &policy.mid_cores,
            };
            push_rule(&mut rules, &mut used, format!("{}={}", record.owner, cpus));
        }
    }

    push_rule(
        // 最后一条永远写主进程兜底规则，保证未单独命中的线程不会跑到未指定核心。
        &mut rules,
        &mut used,
        format!("{pkg}={}", policy.fallback_cores),
    );
    rules
}

fn process_preview(processes: &[ProcInfo], limit: usize) -> String {
    if processes.is_empty() {
        return "-".to_string();
    }
    let mut rows = processes
        .iter()
        .take(limit)
        .map(|proc_info| format!("{}:{}", proc_info.owner, proc_info.pid))
        .collect::<Vec<_>>()
        .join(", ");
    if processes.len() > limit {
        rows.push_str(&format!(" ... +{}", processes.len() - limit));
    }
    rows
}

fn top_record_summary<'a>(
    records: impl IntoIterator<Item = &'a LoadRecord>,
    limit: usize,
) -> String {
    let mut rows = records.into_iter().collect::<Vec<_>>();
    if rows.is_empty() {
        return "-".to_string();
    }
    rows.sort_by(|a, b| {
        load_score(b)
            .partial_cmp(&load_score(a))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut out = rows
        .iter()
        .take(limit)
        .map(|record| {
            let name = if record.is_process {
                record.owner.as_str()
            } else {
                record.name.as_str()
            };
            format!("{name} avg={:.1}% max={:.1}%", record.avg(), record.max_pct)
        })
        .collect::<Vec<_>>()
        .join("; ");
    if rows.len() > limit {
        out.push_str(&format!(" ... +{}", rows.len() - limit));
    }
    out
}

fn push_rule(rules: &mut Vec<String>, used: &mut HashSet<String>, rule: String) {
    let key = rule
        .split_once('=')
        .map(|(left, _)| left.to_string())
        .unwrap_or_else(|| rule.clone());
    if used.insert(key) {
        rules.push(rule);
    }
}

fn load_score(record: &LoadRecord) -> f64 {
    record.avg() * 0.7 + record.max_pct * 0.3
}

fn rule_name(name: &str) -> String {
    name.chars()
        .map(|ch| match ch {
            '{' | '}' | '=' | '\n' | '\r' => '_',
            _ => ch,
        })
        .collect()
}

fn rule_base_for_thread(name: &str, records: &[&LoadRecord]) -> String {
    // 对 Thread-16、pool-17-thread-3 这类带随机数字的线程尝试生成通配符。
    // 只有历史里至少匹配到两个同类名称时才使用通配符，避免单个线程被过度泛化。
    let Some(candidate) = wildcard_candidate(name) else {
        return rule_name(name);
    };
    let matched = records
        .iter()
        .filter(|record| wildcard_match(&candidate, &record.name))
        .count();
    if matched >= 2 {
        candidate
    } else {
        rule_name(name)
    }
}

fn wildcard_candidate(name: &str) -> Option<String> {
    if name.is_empty() || !rule_name_syntax_ok(name) {
        return None;
    }
    let bytes = name.as_bytes();
    let first_digit = bytes.iter().position(|byte| byte.is_ascii_digit())?;
    let mut last_digit_end = first_digit;
    while last_digit_end < bytes.len() && bytes[last_digit_end].is_ascii_digit() {
        last_digit_end += 1;
    }

    if name[last_digit_end..]
        .bytes()
        .all(|byte| byte.is_ascii_digit() || matches!(byte, b'_' | b'-' | b'.'))
    {
        let mut out = String::from(&name[..first_digit]);
        out.push('*');
        return wildcard_name_syntax_ok(&out).then_some(out);
    }

    let mut out = String::with_capacity(name.len());
    let mut idx = 0;
    while idx < bytes.len() {
        if bytes[idx].is_ascii_digit() {
            out.push('*');
            while idx < bytes.len() && bytes[idx].is_ascii_digit() {
                idx += 1;
            }
        } else {
            out.push(bytes[idx] as char);
            idx += 1;
        }
    }
    if out != name && wildcard_name_syntax_ok(&out) {
        Some(out)
    } else {
        None
    }
}

fn rule_name_syntax_ok(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|ch| !matches!(ch, '{' | '}' | '=' | '\n' | '\r') && ch >= ' ')
}

fn wildcard_name_syntax_ok(name: &str) -> bool {
    rule_name_syntax_ok(name) && name.contains('*')
}

fn wildcard_match(pattern: &str, text: &str) -> bool {
    let pattern = pattern.as_bytes();
    let text = text.as_bytes();
    let (mut pi, mut ti) = (0usize, 0usize);
    let mut star = None;
    let mut star_text = 0usize;

    while ti < text.len() {
        if pi < pattern.len() && pattern[pi] == text[ti] {
            pi += 1;
            ti += 1;
        } else if pi < pattern.len() && pattern[pi] == b'*' {
            star = Some(pi);
            pi += 1;
            star_text = ti;
        } else if let Some(star_pos) = star {
            pi = star_pos + 1;
            star_text += 1;
            ti = star_text;
        } else {
            return false;
        }
    }
    while pi < pattern.len() && pattern[pi] == b'*' {
        pi += 1;
    }
    pi == pattern.len()
}

