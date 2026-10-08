use std::{fs, io, path::Path};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ThreadStat {
    pub name: String,
    pub start: u64,
    pub ticks: u64,
}

pub fn parse_stat(bytes: &[u8]) -> Option<ThreadStat> {
    let open = bytes.iter().position(|b| *b == b'(')?;
    let close = bytes.iter().rposition(|b| *b == b')')?;
    if open >= close {
        return None;
    }
    let name = String::from_utf8_lossy(&bytes[open + 1..close])
        .trim()
        .to_owned();
    if name.is_empty() {
        return None;
    }
    let tail = std::str::from_utf8(&bytes[close + 1..]).ok()?;
    let mut fields = tail.split_whitespace();
    let user = fields.nth(11)?.parse::<u64>().ok()?;
    let system = fields.next()?.parse::<u64>().ok()?;
    let start = fields.nth(6)?.parse().ok()?;
    Some(ThreadStat {
        name,
        start,
        ticks: user.checked_add(system)?,
    })
}

pub fn read_stat(path: impl AsRef<Path>) -> io::Result<ThreadStat> {
    parse_stat(&fs::read(path)?)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid proc stat"))
}

pub fn read_cmdline(pid: i32) -> io::Result<String> {
    let bytes = fs::read(format!("/proc/{pid}/cmdline"))?;
    let name = bytes.split(|b| *b == 0).next().unwrap_or_default();
    Ok(
        String::from_utf8_lossy(name.rsplit(|b| *b == b'/').next().unwrap_or_default())
            .trim()
            .to_owned(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_with_parentheses_and_non_utf8_do_not_break_identity() {
        let mut bytes = b"42 (a ) \xff) S".to_vec();
        for index in 1..20 {
            bytes.extend_from_slice(
                format!(
                    " {}",
                    match index {
                        11 => 7,
                        12 => 8,
                        19 => 99,
                        _ => 0,
                    }
                )
                .as_bytes(),
            );
        }
        let stat = parse_stat(&bytes).unwrap();
        assert_eq!((stat.start, stat.ticks), (99, 15));
        assert!(stat.name.contains(')'));
        assert!(parse_stat(b"42 (incomplete) S").is_none());
    }
}
