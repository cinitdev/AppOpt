// applist.conf 的各种区块语法统一在这里展开成旧版规则，执行层只处理一种语义。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CanonicalRule {
    pub key: String,
    pub cpus: String,
}

pub struct CanonicalGroup {
    pub rules: Vec<CanonicalRule>,
}

// 源文本范围仅供解析器回归测试使用。守护进程不再重写配置，
// 因此正式运行时的解析流程不为此分配内存。
#[cfg(test)]
#[derive(Clone, Debug)]
pub struct BlockRange {
    pub owner: String,
    pub start_line: usize,
    pub end_line: usize,
}

struct ParsedDocument {
    groups: Vec<CanonicalGroup>,
    #[cfg(test)]
    ranges: Vec<BlockRange>,
    #[cfg(test)]
    valid: bool,
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum BlockKind {
    Standard,
    Tagged,
    Nested,
    Natural,
    Function,
    Yaml,
}

struct Header {
    owner: String,
    fallback: Option<String>,
    kind: BlockKind,
    valid: bool,
}

struct FunctionProcessHeader {
    name: String,
    fallback: Option<String>,
    valid: bool,
}

pub fn parse_config_groups(text: &str) -> Vec<CanonicalGroup> {
    parse_document(text).groups
}

#[cfg(test)]
fn block_ranges(text: &str) -> Option<Vec<BlockRange>> {
    let document = parse_document(text);
    document.valid.then_some(document.ranges)
}

fn parse_document(text: &str) -> ParsedDocument {
    let lines = text.lines().collect::<Vec<_>>();
    let mut groups = Vec::new();
    #[cfg(test)]
    let mut ranges = Vec::new();
    #[cfg(test)]
    let mut valid = true;
    let mut index = 0;

    while index < lines.len() {
        let raw = lines[index];
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            index += 1;
            continue;
        }

        if let Some(header) = parse_yaml_header(raw) {
            let end = yaml_end(&lines, index);
            let parsed = parse_yaml_body(&lines[index + 1..end], &header.owner);
            #[cfg(test)]
            { valid &= parsed.is_some(); }
            groups.push(CanonicalGroup {
                rules: parsed.unwrap_or_default(),
            });
            #[cfg(test)]
            ranges.push(BlockRange {
                owner: header.owner,
                start_line: index,
                end_line: end,
            });
            index = end;
            continue;
        }

        if let Some(mut header) = parse_brace_header(code) {
            let Some(end) = brace_block_end(&lines, index) else {
                #[cfg(test)]
                { valid = false; }
                break;
            };
            let close = code_part(lines[end - 1]);
            let mut block_valid = header.valid;
            if header.kind == BlockKind::Standard {
                let tail = close.strip_prefix('}').map(str::trim).unwrap_or_default();
                if let Some(cpus) = tail.strip_prefix('=').map(str::trim) {
                    if cpus.is_empty() || header.fallback.is_some() {
                        block_valid = false;
                    } else {
                        header.fallback = Some(cpus.to_string());
                    }
                } else if !tail.is_empty() {
                    block_valid = false;
                }
            } else if close != "}" {
                block_valid = false;
            }
            let body = &lines[index + 1..end - 1];
            let parsed = parse_brace_body(body, &header);
            block_valid &= parsed.is_some();
            #[cfg(test)]
            { valid &= block_valid; }
            groups.push(CanonicalGroup {
                rules: if block_valid {
                    parsed.unwrap_or_default()
                } else {
                    Vec::new()
                },
            });
            #[cfg(test)]
            ranges.push(BlockRange {
                owner: header.owner,
                start_line: index,
                end_line: end,
            });
            index = end;
            continue;
        }

        let legacy = parse_legacy_rule(code);
        #[cfg(test)]
        if legacy.is_none() && code.contains(['{', '}']) {
            valid = false;
        }
        let rules = legacy.into_iter().collect();
        groups.push(CanonicalGroup { rules });
        index += 1;
    }

