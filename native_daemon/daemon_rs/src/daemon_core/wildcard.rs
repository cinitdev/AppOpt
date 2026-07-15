// AppOpt 线程名通配符匹配。
//
// 支持的语法按旧规则保持轻量：
// - *      匹配任意长度
// - ?      匹配单个字符
// - [0-9]  匹配字符范围
//
// 这里不引入 regex，原因有两个：
// 1. Android 守护进程不需要完整正则，旧规则也不是正则语法。
// 2. 线程扫描会频繁调用匹配函数，轻量 glob 更容易控制性能和行为。
#[cfg(any(target_os = "android", target_os = "linux"))]
fn glob_match(pattern: &str, text: &str) -> bool {
    let Ok(pattern) = CString::new(pattern) else {
        return false;
    };
    let Ok(text) = CString::new(text) else {
        return false;
    };
    unsafe { libc::fnmatch(pattern.as_ptr(), text.as_ptr(), libc::FNM_NOESCAPE) == 0 }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn glob_match(pattern: &str, text: &str) -> bool {
    glob_match_portable(pattern, text)
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn glob_match_portable(pattern: &str, text: &str) -> bool {
    let p: Vec<char> = pattern.chars().collect();
    let t: Vec<char> = text.chars().collect();
    let (mut pi, mut ti) = (0usize, 0usize);
    let mut star: Option<usize> = None;
    let mut star_text = 0usize;

    while ti < t.len() {
        if pi < p.len() {
            if let Some(next_pi) = pattern_atom_matches(&p, pi, t[ti]) {
                pi = next_pi;
                ti += 1;
                continue;
            }
        }
        if pi < p.len() && p[pi] == '*' {
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

    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }

    pi == p.len()
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn pattern_atom_matches(pattern: &[char], index: usize, ch: char) -> Option<usize> {
    match pattern[index] {
        '?' => Some(index + 1),
        '[' => match_class(pattern, index, ch),
        literal if literal == ch => Some(index + 1),
        _ => None,
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn match_class(pattern: &[char], index: usize, ch: char) -> Option<usize> {
    let mut i = index + 1;
    let negated = i < pattern.len() && matches!(pattern[i], '!' | '^');
    if negated {
        i += 1;
    }
    let mut matched = false;

    while i < pattern.len() {
        if pattern[i] == ']' {
            return if matched != negated { Some(i + 1) } else { None };
        }

        if i + 2 < pattern.len() && pattern[i + 1] == '-' && pattern[i + 2] != ']' {
            let start = pattern[i];
            let end = pattern[i + 2];
            if start <= ch && ch <= end {
                matched = true;
            }
            i += 3;
        } else {
            if pattern[i] == ch {
                matched = true;
            }
            i += 1;
        }
    }

    None
}
