//! 共用有时间与输出上限的系统服务读取，不经过 Shell，也不写临时文件。
use std::{
    io::{self, Read},
    process::{Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

pub(crate) fn bounded(service: &str, args: &[&str]) -> io::Result<String> {
    const MAX_OUTPUT: usize = 1 << 20;
    const TIMEOUT: Duration = Duration::from_millis(1500);

    let mut child = Command::new("/system/bin/dumpsys")
        .arg(service)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut stdout = child.stdout.take().ok_or_else(|| {
        io::Error::new(io::ErrorKind::BrokenPipe, "dumpsys stdout unavailable")
    })?;
    let (reader_tx, reader_rx) = mpsc::sync_channel(1);
    let _reader = match thread::Builder::new()
        .name("QixiaThreadsDump".to_string())
        .spawn(move || {
            let result = (|| {
                let mut output = Vec::with_capacity(MAX_OUTPUT.min(256 * 1024));
                let mut chunk = [0u8; 8192];
                loop {
                    let read = stdout.read(&mut chunk)?;
                    if read == 0 {
                        break;
                    }
                    let remaining = MAX_OUTPUT.saturating_sub(output.len());
                    output.extend_from_slice(&chunk[..read.min(remaining)]);
                }
                Ok::<Vec<u8>, io::Error>(output)
            })();
            let _ = reader_tx.send(result);
        }) {
        Ok(reader) => reader,
        Err(err) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err(err);
        }
    };

    let started = Instant::now();
    let (status, timed_out) = loop {
        match child.try_wait() {
            Ok(Some(status)) => break (status, false),
            Ok(None) if started.elapsed() < TIMEOUT => {
                thread::sleep(Duration::from_millis(20));
            }
            Ok(None) => {
                let _ = child.kill();
                let status = child.wait()?;
                break (status, true);
            }
            Err(err) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(err);
            }
        }
    };
    let output = reader_rx
        .recv_timeout(Duration::from_millis(750))
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::TimedOut,
                "dumpsys stdout reader did not close after process exit",
            )
        })??;
    if timed_out {
        return Err(io::Error::new(
            io::ErrorKind::TimedOut,
            format!("dumpsys {service} timeout"),
        ));
    }
    if !status.success() {
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!("dumpsys {service} exit status {status}"),
        ));
    }
    Ok(String::from_utf8_lossy(&output).into_owned())
}