    ParsedDocument {
        groups,
        #[cfg(test)]
        ranges,
        #[cfg(test)]
        valid,
    }
}

fn parse_brace_header(code: &str) -> Option<Header> {
    if !code.ends_with('{') {
        return None;
    }
    let prefix = code[..code.len() - 1].trim();
    if prefix == "app" || prefix.starts_with("app ") {
        let rest = prefix.strip_prefix("app ").unwrap_or_default();
        let mut parts = rest.split_whitespace();
        let owner = parts.next().unwrap_or_default().to_string();
        let tail = parts.collect::<Vec<_>>();
        let fallback = match tail.as_slice() {
            [] => None,
            ["fallback", cpus] if !cpus.is_empty() => Some((*cpus).to_string()),
            _ => {
                return Some(Header {
                    owner,
                    fallback: None,
                    kind: BlockKind::Natural,
                    valid: false,
                })
            }
        };
        let valid = !owner.is_empty();
        return Some(Header {
            owner,
            fallback,
            kind: BlockKind::Natural,
            valid,
        });
    }
    if let Some(rest) = prefix.strip_prefix("app(") {
        let Some(args) = rest.strip_suffix(')') else {
            return Some(Header {
                owner: String::new(),
                fallback: None,
                kind: BlockKind::Function,
                valid: false,
            });
        };
        let Some((owner, fallback)) = split_function_header_args(args) else {
            return Some(Header {
                owner: String::new(),
                fallback: None,
                kind: BlockKind::Function,
                valid: false,
            });
        };
        let valid = !owner.is_empty() && fallback.as_deref().is_none_or(|value| !value.is_empty());
        return Some(Header {
            owner,
            fallback,
            kind: BlockKind::Function,
            valid,
        });
    }

    let (owner, fallback, kind) = if let Some((owner, cpus)) = prefix.split_once('=') {
        let owner = owner.trim().to_string();
        let cpus = cpus.trim();
        if cpus.is_empty() {
            (owner, None, BlockKind::Tagged)
        } else {
            (owner, Some(cpus.to_string()), BlockKind::Standard)
        }
    } else {
        (prefix.to_string(), None, BlockKind::Standard)
    };
    if owner.is_empty() || owner.contains(['{', '}']) {
        return None;
    }
    Some(Header {
        owner,
        fallback,
        kind,
        valid: true,
    })
}

fn parse_brace_body(lines: &[&str], header: &Header) -> Option<Vec<CanonicalRule>> {
    if header.kind == BlockKind::Nested
        || (header.kind == BlockKind::Tagged
            && lines
                .iter()
                .any(|line| matches!(code_part(line), "threads {" | "processes {")))
    {
        return parse_nested_body(lines, header);
    }
    if header.kind == BlockKind::Function {
        return parse_function_body(lines, header);
    }

    let mut rules = Vec::new();
    let mut body_fallback = false;
    for raw in lines {
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            continue;
        }
        // 额外的大括号表示嵌套结构；普通成员本身不允许携带它们。
        // 这类情况属于区块结构损坏，不能和单条坏规则混淆。
        if code.contains(['{', '}']) {
            return None;
        }
        let Some(rule) = (match header.kind {
            BlockKind::Standard => parse_standard_member(&header.owner, code),
            BlockKind::Tagged => parse_tagged_member(&header.owner, code),
            BlockKind::Natural => parse_natural_member(&header.owner, code),
            BlockKind::Function => parse_function_member(&header.owner, code),
            BlockKind::Nested | BlockKind::Yaml => None,
        }) else {
            // 成员的名称、赋值语法或 CPU 列表无效时，只忽略该成员；
            // 区块的括号/层级完整性仍由上面的结构检查负责。
            continue;
        };
        if header.kind == BlockKind::Tagged && rule.key == header.owner {
            if body_fallback || header.fallback.is_some() {
                return None;
            }
            body_fallback = true;
        }
        rules.push(rule);
    }
    if let Some(cpus) = header.fallback.as_ref() {
        rules.push(canonical(&header.owner, cpus));
    }
    Some(rules)
}

