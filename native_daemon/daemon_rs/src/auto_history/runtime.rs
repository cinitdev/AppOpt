use super::{
    config_enabled,
    core_events::{arm, Batch, Queue},
    storage::{self, Capture},
    CoreEvent, ThreadBatch, CONFIG, STATUS,
};
use qixia_history_probe::{pmu::Pmu, Collector};
use std::{
    collections::VecDeque,
    fs, io,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, SyncSender},
        Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const FOREGROUND: &str = "/data/adb/modules/QixiaThreads/config/foreground_task.state";
const CALIBRATION: &str = "/data/adb/modules/QixiaThreads/config/calibrate.state";
const PERIOD: Duration = Duration::from_secs(2);
static RECORDING: AtomicBool = AtomicBool::new(false);
static TARGET: OnceLock<Mutex<Option<String>>> = OnceLock::new();
static SENDER: OnceLock<SyncSender<Event>> = OnceLock::new();
static CORE_QUEUE: OnceLock<Mutex<Queue>> = OnceLock::new();
const CLOSE_GRACE: Duration = Duration::from_secs(3);
const MAX_CLOSING: usize = 4;

enum Event {
    Threads(ThreadBatch),
    Fps {
        package: String,
        timestamp_ms: u64,
        fps: f64,
        frame_max_ms: f64,
    },
}

pub(crate) fn wants_threads() -> bool {
    RECORDING.load(Ordering::Relaxed)
}
pub(crate) fn recording_package() -> Option<String> {
    if !wants_threads() {
        return None;
    }
    TARGET
        .get()?
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}
pub(crate) fn recording_for(package: &str) -> bool {
    wants_threads()
        && TARGET.get().is_some_and(|slot| {
            slot.lock().unwrap_or_else(|e| e.into_inner()).as_deref() == Some(package)
        })
}
pub(crate) fn publish_threads(batch: ThreadBatch) {
    if wants_threads() {
        if let Some(sender) = SENDER.get() {
            let _ = sender.try_send(Event::Threads(batch));
        }
    }
}
pub(crate) fn core_events_for(package: &str) -> bool {
    CORE_QUEUE.get().is_some_and(|queue| {
        queue
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .accepts(package)
    })
}
/// 返回正在采集或准备采集的会话身份，特意排除正在关闭的流。
/// 此接口不请求探测；探测仍由 `recording_for` 控制。
pub(crate) fn current_core_session(package: &str) -> Option<u64> {
    CORE_QUEUE
        .get()?
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .current(package)
}
pub(crate) fn publish_core_events(package: &str, events: Vec<CoreEvent>) {
    if let Some(queue) = CORE_QUEUE.get() {
        queue
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .publish(package, events);
    }
}
pub(crate) fn publish_fps(package: &str, fps: f64, frame_max_ms: f64) {
    if recording_for(package) {
        if let Some(sender) = SENDER.get() {
            let _ = sender.try_send(Event::Fps {
                package: package.into(),
                timestamp_ms: epoch_ms(),
                fps,
                frame_max_ms,
            });
        }
    }
}

fn set_target(package: Option<&str>) {
    if package.is_none() {
        if let Some(queue) = CORE_QUEUE.get() {
            queue.lock().unwrap_or_else(|e| e.into_inner()).deactivate();
        }
    }
    let mut slot = TARGET
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if slot.as_deref() == package {
        return;
    }
    *slot = package.map(str::to_owned);
    RECORDING.store(package.is_some(), Ordering::Relaxed);
    drop(slot);
    // 状态转换通知只唤醒一次共享 FPS 工作线程，不写入采样心跳文件；
    // 仅用于历史的 FPS 不单独输出到 socket 或文件。
    let tmp = format!("{STATUS}.tmp");
    if fs::write(
        &tmp,
        format!(
            "active={}\npackage={}\n",
            u8::from(package.is_some()),
            package.unwrap_or_default()
        ),
    )
    .is_ok()
    {
        let _ = fs::rename(tmp, STATUS);
    }
}

