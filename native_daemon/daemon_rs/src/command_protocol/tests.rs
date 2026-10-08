use super::*;
use std::io::Write;
use std::sync::atomic::{AtomicU64, Ordering};

struct Mailbox {
    dir: PathBuf,
    reader: CommandFile,
}

impl Mailbox {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "qixia-command-{}-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&dir).unwrap();
        let reader = CommandFile::new(dir.join("request.cmd"));
        Self { dir, reader }
    }

    fn write(&self, bytes: impl AsRef<[u8]>) {
        fs::write(&self.reader.incoming, bytes).unwrap();
    }

    fn age_claim(&self) {
        File::options()
            .write(true)
            .open(&self.reader.claimed)
            .unwrap()
            .set_modified(SystemTime::UNIX_EPOCH)
            .unwrap();
    }
}

impl Drop for Mailbox {
    fn drop(&mut self) {
        for path in [&self.reader.incoming, &self.reader.claimed] {
            let _ = fs::remove_file(path);
            let _ = fs::remove_dir(path);
        }
        let _ = fs::remove_dir(&self.dir);
    }
}

#[test]
fn calibration_commands_keep_package_ownership() {
    assert_eq!(
        CalibrationCommand::parse(" start com.example:child\r\n"),
        Some(CalibrationCommand::Start("com.example:child".into()))
    );
    assert_eq!(
        CalibrationCommand::parse("stop com.example:child"),
        Some(CalibrationCommand::Stop(Some("com.example:child".into())))
    );
    assert_eq!(
        CalibrationCommand::parse("stop"),
        Some(CalibrationCommand::Stop(None))
    );
    for value in [
        "start",
        "start bad;state",
        "stop other package",
        "start com.example socket token",
    ] {
        assert_eq!(CalibrationCommand::parse(value), None, "{value}");
    }
    assert_eq!(
        CalibrationCommand::parse(&format!("start com.{}", "a".repeat(252))),
        None
    );
}

#[test]
fn fps_commands_keep_socket_token_and_base_package() {
    assert_eq!(
        FpsCommand::parse("start com.example:render qixia_socket abc123\r\n"),
        Some(FpsCommand::Start(FpsRequest {
            pkg: "com.example".into(),
            socket: Some("qixia_socket".into()),
            token: Some("abc123".into())
        }))
    );
    assert_eq!(
        FpsCommand::parse("start com.example"),
        Some(FpsCommand::Start(FpsRequest {
            pkg: "com.example".into(),
            socket: None,
            token: None
        }))
    );
    // 旧版请求只带 socket 时，仍会回退到 FPS 文件。
    assert_eq!(
        FpsCommand::parse("start com.example socket"),
        Some(FpsCommand::Start(FpsRequest {
            pkg: "com.example".into(),
            socket: Some("socket".into()),
            token: None
        }))
    );
    assert_eq!(FpsCommand::parse("stop"), Some(FpsCommand::Stop));
    assert_eq!(
        FpsCommand::parse("stop com.example"),
        Some(FpsCommand::Stop)
    );
    for value in ["start", "restart com.example", "start bad;state", "stopped"] {
        assert_eq!(FpsCommand::parse(value), None, "{value}");
    }
}

#[test]
fn missing_mailbox_keeps_the_long_idle_wait() {
    let mut mailbox = Mailbox::new();
    assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
    assert_eq!(
        mailbox.reader.wait_timeout(Duration::from_secs(30)),
        Duration::from_secs(30)
    );
}

#[test]
fn repeated_start_stop_is_consumed_once() {
    let mut mailbox = Mailbox::new();
    for _ in 0..20 {
        for text in ["start com.example", "stop"] {
            mailbox.write(text);
            assert_eq!(
                mailbox.reader.read(CalibrationCommand::parse).unwrap(),
                CalibrationCommand::parse(text)
            );
            assert!(!mailbox.reader.incoming.exists());
            assert!(!mailbox.reader.claimed.exists());
            assert_eq!(
                mailbox.reader.read(CalibrationCommand::parse).unwrap(),
                None
            );
        }
    }
}

#[test]
fn a_new_command_written_during_consumption_survives() {
    let mut mailbox = Mailbox::new();
    mailbox.write("start com.example");
    let incoming = mailbox.reader.incoming.clone();
    let command = mailbox
        .reader
        .read(|text| {
            fs::write(&incoming, "stop").unwrap();
            FpsCommand::parse(text)
        })
        .unwrap();
    assert_eq!(command, FpsCommand::parse("start com.example"));
    assert_eq!(fs::read_to_string(&incoming).unwrap(), "stop");
    assert_eq!(
        mailbox.reader.read(FpsCommand::parse).unwrap(),
        Some(FpsCommand::Stop)
    );
}

#[test]
fn restart_resumes_unread_claim_before_new_incoming() {
    let mut mailbox = Mailbox::new();
    mailbox.write("start com.example");
    fs::rename(&mailbox.reader.incoming, &mailbox.reader.claimed).unwrap();
    mailbox.write("stop com.example");
    mailbox.reader = CommandFile::new(mailbox.reader.incoming.clone());
    assert_eq!(
        mailbox.reader.read(CalibrationCommand::parse).unwrap(),
        CalibrationCommand::parse("start com.example")
    );
    assert_eq!(
        mailbox.reader.read(CalibrationCommand::parse).unwrap(),
        CalibrationCommand::parse("stop com.example")
    );
}