fn parse_function_body(lines: &[&str], header: &Header) -> Option<Vec<CanonicalRule>> {
    let mut rules = Vec::new();
    let mut index = 0usize;
    while index < lines.len() {
        let code = code_part(lines[index]);
        if code.is_empty() || code.starts_with('#') {
            index += 1;
            continue;
        }
        if code.contains('}') && code != "}" {
            return None;
        }

        let Some(process) = parse_function_process_header(code) else {
            if code == "}" || code.contains('{') {
                return None;
            }
            if let Some(rule) = parse_function_member(&header.owner, code) {
                rules.push(rule);
            }
            index += 1;
            continue;
        };

        let child_owner = process
            .valid
            .then(|| process_owner(&header.owner, &process.name));
        if let (Some(child_owner), Some(cpus)) = (
            child_owner.as_deref(),
            process
                .fallback
                .as_deref()
                .filter(|cpus| valid_cpu_value(cpus)),
        ) {
            rules.push(canonical(child_owner, cpus));
        }
        index += 1;
        let mut closed = false;
        while index < lines.len() {
            let child_code = code_part(lines[index]);
            if child_code.is_empty() || child_code.starts_with('#') {
                index += 1;
                continue;
            }
            if child_code == "}" {
                closed = true;
                index += 1;
                break;
            }
            if child_code.contains(['{', '}']) {
                return None;
            }
            if let Some(child_owner) = child_owner.as_deref() {
                if let Some(child_rule) = parse_function_member(child_owner, child_code) {
                    if child_rule.key.contains('{') {
                        rules.push(child_rule);
                    }
                }
            }
            index += 1;
        }
        if !closed {
            return None;
        }
    }
    if let Some(cpus) = header.fallback.as_deref() {
        rules.push(canonical(&header.owner, cpus));
    }
    Some(rules)
}

fn parse_function_process_header(code: &str) -> Option<FunctionProcessHeader> {
    let call = code.strip_suffix('{')?.trim_end();
    let args = call.strip_prefix("process(")?.strip_suffix(')')?;
    let Some((name, fallback)) = split_function_header_args(args) else {
        return Some(FunctionProcessHeader {
            name: String::new(),
            fallback: None,
            valid: false,
        });
    };
    // 进程名仍可确定时，即使可选 fallback CPU 无效，也要继续解析其子线程；
    // fallback 本身会在写入规则前逐条过滤。
    let valid = valid_member_name(&name) && !name.is_empty();
    Some(FunctionProcessHeader {
        name,
        fallback,
        valid,
    })
}

fn parse_nested_body(lines: &[&str], header: &Header) -> Option<Vec<CanonicalRule>> {
    let mut rules = Vec::new();
    let mut section: Option<&str> = None;
    let mut fallback = header.fallback.clone();
    for raw in lines {
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            continue;
        }
        if code.contains(['{', '}']) && !matches!(code, "threads {" | "processes {" | "}") {
            return None;
        }
        match code {
            "threads {" => {
                if section.is_some() {
                    return None;
                }
                section = Some("threads");
            }
            "processes {" => {
                if section.is_some() {
                    return None;
                }
                section = Some("processes");
            }
            "}" => {
                if section.take().is_none() {
                    return None;
                }
            }
            _ if section == Some("threads") => {
                let Some((name, cpus)) = split_assignment(code) else {
                    continue;
                };
                if valid_member_name(name) && valid_cpu_value(cpus) {
                    rules.push(thread_rule(&header.owner, name, cpus));
                }
            }
            _ if section == Some("processes") => {
                let Some((name, cpus)) = split_assignment(code) else {
                    continue;
                };
                if valid_member_name(name) && valid_cpu_value(cpus) {
                    rules.push(process_rule(&header.owner, name, cpus));
                }
            }
            _ => {
                let Some((name, cpus)) = split_assignment(code) else {
                    continue;
                };
                if name != "fallback" {
                    continue;
                }
                if fallback.is_some() {
                    return None;
                }
                if valid_cpu_value(cpus) {
                    fallback = Some(cpus.to_string());
                }
            }
        }
    }
    if section.is_some() {
        return None;
    }
    if let Some(cpus) = fallback {
        rules.push(canonical(&header.owner, &cpus));
    }
    Some(rules)
}

