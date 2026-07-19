// applist.conf 的各种区块语法统一在这里展开成旧版规则，执行层只处理一种语义。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CanonicalRule {
    pub key: String,
    pub cpus: String,
}

pub struct CanonicalGroup {
    pub rules: Vec<CanonicalRule>,
    pub block: bool,
}

#[derive(Clone, Debug)]
pub struct BlockRange {
    pub owner: String,
    pub start_line: usize,
    pub end_line: usize,
}

struct ParsedDocument {
    groups: Vec<CanonicalGroup>,
    ranges: Vec<BlockRange>,
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

pub fn parse_config_groups(text: &str) -> Vec<CanonicalGroup> {
    parse_document(text).groups
}

pub fn block_ranges(text: &str) -> Option<Vec<BlockRange>> {
    let document = parse_document(text);
    document.valid.then_some(document.ranges)
}

fn parse_document(text: &str) -> ParsedDocument {
    let lines = text.lines().collect::<Vec<_>>();
    let mut groups = Vec::new();
    let mut ranges = Vec::new();
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
            valid &= parsed.is_some();
            groups.push(CanonicalGroup {
                rules: parsed.unwrap_or_default(),
                block: true,
            });
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
                valid = false;
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
            valid &= block_valid;
            groups.push(CanonicalGroup {
                rules: if block_valid {
                    parsed.unwrap_or_default()
                } else {
                    Vec::new()
                },
                block: true,
            });
            ranges.push(BlockRange {
                owner: header.owner,
                start_line: index,
                end_line: end,
            });
            index = end;
            continue;
        }

        let rules = parse_legacy_rule(code).into_iter().collect();
        groups.push(CanonicalGroup {
            rules,
            block: false,
        });
        index += 1;
    }

    ParsedDocument {
        groups,
        ranges,
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
        let values = args.split(',').map(str::trim).collect::<Vec<_>>();
        let owner = values.first()?.trim().to_string();
        let fallback = values.get(1).map(|v| v.trim().to_string());
        return Some(Header {
            owner,
            fallback,
            kind: BlockKind::Function,
            valid: values.len() <= 2 && values.iter().all(|value| !value.is_empty()),
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

    let mut rules = Vec::new();
    let mut body_fallback = false;
    for raw in lines {
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            continue;
        }
        let rule = match header.kind {
            BlockKind::Standard => parse_standard_member(&header.owner, code),
            BlockKind::Tagged => parse_tagged_member(&header.owner, code),
            BlockKind::Natural => parse_natural_member(&header.owner, code),
            BlockKind::Function => parse_function_member(&header.owner, code),
            BlockKind::Nested | BlockKind::Yaml => None,
        }?;
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

fn parse_nested_body(lines: &[&str], header: &Header) -> Option<Vec<CanonicalRule>> {
    let mut rules = Vec::new();
    let mut section: Option<&str> = None;
    let mut fallback = header.fallback.clone();
    for raw in lines {
        let code = code_part(raw);
        if code.is_empty() || code.starts_with('#') {
            continue;
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
            "}" => section = None,
            _ if section == Some("threads") => {
                let (name, cpus) = split_assignment(code)?;
                if !valid_member_name(name) {
                    return None;
                }
                rules.push(thread_rule(&header.owner, name, cpus));
            }
            _ if section == Some("processes") => {
                let (name, cpus) = split_assignment(code)?;
                if !valid_member_name(name) {
                    return None;
                }
                rules.push(process_rule(&header.owner, name, cpus));
            }
            _ => {
                let (name, cpus) = split_assignment(code)?;
                if name != "fallback" || fallback.is_some() {
                    return None;
                }
                fallback = Some(cpus.to_string());
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
        let (name, cpus) = code.rsplit_once(':')?;
        let name = name.trim();
        let cpus = cpus.trim();
        if name.is_empty() || cpus.is_empty() {
            return None;
        }
        match section {
            Some("threads") if leading_spaces(raw) == 8 && valid_member_name(name) => {
                rules.push(thread_rule(owner, name, cpus))
            }
            Some("processes") if leading_spaces(raw) == 8 && valid_member_name(name) => {
                rules.push(process_rule(owner, name, cpus))
            }
            _ if name == "fallback" && leading_spaces(raw) == 4 && fallback.is_none() => {
                fallback = Some(cpus.to_string())
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
    if !valid_member_name(name) {
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
        if !valid_member_name(name) {
            return None;
        }
        return Some(thread_rule(owner, name, cpus));
    }
    let rest = code.strip_prefix("process ")?;
    let (name, cpus) = split_assignment(rest)?;
    if !valid_member_name(name) {
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
    let values = split_function_args(args);
    if values.len() != 2 {
        return None;
    }
    let name = values[0].trim();
    let cpus = values[1].trim();
    if !valid_member_name(name) || cpus.is_empty() {
        return None;
    }
    Some(if process {
        process_rule(owner, name, cpus)
    } else {
        thread_rule(owner, name, cpus)
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

fn split_function_args(args: &str) -> Vec<&str> {
    match args.rsplit_once(',') {
        Some((left, right)) => vec![left.trim(), right.trim()],
        None => vec![args.trim()],
    }
}

fn thread_rule(owner: &str, name: &str, cpus: &str) -> CanonicalRule {
    canonical(&format!("{owner}{{{name}}}"), cpus)
}

fn process_rule(owner: &str, name: &str, cpus: &str) -> CanonicalRule {
    let child = if name.starts_with(&format!("{owner}:")) {
        name.to_string()
    } else if name.starts_with(':') {
        format!("{owner}{name}")
    } else {
        format!("{owner}:{name}")
    };
    canonical(&child, cpus)
}

fn canonical(key: &str, cpus: &str) -> CanonicalRule {
    CanonicalRule {
        key: key.to_string(),
        cpus: cpus.to_string(),
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