#[test]
fn direct_writer_can_finish_after_its_empty_file_was_claimed() {
    let mut mailbox = Mailbox::new();
    let mut writer = File::create(&mailbox.reader.incoming).unwrap();
    assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
    assert!(mailbox.reader.claimed.exists());
    assert_eq!(
        mailbox.reader.wait_timeout(Duration::from_secs(30)),
        INCOMPLETE_GRACE
    );
    writer.write_all(b"start com.example socket token").unwrap();
    drop(writer);
    assert_eq!(
        mailbox.reader.read(FpsCommand::parse).unwrap(),
        FpsCommand::parse("start com.example socket token")
    );
    assert_eq!(
        mailbox.reader.wait_timeout(Duration::from_secs(30)),
        Duration::from_secs(30)
    );
}

#[test]
fn invalid_claims_expire_without_deleting_the_next_command() {
    for bytes in [b"".as_slice(), b"\xff\xfe", b"unknown", b"start bad;state"] {
        let mut mailbox = Mailbox::new();
        mailbox.write(bytes);
        assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
        assert!(mailbox.reader.claimed.exists());
        mailbox.write("stop");
        mailbox.age_claim();
        assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
        assert!(!mailbox.reader.claimed.exists());
        assert_eq!(
            mailbox.reader.read(FpsCommand::parse).unwrap(),
            Some(FpsCommand::Stop)
        );
    }
}

#[test]
fn future_mtime_cannot_hold_a_stop_request_forever() {
    let mut mailbox = Mailbox::new();
    mailbox.write("unknown");
    let future = SystemTime::now() + Duration::from_secs(3600);
    File::options()
        .write(true)
        .open(&mailbox.reader.incoming)
        .unwrap()
        .set_modified(future)
        .unwrap();
    assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
    mailbox.write("stop");
    // 直接推进消费者的单调时钟宽限期，避免测试等待真实时间。
    mailbox.reader.pending.as_mut().unwrap().1 -= INCOMPLETE_GRACE;
    assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
    assert_eq!(
        mailbox.reader.read(FpsCommand::parse).unwrap(),
        Some(FpsCommand::Stop)
    );
}

#[test]
fn oversized_commands_are_discarded_for_both_consumers() {
    for fps in [true, false] {
        let mut mailbox = Mailbox::new();
        mailbox.write("start com.example");
        File::options()
            .write(true)
            .open(&mailbox.reader.incoming)
            .unwrap()
            .set_len(2 * 1024 * 1024)
            .unwrap();
        if fps {
            assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
        } else {
            assert_eq!(
                mailbox.reader.read(CalibrationCommand::parse).unwrap(),
                None
            );
        }
        assert!(!mailbox.reader.claimed.exists());
        mailbox.write("stop");
        assert_eq!(
            mailbox.reader.read(FpsCommand::parse).unwrap(),
            Some(FpsCommand::Stop)
        );
    }
}

#[test]
fn fps_socket_request_at_byte_limit_is_accepted() {
    let mut mailbox = Mailbox::new();
    let prefix = "start com.example socket ";
    let text = format!(
        "{prefix}{}",
        "t".repeat(MAX_COMMAND_BYTES as usize - prefix.len())
    );
    mailbox.write(&text);
    assert_eq!(
        mailbox.reader.read(FpsCommand::parse).unwrap(),
        FpsCommand::parse(&text)
    );
    mailbox.write(format!("{text}t"));
    assert_eq!(mailbox.reader.read(FpsCommand::parse).unwrap(), None);
    assert!(!mailbox.reader.claimed.exists());
}

#[test]
fn read_errors_retain_the_claim_for_retry() {
    let mut mailbox = Mailbox::new();
    fs::create_dir(&mailbox.reader.claimed).unwrap();
    mailbox.write("stop");
    assert_eq!(
        mailbox.reader.read(FpsCommand::parse).unwrap_err().kind(),
        io::ErrorKind::InvalidData
    );
    assert_eq!(
        fs::read_to_string(&mailbox.reader.incoming).unwrap(),
        "stop"
    );
    fs::remove_dir(&mailbox.reader.claimed).unwrap();
    assert_eq!(
        mailbox.reader.read(FpsCommand::parse).unwrap(),
        Some(FpsCommand::Stop)
    );
}

#[test]
fn changed_or_short_reads_are_not_stable_commands() {
    let mailbox = Mailbox::new();
    mailbox.write("start");
    let before = fs::metadata(&mailbox.reader.incoming).unwrap();
    mailbox.write("start com.example");
    let after = fs::metadata(&mailbox.reader.incoming).unwrap();
    assert!(!stable_read(&before, &after, 5));
    assert!(!stable_read(&after, &after, 5));
    assert!(stable_read(&after, &after, "start com.example".len()));
}