fn parse_yaml_header(raw: &str) -> Option<Header> {
    if leading_spaces(raw) != 0 {
        return None;
    }
    let code = code_part(raw);
    let owner = code.strip_suffix(':')?.trim();
    if owner.is_empty()
        || owner.contains(char::is_whitespace)
        || owner == "threads"
        || owner == "processes"
    {
        return None;
    }
    Some(Header {
        owner: owner.to_string(),
        fallback: None,
        kind: BlockKind::Yaml,
        valid: true,
    })
}

fn yaml_end(lines: &[&str], start: usize) -> usize {
    let mut index = start + 1;
    while index < lines.len() {
        let raw = lines[index];
        let code = code_part(raw);
        if !code.is_empty() && !code.starts_with('#') && leading_spaces(raw) == 0 {
            break;
        }
        index += 1;
    }
    index
}

fn parse_yaml_body(lines: &[&str], owner: &str) -> Option<Vec<CanonicalRule>> {
    let mut rules = Vec::new();
    let mut section: Option<&str> = None;
    let mut fallback: Option<String> = None;
    for raw in lines {
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            continue;
        }
        if code == "threads:" {
            if leading_spaces(raw) != 4 {
                return None;
            }
            section = Some("threads");
            continue;
        }
        if code == "processes:" {
            if leading_spaces(raw) != 4 {
                return None;
            }
            section = Some("processes");
            continue;
        }
        let Some((name, cpus)) = code.rsplit_once(':') else {
            // 缺少冒号只是这一条成员格式错误；只要缩进结构仍可判断，
            // 不应牵连同一 YAML 区块中的其他规则。
            continue;
        };
        let name = name.trim();
        let cpus = cpus.trim();
        if name.is_empty() || cpus.is_empty() {
            continue;
        }
        match section {
            Some("threads")
                if leading_spaces(raw) == 8 && valid_member_name(name) && valid_cpu_value(cpus) =>
            {
                rules.push(thread_rule(owner, name, cpus))
            }
            Some("processes")
                if leading_spaces(raw) == 8 && valid_member_name(name) && valid_cpu_value(cpus) =>
            {
                rules.push(process_rule(owner, name, cpus))
            }
            _ if name == "fallback" && leading_spaces(raw) == 4 => {
                if fallback.is_some() {
                    return None;
                }
                if valid_cpu_value(cpus) {
                    fallback = Some(cpus.to_string());
                }
            }
            _ if leading_spaces(raw) >= 8 => {
                // 区块内成员的名称、CPU 或轻微语法错误只跳过这一行。
                continue;
            }
            _ => return None,
        }
    }
    if let Some(cpus) = fallback {
        rules.push(canonical(owner, &cpus));
    }
    Some(rules)
}

fn parse_standard_member(owner: &str, code: &str) -> Option<CanonicalRule> {
    let (name, cpus) = split_assignment(code)?;
    if !valid_member_name(name) || !valid_cpu_value(cpus) {
        return None;
    }
    if name.starts_with(':') || name.starts_with(&format!("{owner}:")) {
        Some(process_rule(owner, name, cpus))
    } else {
        Some(thread_rule(owner, name, cpus))
    }
}

fn parse_tagged_member(owner: &str, code: &str) -> Option<CanonicalRule> {
    let (name, cpus) = split_assignment(code)?;
    if !valid_cpu_value(cpus) {
        return None;
    }
    if name == "fallback" {
        return Some(canonical(owner, cpus));
    }
    if let Some(thread) = name.strip_prefix("thread:") {
        return valid_member_name(thread).then(|| thread_rule(owner, thread, cpus));
    }
    let process = name.strip_prefix("process:")?;
    valid_member_name(process).then(|| process_rule(owner, process, cpus))
}

