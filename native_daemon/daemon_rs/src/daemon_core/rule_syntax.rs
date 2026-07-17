// applist.conf 新版区块语法在这里展开成旧版规则，执行层无需维护第二套规则语义。

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CanonicalRule {
    pub key: String,
    pub cpus: String,
}

pub struct CanonicalGroup {
    pub rules: Vec<CanonicalRule>,
    pub block: bool,
}

struct BlockHeader {
    owner: String,
    fallback_cpus: Option<String>,
    valid: bool,
}

struct OpenBlock {
    header: BlockHeader,
    rules: Vec<CanonicalRule>,
    valid: bool,
}

struct BlockClose {
    fallback_cpus: Option<String>,
    valid: bool,
}

pub fn parse_config_groups(text: &str) -> Vec<CanonicalGroup> {
    let mut result = Vec::new();
    let mut open_block: Option<OpenBlock> = None;

    for raw in text.lines() {
        let code = code_part(raw);
        if let Some(block) = open_block.as_mut() {
            if code.is_empty() || code.starts_with('#') {
                continue;
            }
            if let Some(close) = parse_block_close(code) {
                let ambiguous =
                    block.header.fallback_cpus.is_some() && close.fallback_cpus.is_some();
                if block.valid && close.valid && !ambiguous {
                    let fallback = block.header.fallback_cpus.take().or(close.fallback_cpus);
                    if let Some(cpus) = fallback {
                        block.rules.push(CanonicalRule {
                            key: block.header.owner.clone(),
                            cpus,
                        });
                    }
                    result.push(CanonicalGroup {
                        rules: std::mem::take(&mut block.rules),
                        block: true,
                    });
                }
                open_block = None;
                continue;
            }

            if let Some(rule) = parse_block_body(&block.header.owner, code) {
                block.rules.push(rule);
            } else {
                block.valid = false;
            }
            continue;
        }

        if let Some(header) = parse_block_header(code) {
            let valid = header.valid;
            open_block = Some(OpenBlock {
                header,
                rules: Vec::new(),
                valid,
            });
            continue;
        }

        if code.is_empty() || code.starts_with('#') {
            continue;
        }
        let Some((key, cpus)) = code.split_once('=') else {
            continue;
        };
        let key = key.trim();
        let cpus = cpus.trim();
        if !key.is_empty() && !cpus.is_empty() {
            result.push(CanonicalGroup {
                rules: vec![CanonicalRule {
                    key: key.to_string(),
                    cpus: cpus.to_string(),
                }],
                block: false,
            });
        }
    }

    // 文件结束时仍未闭合的区块不会产生任何规则。
    result
}

pub fn block_header_owner(raw: &str) -> Option<String> {
    parse_block_header(code_part(raw)).map(|header| header.owner)
}

pub fn is_block_close(raw: &str) -> bool {
    code_part(raw).starts_with('}')
}

pub fn has_valid_block_structure(text: &str) -> bool {
    let mut open_block: Option<BlockHeader> = None;
    for raw in text.lines() {
        let code = code_part(raw);
        if let Some(header) = open_block.as_ref() {
            if code.is_empty() || code.starts_with('#') {
                continue;
            }
            if let Some(close) = parse_block_close(code) {
                if !header.valid
                    || !close.valid
                    || (header.fallback_cpus.is_some() && close.fallback_cpus.is_some())
                {
                    return false;
                }
                open_block = None;
            } else if parse_block_body(&header.owner, code).is_none() {
                return false;
            }
            continue;
        }

        if let Some(header) = parse_block_header(code) {
            if !header.valid {
                return false;
            }
            open_block = Some(header);
        }
    }
    open_block.is_none()
}

fn parse_block_header(code: &str) -> Option<BlockHeader> {
    let open = code.find('{')?;
    if open == 0 || !code[open + 1..].trim().is_empty() {
        return None;
    }
    let prefix = code[..open].trim();
    if prefix.is_empty() || prefix.contains('{') || prefix.contains('}') {
        return None;
    }

    let (owner, fallback_cpus, valid) = if let Some((owner, cpus)) = prefix.split_once('=') {
        let owner = owner.trim();
        let cpus = cpus.trim();
        (
            owner,
            Some(cpus.to_string()),
            !owner.is_empty() && !owner.contains('=') && !cpus.is_empty() && !cpus.contains('='),
        )
    } else {
        (prefix, None, true)
    };
    Some(BlockHeader {
        owner: owner.to_string(),
        fallback_cpus,
        valid,
    })
}

fn parse_block_close(code: &str) -> Option<BlockClose> {
    let tail = code.strip_prefix('}')?.trim();
    if tail.is_empty() {
        return Some(BlockClose {
            fallback_cpus: None,
            valid: true,
        });
    }
    let Some(cpus) = tail.strip_prefix('=') else {
        return Some(BlockClose {
            fallback_cpus: None,
            valid: false,
        });
    };
    let cpus = cpus.trim();
    Some(BlockClose {
        fallback_cpus: (!cpus.is_empty()).then(|| cpus.to_string()),
        valid: !cpus.is_empty() && !cpus.contains('='),
    })
}

fn parse_block_body(owner: &str, code: &str) -> Option<CanonicalRule> {
    let (name, cpus) = code.split_once('=')?;
    let name = name.trim();
    let cpus = cpus.trim();
    if name.is_empty()
        || cpus.is_empty()
        || name.contains('{')
        || name.contains('}')
        || name.contains('=')
    {
        return None;
    }

    let key = if name.starts_with(':') && name.len() > 1 {
        format!("{owner}{name}")
    } else if name.starts_with(&format!("{owner}:")) && name.len() > owner.len() + 1 {
        name.to_string()
    } else {
        format!("{owner}{{{name}}}")
    };
    Some(CanonicalRule {
        key,
        cpus: cpus.to_string(),
    })
}

fn code_part(raw: &str) -> &str {
    raw.split_once("//").map_or(raw, |(code, _)| code).trim()
}
