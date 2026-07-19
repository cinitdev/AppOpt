// 校准先生成统一的旧版规则，再按用户策略转换成写回格式。

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RuleOutputFormat {
    Legacy,
    AuthorBlock,
    CompactExtendedBlock,
    TaggedBlock,
    NaturalBlock,
    NestedBlock,
    FunctionBlock,
    Yaml,
}

impl RuleOutputFormat {
    pub(crate) fn from_wire(value: &str) -> Self {
        match value.trim() {
            "author_block" => Self::AuthorBlock,
            "compact_header_block"
            | "separate_fallback_block"
            | "compact_separate_fallback_block"
            | "extended_block" => Self::AuthorBlock,
            "compact_extended_block" => Self::CompactExtendedBlock,
            "tagged_block" => Self::TaggedBlock,
            "natural_block" => Self::NaturalBlock,
            "nested_block" => Self::NestedBlock,
            "function_block" => Self::FunctionBlock,
            "yaml" => Self::Yaml,
            _ => Self::Legacy,
        }
    }

    pub(crate) fn wire(self) -> &'static str {
        match self {
            Self::Legacy => "legacy",
            Self::AuthorBlock => "author_block",
            Self::CompactExtendedBlock => "compact_extended_block",
            Self::TaggedBlock => "tagged_block",
            Self::NaturalBlock => "natural_block",
            Self::NestedBlock => "nested_block",
            Self::FunctionBlock => "function_block",
            Self::Yaml => "yaml",
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
    // 只有主进程兜底时保持单行，避免生成没有实际成员的空区块。
    if threads.is_empty() && children.is_empty() {
        if let Some(cpus) = fallback.as_ref() {
            out.push(format!("{pkg}={cpus}"));
        }
        out.extend(others);
        return out;
    }
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
        RuleOutputFormat::CompactExtendedBlock => {
            if threads.is_empty() && children.is_empty() {
                fallback
                    .as_ref()
                    .map(|cpus| format!("{pkg}={cpus}"))
                    .into_iter()
                    .for_each(|line| out.push(line));
            } else {
                out.push(format!("{pkg}{{"));
                append_block_members(&mut out, pkg, &threads, &children);
                out.push(match fallback.as_ref() {
                    Some(cpus) => format!("}}={cpus}"),
                    None => "}".to_string(),
                });
            }
        }
        RuleOutputFormat::TaggedBlock => {
            out.push(format!("{pkg}={{"));
            for rule in &threads {
                out.push(format!(
                    "    thread:{}={}",
                    rule.thread.as_deref().unwrap_or_default(),
                    rule.cpus
                ));
            }
            for rule in &children {
                out.push(format!("    process:{}={}", &rule.owner[pkg.len() + 1..], rule.cpus));
            }
            if let Some(cpus) = fallback.as_ref() {
                out.push(format!("    fallback={cpus}"));
            }
            out.push("}".to_string());
        }
        RuleOutputFormat::NaturalBlock => {
            out.push(match fallback.as_ref() {
                Some(cpus) => format!("app {pkg} fallback {cpus} {{"),
                None => format!("app {pkg} {{"),
            });
            for rule in &threads {
                out.push(format!(
                    "    thread {}={}",
                    rule.thread.as_deref().unwrap_or_default(),
                    rule.cpus
                ));
            }
            for rule in &children {
                out.push(format!("    process {}={}", &rule.owner[pkg.len() + 1..], rule.cpus));
            }
            out.push("}".to_string());
        }
        RuleOutputFormat::NestedBlock => {
            out.push(format!("{pkg}={{"));
            if !threads.is_empty() {
                out.push("    threads {".to_string());
                for rule in &threads {
                    out.push(format!(
                        "        {}={}",
                        rule.thread.as_deref().unwrap_or_default(),
                        rule.cpus
                    ));
                }
                out.push("    }".to_string());
            }
            if !children.is_empty() {
                out.push("    processes {".to_string());
                for rule in &children {
                    out.push(format!("        {}={}", &rule.owner[pkg.len() + 1..], rule.cpus));
                }
                out.push("    }".to_string());
            }
            if let Some(cpus) = fallback.as_ref() {
                out.push(format!("    fallback={cpus}"));
            }
            out.push("}".to_string());
        }
        RuleOutputFormat::FunctionBlock => {
            out.push(match fallback.as_ref() {
                Some(cpus) => format!("app({pkg}, {cpus}) {{"),
                None => format!("app({pkg}) {{"),
            });
            for rule in &threads {
                out.push(format!(
                    "    thread({}, {})",
                    rule.thread.as_deref().unwrap_or_default(),
                    rule.cpus
                ));
            }
            for rule in &children {
                out.push(format!(
                    "    process({}, {})",
                    &rule.owner[pkg.len() + 1..],
                    rule.cpus
                ));
            }
            out.push("}".to_string());
        }
        RuleOutputFormat::Yaml => {
            out.push(format!("{pkg}:"));
            if !threads.is_empty() {
                out.push("    threads:".to_string());
                for rule in &threads {
                    out.push(format!(
                        "        {}: {}",
                        rule.thread.as_deref().unwrap_or_default(),
                        rule.cpus
                    ));
                }
            }
            if !children.is_empty() {
                out.push("    processes:".to_string());
                for rule in &children {
                    out.push(format!("        {}: {}", &rule.owner[pkg.len() + 1..], rule.cpus));
                }
            }
            if let Some(cpus) = fallback.as_ref() {
                out.push(format!("    fallback: {cpus}"));
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