fn parse_natural_member(owner: &str, code: &str) -> Option<CanonicalRule> {
    if let Some(rest) = code.strip_prefix("thread ") {
        let (name, cpus) = split_assignment(rest)?;
        if !valid_member_name(name) || !valid_cpu_value(cpus) {
            return None;
        }
        return Some(thread_rule(owner, name, cpus));
    }
    let rest = code.strip_prefix("process ")?;
    let (name, cpus) = split_assignment(rest)?;
    if !valid_member_name(name) || !valid_cpu_value(cpus) {
        return None;
    }
    Some(process_rule(owner, name, cpus))
}

fn parse_function_member(owner: &str, code: &str) -> Option<CanonicalRule> {
    let (process, args) = if let Some(args) = code
        .strip_prefix("thread(")
        .and_then(|v| v.strip_suffix(')'))
    {
        (false, args)
    } else {
        (true, code.strip_prefix("process(")?.strip_suffix(')')?)
    };
    let (name, cpus) = split_function_member_args(args)?;
    if !valid_member_name(&name) || !valid_cpu_value(&cpus) {
        return None;
    }
    Some(if process {
        process_rule(owner, &name, &cpus)
    } else {
        thread_rule(owner, &name, &cpus)
    })
}

fn parse_legacy_rule(code: &str) -> Option<CanonicalRule> {
    let (key, cpus) = split_assignment(code)?;
    Some(canonical(key, cpus))
}

fn split_assignment(code: &str) -> Option<(&str, &str)> {
    let (name, cpus) = code.split_once('=')?;
    let name = name.trim();
    let cpus = cpus.trim();
    (!name.is_empty() && !cpus.is_empty()).then_some((name, cpus))
}

fn valid_cpu_value(cpus: &str) -> bool {
    cpus.eq_ignore_ascii_case("auto") || super::CpuMask::parse(cpus).is_some()
}

fn split_function_member_args(args: &str) -> Option<(String, String)> {
    if args.trim_start().starts_with('"') {
        let (name, rest) = parse_quoted_function_name(args.trim_start())?;
        let cpus = rest.trim().strip_prefix(',')?.trim();
        if !valid_cpu_value(cpus) {
            return None;
        }
        return Some((name, cpus.to_string()));
    }
    for (comma, _) in args.match_indices(',') {
        let name = args[..comma].trim();
        let cpus = args[comma + 1..].trim();
        if valid_member_name(name) && valid_cpu_value(cpus) {
            return Some((name.to_string(), cpus.to_string()));
        }
    }
    None
}

fn split_function_header_args(args: &str) -> Option<(String, Option<String>)> {
    let args = args.trim();
    if args.starts_with('"') {
        let (name, rest) = parse_quoted_function_name(args)?;
        let rest = rest.trim();
        if rest.is_empty() {
            return Some((name, None));
        }
        let fallback = rest.strip_prefix(',')?.trim();
        return Some((name, Some(fallback.to_string())));
    }
    Some(match args.split_once(',') {
        Some((owner, fallback)) => (owner.trim().to_string(), Some(fallback.trim().to_string())),
        None => (args.to_string(), None),
    })
}

fn parse_quoted_function_name(input: &str) -> Option<(String, &str)> {
    let mut escaped = false;
    let mut out = String::new();
    for (index, ch) in input.char_indices().skip(1) {
        if escaped {
            out.push(ch);
            escaped = false;
        } else if ch == '\\' {
            escaped = true;
        } else if ch == '"' {
            return Some((out, &input[index + ch.len_utf8()..]));
        } else if ch.is_control() {
            return None;
        } else {
            out.push(ch);
        }
    }
    None
}

fn thread_rule(owner: &str, name: &str, cpus: &str) -> CanonicalRule {
    canonical(&format!("{owner}{{{name}}}"), cpus)
}

fn process_rule(owner: &str, name: &str, cpus: &str) -> CanonicalRule {
    canonical(&process_owner(owner, name), cpus)
}

