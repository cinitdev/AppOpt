//! 手动性能记录的命令与持久开关；不调用校准或亲和性写入接口。
use std::{fs, io::Read};

pub(super) const COMMAND: &str = "/data/adb/modules/QixiaThreads/config/performance_recording.cmd";
pub(super) fn enabled(config: &str, package: &str) -> bool {
    crate::auto_affinity::app_options(config).get(package).is_some_and(|options| options.recording)
}

#[derive(Clone, Debug, PartialEq)]
pub(super) struct Command { pub start: bool, pub package: String, pub token: String }
pub(super) fn parse(text: &str) -> Option<Command> {
    let fields: Vec<_> = text.split_whitespace().collect();
    if fields.len() != 3 || !matches!(fields[0], "start" | "stop") { return None; }
    let package = fields[1];
    if package.len() > 255 || !package.contains('.') || !package.bytes().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_')) { return None; }
    let token = fields[2];
    if token.is_empty() || token.len() > 80 || !token.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-') { return None; }
    Some(Command { start: fields[0] == "start", package: package.into(), token: token.into() })
}
pub(super) fn take_command() -> Option<Command> {
    let claimed = format!("{COMMAND}.reading");
    fs::rename(COMMAND, &claimed).ok()?;
    let mut text = String::new();
    let read = fs::File::open(&claimed).and_then(|file| file.take(1025).read_to_string(&mut text));
    let _ = fs::remove_file(claimed);
    if read.is_err() || text.len() > 1024 { return None; }
    parse(&text)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn switch_and_commands_are_strict_and_manual() {
        assert!(enabled("com.game,record\ncom.other", "com.game"));
        assert!(enabled("com.game,fallback,record", "com.game"));
        assert!(!enabled("com.game,fallback", "com.game"));
        assert!(!enabled("com.game,record", "com.gam"));
        assert!(parse("start com.game record-1").unwrap().start);
        assert!(!parse("stop com.game record-1").unwrap().start);
        for text in ["start com.game", "auto com.game abc", "start com.game abc extra", "start com.game;id abc"] {
            assert!(parse(text).is_none());
        }
    }
}
