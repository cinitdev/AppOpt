// 校准先生成统一的旧版规则，再按用户策略转换成写回格式。

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RuleOutputFormat {
    Legacy,
    AuthorBlock,
    CompactHeaderBlock,
    SeparateFallbackBlock,
    CompactSeparateFallbackBlock,
    ExtendedBlock,
    CompactExtendedBlock,
}

impl RuleOutputFormat {
    pub(crate) fn from_wire(value: &str) -> Self {
        match value.trim() {
            "author_block" => Self::AuthorBlock,
            "compact_header_block" => Self::CompactHeaderBlock,
            "separate_fallback_block" => Self::SeparateFallbackBlock,
            "compact_separate_fallback_block" => Self::CompactSeparateFallbackBlock,
            "extended_block" => Self::ExtendedBlock,
            "compact_extended_block" => Self::CompactExtendedBlock,
            _ => Self::Legacy,
        }
    }

    pub(crate) fn wire(self) -> &'static str {
        match self {
            Self::Legacy => "legacy",
            Self::AuthorBlock => "author_block",
            Self::CompactHeaderBlock => "compact_header_block",
            Self::SeparateFallbackBlock => "separate_fallback_block",
            Self::CompactSeparateFallbackBlock => "compact_separate_fallback_block",
            Self::ExtendedBlock => "extended_block",
            Self::CompactExtendedBlock => "compact_extended_block",
        }
    }
}

struct GeneratedRule {
    owner: String,
    thread: Option<String>,
    cpus: String,
    original: String,
}

pub(crate) fn format_generated_rules(
    pkg: &str,
    legacy_rules: Vec<String>,
    output_format: RuleOutputFormat,
) -> Vec<String> {
    if output_format == RuleOutputFormat::Legacy {
        return legacy_rules;
    }

    let parsed = legacy_rules
        .into_iter()
        .map(parse_generated_rule)
        .collect::<Vec<_>>();
    let fallback = parsed.iter().find_map(|rule| {
        rule.as_ref()
            .filter(|rule| rule.owner == pkg && rule.thread.is_none())
            .map(|rule| rule.cpus.clone())
    });
    let threads = parsed
        .iter()
        .filter_map(Option::as_ref)
        .filter(|rule| rule.owner == pkg && rule.thread.is_some())
        .collect::<Vec<_>>();
    let children = parsed
        .iter()
        .filter_map(Option::as_ref)
        .filter(|rule| rule.thread.is_none() && rule.owner.starts_with(&format!("{pkg}:")))
        .collect::<Vec<_>>();
    let others = parsed
        .iter()
        .filter_map(|rule| match rule {
            Some(rule)
                if (rule.owner == pkg && rule.thread.is_none())
                    || (rule.owner == pkg && rule.thread.is_some())
                    || (rule.thread.is_none() && rule.owner.starts_with(&format!("{pkg}:"))) =>
            {
                None
            }
            Some(rule) => Some(rule.original.clone()),
            None => None,
        })
        .collect::<Vec<_>>();

    let mut out = Vec::new();
    match output_format {
        RuleOutputFormat::Legacy => unreachable!(),
        RuleOutputFormat::AuthorBlock => {
            if threads.is_empty() {
                fallback
                    .as_ref()
                    .map(|cpus| format!("{pkg}={cpus}"))
                    .into_iter()
                    .for_each(|line| out.push(line));
            } else {
                out.push(match fallback.as_ref() {
                    Some(cpus) => format!("{pkg}={cpus} {{"),
                    None => format!("{pkg} {{"),
                });
                for rule in threads {
                    out.push(format!(
                        "    {}={}",
                        rule.thread.as_deref().unwrap_or_default(),
                        rule.cpus
                    ));
                }
                out.push("}".to_string());
            }
            out.extend(children.into_iter().map(|rule| rule.original.clone()));
        }
        RuleOutputFormat::CompactHeaderBlock => {
            if threads.is_empty() && children.is_empty() {
                fallback
                    .as_ref()
                    .map(|cpus| format!("{pkg}={cpus}"))
                    .into_iter()
                    .for_each(|line| out.push(line));
            } else {
                out.push(match fallback.as_ref() {
                    Some(cpus) => format!("{pkg}={cpus}{{"),
                    None => format!("{pkg}{{"),
                });
                append_block_members(&mut out, pkg, &threads, &children);
                out.push("}".to_string());
            }
        }
        RuleOutputFormat::SeparateFallbackBlock
        | RuleOutputFormat::CompactSeparateFallbackBlock => {
            if threads.is_empty() && children.is_empty() {
                fallback
                    .as_ref()
                    .map(|cpus| format!("{pkg}={cpus}"))
                    .into_iter()
                    .for_each(|line| out.push(line));
            } else {
                let separator = if output_format == RuleOutputFormat::SeparateFallbackBlock {
                    " "
                } else {
                    ""
                };
                out.push(format!("{pkg}{separator}{{"));
                append_block_members(&mut out, pkg, &threads, &children);
                out.push("}".to_string());
                fallback
                    .as_ref()
                    .map(|cpus| format!("{pkg}={cpus}"))
                    .into_iter()
                    .for_each(|line| out.push(line));
            }
        }
        RuleOutputFormat::ExtendedBlock | RuleOutputFormat::CompactExtendedBlock => {
            if threads.is_empty() && children.is_empty() {
                fallback
                    .as_ref()
                    .map(|cpus| format!("{pkg}={cpus}"))
                    .into_iter()
                    .for_each(|line| out.push(line));
            } else {
                let separator = if output_format == RuleOutputFormat::ExtendedBlock {
                    " "
                } else {
                    ""
                };
                out.push(format!("{pkg}{separator}{{"));
                append_block_members(&mut out, pkg, &threads, &children);
                out.push(match fallback.as_ref() {
                    Some(cpus) => format!("}}={cpus}"),
                    None => "}".to_string(),
                });
            }
        }
    }
    out.extend(others);
    out
}

fn append_block_members(
    out: &mut Vec<String>,
    pkg: &str,
    threads: &[&GeneratedRule],
    children: &[&GeneratedRule],
) {
    for rule in threads {
        out.push(format!(
            "    {}={}",
            rule.thread.as_deref().unwrap_or_default(),
            rule.cpus
        ));
    }
    for rule in children {
        out.push(format!("    {}={}", &rule.owner[pkg.len()..], rule.cpus));
    }
}

fn parse_generated_rule(line: String) -> Option<GeneratedRule> {
    let (key, cpus) = line.split_once('=')?;
    let key = key.trim();
    let cpus = cpus.trim();
    if key.is_empty() || cpus.is_empty() {
        return None;
    }
    let (owner, thread) = if let Some(open) = key.find('{') {
        let close = key.rfind('}')?;
        if close != key.len() - 1 || close <= open + 1 {
            return None;
        }
        (
            key[..open].trim().to_string(),
            Some(key[open + 1..close].trim().to_string()),
        )
    } else {
        (key.to_string(), None)
    };
    Some(GeneratedRule {
        owner,
        thread,
        cpus: cpus.to_string(),
        original: line,
    })
}