fn epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn new_recording(storage: &crate::private_storage::Storage, package: &str, start_ms: u64) -> io::Result<Capture> {
    // 空目录清理可能刚好先于首个 .tmp 的创建完成。重试时沿用原 App 归属和标签，
    // 不能将已写入部分内容的采集作为新会话继续。
    for attempt in 0..3 {
        let directory = storage.prepare("auto_history")?;
        match Capture::new(&directory, package, start_ms) {
            Ok(mut recording) => { recording.device(&device_info())?; return Ok(recording); }
            Err(error) if error.kind() == io::ErrorKind::NotFound && attempt < 2 => continue,
            Err(error) => return Err(error),
        }
    }
    unreachable!()
}

fn device_info() -> Vec<(String, String)> {
    #[cfg(target_os = "android")]
    {
        fn property(name: &std::ffi::CStr) -> Option<String> {
            let mut bytes = [0 as libc::c_char; 92];
            let length = unsafe { libc::__system_property_get(name.as_ptr(), bytes.as_mut_ptr()) };
            if length <= 0 || length as usize >= bytes.len() {
                return None;
            }
            let value = unsafe { std::ffi::CStr::from_ptr(bytes.as_ptr()) }
                .to_string_lossy()
                .trim()
                .to_owned();
            (!value.is_empty() && value != "unknown").then_some(value)
        }
        let mut fields = Vec::new();
        if let Some(platform) = property(c"ro.soc.model").or_else(|| property(c"ro.board.platform"))
        {
            fields.push(("platform".into(), platform));
        }
        if let Some(model) = property(c"ro.product.model") {
            fields.push(("model".into(), model));
        }
        if let Some(os) = property(c"ro.build.version.release") {
            fields.push(("os".into(), format!("Android {os}")));
        }
        fields
    }
    #[cfg(not(target_os = "android"))]
    {
        Vec::new()
    }
}

fn read_small(path: &str, maximum: usize) -> Option<String> {
    use std::io::Read;
    let mut text = String::new();
    fs::File::open(path)
        .ok()?
        .take(maximum as u64 + 1)
        .read_to_string(&mut text)
        .ok()?;
    (text.len() <= maximum).then_some(text)
}

fn target(boot: &str) -> Option<String> {
    let config = read_small(CONFIG, 64 * 1024)?;
    if !config_enabled(&config) {
        return None;
    }
    // 首次校准前没有校准状态文件是正常情况；其他读取错误按停止采集处理，
    // 避免损坏的状态引发重复采集。
    let calibration = match fs::read_to_string(CALIBRATION) {
        Ok(raw) => raw,
        Err(error) if error.kind() != io::ErrorKind::NotFound => return None,
        _ => String::new(),
    };
    super::eligible_target(
        &config,
        &calibration,
        &read_small(FOREGROUND, 16 * 1024)?,
        &read_small(crate::auto_affinity::CONFIG, 64 * 1024)?,
        crate::elapsed_realtime_ms(),
        boot,
    )
}

