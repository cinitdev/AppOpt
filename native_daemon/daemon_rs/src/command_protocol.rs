//! FPS 与校准共用的文件命令协议，每个信箱只有一个消费者。
//! 写入方继续使用现有 `.cmd` 路径；消费者只删除已领取的
//! `.processing` 文件。未领取的写入只保留最新值，不形成队列。
use std::fs::{self, File, Metadata};
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

const MAX_COMMAND_BYTES: u64 = 512;
const INCOMPLETE_GRACE: Duration = Duration::from_secs(2);

pub(crate) struct CommandFile {
    incoming: PathBuf,
    claimed: PathBuf,
    pending: Option<(Option<SystemTime>, Instant)>,
}

impl CommandFile {
    pub(crate) fn new(path: impl AsRef<Path>) -> Self {
        let incoming = path.as_ref().to_path_buf();
        let mut claimed = incoming.as_os_str().to_os_string();
        claimed.push(".processing");
        Self {
            incoming,
            claimed: claimed.into(),
            pending: None,
        }
    }

    pub(crate) fn claimed_path(&self) -> &Path {
        &self.claimed
    }

    pub(crate) fn read<T>(
        &mut self,
        parse: impl FnOnce(&str) -> Option<T>,
    ) -> io::Result<Option<T>> {
        match self.read_claimed(parse) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                self.pending = None;
                Ok(None)
            }
            result => result,
        }
    }

    fn read_claimed<T>(&mut self, parse: impl FnOnce(&str) -> Option<T>) -> io::Result<Option<T>> {
        match fs::metadata(&self.claimed) {
            Ok(_) => {} // 接受新命令前，先继续处理尚未读取的已领取命令。
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                fs::rename(&self.incoming, &self.claimed)?;
            }
            Err(error) => return Err(error),
        }
        if !fs::metadata(&self.claimed)?.is_file() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "command is not a regular file",
            ));
        }
        let mut file = File::open(&self.claimed)?;
        let before = file.metadata()?;
        let mut bytes = Vec::new();
        // 限制读取本身的大小，包括检查元数据后文件继续增长的情况。
        if before.len() <= MAX_COMMAND_BYTES {
            (&mut file)
                .take(MAX_COMMAND_BYTES + 1)
                .read_to_end(&mut bytes)?;
        }
        let after = file.metadata()?;
        drop(file);
        if before.len() > MAX_COMMAND_BYTES || bytes.len() as u64 > MAX_COMMAND_BYTES {
            self.finish()?;
            return Ok(None);
        }
        let modified = after.modified().ok();
        if self
            .pending
            .as_ref()
            .is_none_or(|(previous, _)| *previous != modified)
        {
            self.pending = Some((modified, Instant::now()));
        }
        if !stable_read(&before, &after, bytes.len()) {
            return Ok(None);
        }
        let command = std::str::from_utf8(&bytes)
            .ok()
            .and_then(|text| parse(text.trim()));
        // 旧版 App 直接写入已打开的文件。先等待空白或未完整写入的
        // 已领取文件完成，再清理稳定的无效内容。使用单调时钟期限，
        // 也能处理系统校时后 mtime 位于未来的情况。
        let stale = modified
            .and_then(|time| time.elapsed().ok())
            .is_some_and(|age| age >= INCOMPLETE_GRACE)
            || self
                .pending
                .as_ref()
                .is_some_and(|(_, since)| since.elapsed() >= INCOMPLETE_GRACE);
        if command.is_some() || stale {
            self.finish()?;
        }
        Ok(command)
    }

    fn finish(&mut self) -> io::Result<()> {
        // 这里不能删除待领取文件：读取已领取文件中的旧 start 命令时，
        // App 可能已经向待领取文件写入了 stop 命令。
        fs::remove_file(&self.claimed)?;
        self.pending = None;
        Ok(())
    }

    pub(crate) fn wait_timeout(&self, idle: Duration) -> Duration {
        if self.pending.is_some() {
            idle.min(INCOMPLETE_GRACE)
        } else {
            idle
        }
    }
}

fn stable_read(before: &Metadata, after: &Metadata, bytes: usize) -> bool {
    before.len() == after.len()
        && before.modified().ok() == after.modified().ok()
        && after.len() == bytes as u64
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum CalibrationCommand {
    Start(String),
    Stop(Option<String>),
}

impl CalibrationCommand {
    pub(crate) fn parse(command: &str) -> Option<Self> {
        let command = command.trim();
        let (kind, value) = command
            .split_once(' ')
            .map_or((command, ""), |(kind, value)| (kind, value.trim()));
        match kind {
            "start" if valid_package(value, true) => Some(Self::Start(value.to_owned())),
            "stop" if value.is_empty() => Some(Self::Stop(None)),
            "stop" if valid_package(value, true) => Some(Self::Stop(Some(value.to_owned()))),
            _ => None,
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) struct FpsRequest {
    pub(crate) pkg: String,
    pub(crate) socket: Option<String>,
    pub(crate) token: Option<String>,
}

#[derive(Debug, Eq, PartialEq)]
pub(crate) enum FpsCommand {
    Start(FpsRequest),
    Stop,
}

impl FpsCommand {
    pub(crate) fn parse(command: &str) -> Option<Self> {
        let command = command.trim();
        if command == "stop" || command.starts_with("stop ") {
            // 旧版 FPS 的 stop 是全局命令，不像校准命令那样归属于指定包名。
            return Some(Self::Stop);
        }
        let mut parts = command.strip_prefix("start ")?.split_whitespace();
        let value = parts.next()?.split('#').next()?.trim();
        let pkg = value.split(':').next()?.trim();
        if !valid_package(pkg, false) {
            return None;
        }
        // 保留原有可选 socket/token 处理及文件输出回退。
        Some(Self::Start(FpsRequest {
            pkg: pkg.to_owned(),
            socket: parts.next().map(str::to_owned),
            token: parts.next().map(str::to_owned),
        }))
    }
}

fn valid_package(pkg: &str, allow_colon: bool) -> bool {
    !pkg.is_empty()
        && pkg.len() <= 255
        && pkg.contains('.')
        && pkg.bytes().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(byte, b'_' | b'.')
                || (allow_colon && byte == b':')
        })
}

#[cfg(test)]
mod tests;