fn process_owner(owner: &str, name: &str) -> String {
    if name.starts_with(&format!("{owner}:")) {
        name.to_string()
    } else if name.starts_with(':') {
        format!("{owner}{name}")
    } else {
        format!("{owner}:{name}")
    }
}

fn canonical(key: &str, cpus: &str) -> CanonicalRule {
    CanonicalRule {
        key: key.to_string(),
        cpus: cpus.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn function_blocks_keep_comma_separated_cpu_masks() {
        let groups = parse_config_groups(
            "app(com.example, 0-3,6-7) {\n  thread(RenderThread, 0-3,6-7)\n}\n",
        );
        let rules = &groups[0].rules;
        assert_eq!(rules[0].key, "com.example{RenderThread}");
        assert_eq!(rules[0].cpus, "0-3,6-7");
        assert_eq!(rules[1].key, "com.example");
        assert_eq!(rules[1].cpus, "0-3,6-7");
    }

    #[test]
    fn quoted_function_names_disambiguate_commas_from_cpu_masks() {
        let groups = parse_config_groups(
            "app(com.example, 0-3,5,7) {\n\
             thread(\"worker,0\", 4-7)\n\
             process(\"remote,0\", 0-3) {\n\
             thread(\"Binder,worker\", 0-3,5,7)\n\
             }\n\
             }\n",
        );
        let rules = &groups[0].rules;
        assert!(rules
            .iter()
            .any(|rule| { rule.key == "com.example{worker,0}" && rule.cpus == "4-7" }));
        assert!(rules.iter().any(|rule| {
            rule.key == "com.example:remote,0{Binder,worker}" && rule.cpus == "0-3,5,7"
        }));
        assert!(rules
            .iter()
            .any(|rule| { rule.key == "com.example:remote,0" && rule.cpus == "0-3" }));
    }

    #[test]
    fn invalid_cpu_member_does_not_drop_the_surrounding_block() {
        let text = "com.example=0-3 {\n  RenderThread=6-7\n  Worker=abc\n}\n";
        let document = parse_document(text);
        assert!(document.valid);
        let ranges = block_ranges(text).unwrap();
        assert_eq!(ranges.len(), 1);
        assert_eq!((&*ranges[0].owner, ranges[0].start_line, ranges[0].end_line), ("com.example", 0, 4));
        let groups = parse_config_groups(text);
        let rules = &groups[0].rules;
        assert!(rules
            .iter()
            .any(|rule| rule.key == "com.example{RenderThread}"));
        assert!(!rules.iter().any(|rule| rule.key == "com.example{Worker}"));
        assert!(rules.iter().any(|rule| rule.key == "com.example"));
    }

    #[test]
    fn invalid_members_are_isolated_in_all_block_formats() {
        let cases = [
            (
                "standard",
                "com.example {\n  Good=4-7\n  Bad=abc\n  malformed\n}\n",
                vec!["com.example{Good}"],
            ),
            (
                "tagged",
                "com.example={\n  thread:Good=4-7\n  thread:Bad=abc\n  malformed\n  fallback=0-3\n}\n",
                vec!["com.example{Good}", "com.example"],
            ),
            (
                "natural",
                "app com.example fallback 0-3 {\n  thread Good=4-7\n  thread Bad=abc\n  malformed\n}\n",
                vec!["com.example{Good}", "com.example"],
            ),
            (
                "nested",
                "com.example={\n  threads {\n    Good=4-7\n    Bad=abc\n  }\n  processes {\n    child=0-3\n    bad=abc\n  }\n  fallback=0-3\n}\n",
                vec!["com.example{Good}", "com.example:child", "com.example"],
            ),
            (
                "function",
                "app(com.example, 0-3) {\n  thread(Good, 4-7)\n  thread(Bad, abc)\n  process(child, 0-2) {\n    thread(ChildGood, 5-7)\n    thread(ChildBad, abc)\n  }\n  process(remote, abc) {\n    thread(RemoteGood, 1-2)\n  }\n  process(empty, ) {\n    thread(EmptyGood, 1-2)\n  }\n}\n",
                vec![
                    "com.example{Good}",
                    "com.example:child{ChildGood}",
                    "com.example:child",
                    "com.example:remote{RemoteGood}",
                    "com.example:empty{EmptyGood}",
                    "com.example",
                ],
            ),
            (
                "yaml",
                "com.example:\n    threads:\n        Good: 4-7\n        Bad: abc\n        malformed\n    processes:\n        child: 0-3\n        bad: abc\n    fallback: 0-3\n",
                vec!["com.example{Good}", "com.example:child", "com.example"],
            ),
        ];

        for (name, text, expected_keys) in cases {
            let document = parse_document(text);
            assert!(
                document.valid,
                "{name} block should remain structurally valid"
            );
            assert_eq!(
                block_ranges(text).map(|ranges| ranges.len()),
                Some(1),
                "{name} block range should remain usable"
            );
            let rules = &document.groups[0].rules;
            for key in expected_keys {
                assert!(
                    rules.iter().any(|rule| rule.key == key),
                    "{name} missing valid rule {key}"
                );
            }
            assert!(
                rules.iter().all(|rule| {
                    !rule.key.contains("Bad")
                        && !rule.key.contains("bad")
                        && !rule.key.contains("malformed")
                }),
                "{name} retained an invalid member"
            );
        }
    }

    #[test]
    fn malformed_block_structure_still_invalidates_block_ranges() {
        let malformed = [
            "com.example {\n  Good=0-3\n",
            "com.example={\n  threads {\n    Good=0-3\n  }\n",
            "app(com.example) {\n  process(child, 0-3) {\n    thread(Good, 0-3)\n  }\n",
            "com.example:\n  threads:\n    Good: 0-3\n",
            "com.example={\n  fallback=0-3\n  fallback=4-7\n}\n",
            "com.example:\n    fallback: 0-3\n    fallback: 4-7\n",
            "com.example {\n  Good=0-3\n}\n}\n",
        ];

        for text in malformed {
            assert!(
                block_ranges(text).is_none(),
                "malformed block must not provide health ranges: {text:?}"
            );
        }
    }

    #[test]
    fn function_process_blocks_expand_child_threads_and_optional_fallback() {
        let groups = parse_config_groups(
            "app(com.tencent.mobileqq, 0-6) {\n\
             process(MSF, 0-3) {\n\
             thread(Thread_*, 0-7)\n\
             }\n\
             process(push) {\n\
             thread(Push-Worker, 4-7)\n\
             }\n\
             }\n",
        );
        let rules = &groups[0].rules;
        assert!(rules
            .iter()
            .any(|rule| { rule.key == "com.tencent.mobileqq:MSF" && rule.cpus == "0-3" }));
        assert!(rules.iter().any(|rule| {
            rule.key == "com.tencent.mobileqq:MSF{Thread_*}" && rule.cpus == "0-7"
        }));
        assert!(rules.iter().any(|rule| {
            rule.key == "com.tencent.mobileqq:push{Push-Worker}" && rule.cpus == "4-7"
        }));
        assert!(rules
            .iter()
            .any(|rule| { rule.key == "com.tencent.mobileqq" && rule.cpus == "0-6" }));
    }
}

fn brace_block_end(lines: &[&str], start: usize) -> Option<usize> {
    let mut depth = 0isize;
    for (index, raw) in lines.iter().enumerate().skip(start) {
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            continue;
        }
        depth += code.chars().filter(|ch| *ch == '{').count() as isize;
        depth -= code.chars().filter(|ch| *ch == '}').count() as isize;
        if depth == 0 {
            return Some(index + 1);
        }
        if depth < 0 {
            return None;
        }
    }
    None
}

fn leading_spaces(raw: &str) -> usize {
    raw.len() - raw.trim_start().len()
}

fn valid_member_name(name: &str) -> bool {
    !name.is_empty() && !name.contains(['{', '}', '='])
}

fn code_part(raw: &str) -> &str {
    raw.split_once("//").map_or(raw, |(code, _)| code).trim()
}