struct Session {
    recording: Capture,
    directory: PathBuf,
    collector: Collector,
    pmu: Pmu,
    pending: super::window::Window,
    last_metrics: Option<Instant>,
    probe_started: Instant,
    core_id: u64,
}
impl Session {
    fn new(package: &str) -> io::Result<Self> {
        let storage = crate::private_storage::resolve()?;
        let directory = storage.directory("auto_history");
        let start_ms = epoch_ms();
        let (core_id, recording) = arm(
            CORE_QUEUE.get_or_init(Default::default),
            package,
            start_ms,
            || new_recording(&storage, package, start_ms),
        )?;
        Ok(Self {
            recording,
            directory,
            collector: Collector::new("/"),
            pmu: Pmu::new(),
            pending: Default::default(),
            last_metrics: None,
            probe_started: Instant::now(),
            core_id,
        })
    }
    fn sample(&mut self) -> io::Result<()> {
        if self
            .last_metrics
            .is_some_and(|last| last.elapsed() < PERIOD)
        {
            return Ok(());
        }
        self.last_metrics = Some(Instant::now());
        self.recording.checkpoint()?;
        if self.recording.full() {
            self.recording.rotate_with_threads(
                &self.directory,
                epoch_ms(),
                &mut self.pending,
            )?;
        }
        let elapsed = self.probe_started.elapsed().as_millis() as u64;
        let mut sample = self.collector.sample(elapsed);
        self.collector
            .add_cycles(&mut sample, &self.pmu.sample(elapsed));
        self.recording.metrics(epoch_ms(), &sample)?;
        if let Some((timestamp_ms, threads)) = self.pending.take() {
            self.recording.write_threads(&self.directory, timestamp_ms, &threads)?;
        }
        self.recording.flush_if_due()
    }
    fn accept(&mut self, event: Event) -> io::Result<()> {
        match event {
            Event::Threads(batch)
                if batch.package == self.recording.package
                    && self.recording.accepts(batch.timestamp_ms) =>
            {
                self.recording.changes(batch.timestamp_ms, &batch.changes)?;
                self.pending.observe(batch.timestamp_ms, batch.threads);
            }
            Event::Fps {
                package,
                timestamp_ms,
                fps,
                frame_max_ms,
            } if package == self.recording.package => {
                self.recording.fps(timestamp_ms, fps, frame_max_ms)?
            }
            _ => {}
        }
        Ok(())
    }
    fn core_batch(&mut self, batch: Batch) -> io::Result<()> {
        self.recording.core_loss(batch.dropped, false);
        for (index, event) in batch.events.iter().enumerate() {
            if self.recording.full() {
                if let Err(error) = self
                    .recording
                    .rotate_with_threads(&self.directory, event.timestamp_ms, &mut self.pending)
                {
                    self.recording
                        .core_loss((batch.events.len() - index) as u64, true);
                    return Err(error);
                }
            }
            if let Err(error) = self.recording.core_event(event) {
                self.recording
                    .core_loss((batch.events.len() - index - 1) as u64, true);
                return Err(error);
            }
        }
        Ok(())
    }
    fn drain_core(&mut self, close: bool) -> io::Result<()> {
        let batch = {
            let mut queue = CORE_QUEUE
                .get_or_init(Default::default)
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if close {
                queue.close(self.core_id)
            } else {
                queue.drain(self.core_id, 2048)
            }
        };
        self.core_batch(batch)
    }
    fn finish(mut self, incomplete: bool) -> io::Result<()> {
        self.recording.stop_usage();
        self.recording.core_loss(0, incomplete);
        let mut error = self.drain_core(true).err();
        if let Some((timestamp_ms, threads)) = self.pending.take() {
            if let Err(failed) = self.recording.write_threads(&self.directory, timestamp_ms, &threads) {
                self.recording.core_loss(0, true);
                if error.is_none() {
                    error = Some(failed);
                }
            }
        }
        self.recording.finish(epoch_ms())?;
        let _ = fs::remove_dir(&self.directory); // 仅删除空收件目录，不删除活跃 .tmp 文件。
        error.map_or(Ok(()), Err)
    }
}

