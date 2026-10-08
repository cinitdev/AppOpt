use crate::{pmu::Pmu, Collector};
use std::{
    io::{self, Write},
    time::{Duration, Instant},
};

/// 每次有界读取前先轮询，包括尚未接收完整的命令；遇到 EOF 或空闲 15 秒后释放所有文件描述符。
fn request(fd: i32) -> io::Result<bool> {
    let deadline = Instant::now() + Duration::from_secs(15);
    let mut command = Vec::with_capacity(16);
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Ok(false);
        }
        let mut poll = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let ready = unsafe { libc::poll(&mut poll, 1, left.as_millis().min(15000) as i32) };
        if ready == 0 {
            return Ok(false);
        }
        if ready < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            } else {
                return Err(err);
            }
        }
        let mut byte = 0u8;
        let count = unsafe { libc::read(fd, (&mut byte as *mut u8).cast(), 1) };
        if count == 0 {
            return Ok(false);
        }
        if count < 0 {
            return Err(io::Error::last_os_error());
        }
        if byte == b'\n' {
            return Ok(command == b"sample");
        }
        if command.len() >= 16 {
            return Ok(false);
        }
        command.push(byte);
    }
}
pub fn run() -> io::Result<()> {
    // 低优先级读取器不改变应用或守护进程的调度、亲和性和 CPU 频率。
    unsafe {
        libc::nice(10);
    }
    let mut collector = Collector::new("/");
    let mut pmu = Pmu::new();
    let start = Instant::now();
    let stdout = io::stdout();
    let mut out = stdout.lock();
    writeln!(out, "QIXIA_METRICS 1")?;
    out.flush()?;
    let mut previous = None;
    while request(libc::STDIN_FILENO)? {
        let now = start.elapsed().as_millis() as u64;
        if previous.is_some_and(|t| now - t < 750) {
            break;
        } // 拒绝意外进入忙循环的客户端。
        let mut sample = collector.sample(now);
        collector.add_cycles(&mut sample, &pmu.sample(now));
        writeln!(out, "BEGIN")?;
        writeln!(
            out,
            "charging|{}",
            match sample.charging {
                Some(true) => "1",
                Some(false) => "0",
                None => "?",
            }
        )?;
        for (key, value) in sample.values {
            writeln!(out, "{key}|{value:.6}")?;
        }
        writeln!(out, "END")?;
        out.flush()?;
        previous = Some(now);
    }
    Ok(())
}