pub(crate) fn start() -> io::Result<thread::JoinHandle<()>> {
    let (sender, receiver) = mpsc::sync_channel(8);
    SENDER
        .set(sender)
        .map_err(|_| io::Error::other("auto history already running"))?;
    thread::Builder::new()
        .name("QiXiaRsHistory".into())
        .spawn(move || {
            // Linux nice 仅作用于本采集线程，不作用于亲和性线程或游戏。
            unsafe {
                libc::nice(10);
            }
            if let Ok(storage) = crate::private_storage::resolve() {
                if let Err(error) = storage::recover(&storage.directory("auto_history")) {
                    log_warn!("[history] 自动记录恢复失败: {error}");
                }
            }
            let boot = read_small("/proc/sys/kernel/random/boot_id", 128).unwrap_or_default();
            let mut events = crate::CommandFileMonitor::new(&[
                CONFIG,
                FOREGROUND,
                CALIBRATION,
                crate::auto_affinity::CONFIG,
                crate::private_storage::REGISTRATION,
            ])
            .ok();
            let mut session: Option<Session> = None;
            let mut closing: VecDeque<(Instant, Session)> = VecDeque::new();
            let mut retry_after: Option<Instant> = None;
            while !crate::shutdown_requested() {
                let wanted = target(boot.trim());
                if session.as_ref().map(|s| s.recording.package.as_str()) != wanted.as_deref() {
                    set_target(None);
                    if let Some(mut old) = session.take() {
                        // 等待释放事件的宽限期不计入前台使用时长。
                        old.recording.stop_usage();
                        for _ in 0..8 {
                            let Ok(event) = receiver.try_recv() else {
                                break;
                            };
                            if old.accept(event).is_err() {
                                old.recording.core_loss(0, true);
                                break;
                            }
                        }
                        // 该包名此时只保持事件接收。
                        // 宽限期内不请求传感器或线程探测。
                        closing.push_back((Instant::now() + CLOSE_GRACE, old));
                        if closing.len() > MAX_CLOSING {
                            let (_, oldest) = closing.pop_front().unwrap();
                            if let Err(error) = oldest.finish(true) {
                                log_warn!("[history] 快速切换保存失败: {error}");
                            }
                        }
                    }
                    // 所有旧观测都属于已经结束的前台分段。
                    while receiver.try_recv().is_ok() {}
                }
                if let Some(package) = wanted
                    .as_deref()
                    .filter(|_| retry_after.is_none_or(|until| Instant::now() >= until))
                {
                    if session.is_none() {
                        match Session::new(package) {
                            Ok(new) => {
                                session = Some(new);
                                retry_after = None;
                                set_target(Some(package));
                            }
                            Err(error) => {
                                log_warn!("[history] 自动记录无法启动，60 秒后重试: {error}");
                                retry_after = Some(Instant::now() + Duration::from_secs(60));
                            }
                        }
                    }
                }
                let result = (|| -> io::Result<()> {
                    if let Some(active) = session.as_mut() {
                        // 生产者队列有界：慢传感器不会阻塞核心分配，也不会使内存持续增长。
                        // 丢弃的观测保持为空缺。
                        for _ in 0..8 {
                            let Ok(event) = receiver.try_recv() else {
                                break;
                            };
                            active.accept(event)?;
                        }
                        // 核心事件批次可能触发换段，需先接纳已排队的样本，
                        // 让换段流程能够刷出它们所属的窗口。
                        active.drain_core(false)?;
                        active.sample()?;
                    }
                    Ok(())
                })();
                if let Err(error) = result {
                    log_warn!("[history] 自动记录暂停，60 秒后重试: {error}");
                    set_target(None);
                    if let Some(old) = session.take() {
                        let _ = old.finish(true);
                    }
                    retry_after = Some(Instant::now() + Duration::from_secs(60));
                }
                let mut index = 0;
                while index < closing.len() {
                    let (deadline, old) = &mut closing[index];
                    let failed = old
                        .drain_core(false)
                        .and_then(|_| old.recording.flush_if_due())
                        .is_err();
                    if failed || Instant::now() >= *deadline {
                        let (_, old) = closing.remove(index).unwrap();
                        if let Err(error) = old.finish(failed) {
                            log_warn!("[history] 自动记录保存失败: {error}");
                        }
                    } else {
                        index += 1;
                    }
                }
                let timeout = if session.is_some() || !closing.is_empty() {
                    Duration::from_secs(1)
                } else {
                    Duration::from_secs(10)
                };
                crate::wait_for_command_files(
                    &mut events,
                    timeout,
                    timeout.min(Duration::from_secs(1)),
                );
            }
            set_target(None);
            if let Some(mut old) = session {
                old.recording.stop_usage();
                closing.push_back((Instant::now() + CLOSE_GRACE, old));
            }
            // 关停过程中亲和性线程仍可能完成释放。短暂保留发布入口，
            // 随后原子地关闭并排空队列。
            while let Some((deadline, _)) = closing.front() {
                if Instant::now() < *deadline {
                    thread::sleep(Duration::from_millis(50));
                    continue;
                }
                let (_, old) = closing.pop_front().unwrap();
                if let Err(error) = old.finish(false) {
                    log_warn!("[history] 退出保存自动记录失败: {error}");
                }
            }
        })
}
