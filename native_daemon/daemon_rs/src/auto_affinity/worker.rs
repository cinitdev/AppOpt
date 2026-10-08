//! 分配器使用独立计时器。FPS 发布可选观测时不持有分配互斥锁，
//! 因此慢速 Binder/dumpsys 请求不会延迟核心分配。
use super::{boot_id, lock, Sample};
use std::{
    fs, io,
    sync::{Mutex, OnceLock},
    thread,
    time::Duration,
};

const FOREGROUND: &str = "/data/adb/modules/QixiaThreads/config/foreground_task.state";
const CALIBRATION: &str = "/data/adb/modules/QixiaThreads/config/calibrate.state";
static FEEDBACK: OnceLock<Mutex<Option<(String, Sample)>>> = OnceLock::new();

pub(crate) fn publish_feedback(pkg: Option<&str>, sample: Option<Sample>) {
    let mut slot = FEEDBACK
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    match (pkg, sample) {
        (Some(pkg), Some(sample)) => match slot.as_mut() {
            Some((previous, value)) if previous == pkg => *value = sample,
            _ => *slot = Some((pkg.to_owned(), sample)),
        },
        _ => *slot = None,
    }
}

fn feedback(pkg: &str) -> Option<Sample> {
    FEEDBACK
        .get()?
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .as_ref()
        .filter(|(owner, _)| owner == pkg)
        .map(|(_, sample)| *sample)
}

pub(crate) fn start(cpuset_name: &str) -> io::Result<thread::JoinHandle<()>> {
    super::cpuset::configure(cpuset_name)?;
    if let Err(error) = super::recover_journal(None) {
        log_error!("[auto] 遗留亲和性恢复未完成: {error}");
    }
    thread::Builder::new()
        .name("QiXiaRsAffinity".into())
        .spawn(run)
}

fn run() {
    let mut events = crate::CommandFileMonitor::new(&[FOREGROUND, CALIBRATION, super::CONFIG]).ok();
    let boot = boot_id().unwrap_or_default();
    while !crate::shutdown_requested() {
        let foreground = fs::read_to_string(FOREGROUND).ok().and_then(|raw| {
            super::super::foreground_package(&raw, crate::elapsed_realtime_ms(), &boot)
        });
        let (timeout, histories, core_events) = {
            let mut runtime = lock();
            // 切换到未选中的应用，不会关闭旧应用的设置；
            // 实际模式变化由 refresh_config 记录。
            let stop_reason = "foreground_left";
            let target = foreground.filter(|pkg| runtime.wants_allocation(pkg));
            let timeout = match target {
                Some(pkg) => {
                    runtime.tick(&pkg, feedback(&pkg));
                    runtime.next_sample_delay().max(Duration::from_millis(50))
                }
                None => {
                    runtime.stop_with_reason("目标离开前台或自动分配已关闭，恢复系统调度", stop_reason);
                    // 另一个工作线程可能在更新选择前先消耗唤醒信号，
                    // 此时不能漏掉前台或配置交接。
                    if runtime.selected.is_empty() && !runtime.config_handoff_pending() {
                        Duration::from_secs(10)
                    } else { Duration::from_secs(1) }
                }
            };
            let mut histories = Vec::new();
            while let Some(history) = runtime.take_history() { histories.push(history); }
            (timeout, histories, runtime.take_core_events())
        };
        for history in histories { crate::auto_history::publish_threads(history); }
        for (package, events) in core_events { crate::auto_history::publish_core_events(&package, events); }
        crate::wait_for_command_files(&mut events, timeout, timeout.min(Duration::from_secs(1)));
    }
    let (histories, core_events) = {
        let mut runtime = lock();
        runtime.stop("守护进程退出");
        let mut histories = Vec::new();
        while let Some(history) = runtime.take_history() { histories.push(history); }
        (histories, runtime.take_core_events())
    };
    for history in histories { crate::auto_history::publish_threads(history); }
    for (package, events) in core_events { crate::auto_history::publish_core_events(&package, events); }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn feedback_never_crosses_foreground_packages_and_can_be_cleared() {
        publish_feedback(
            Some("com.first"),
            Some(Sample {
                pid: 1,
                ..Sample::default()
            }),
        );
        assert_eq!(feedback("com.first").unwrap().pid, 1);
        assert!(feedback("com.other").is_none());
        publish_feedback(None, None);
        assert!(feedback("com.first").is_none());
        // FPS 发布不能等待静态规则写入或一轮核心分配；
        // 工作线程之间只共享一个很小的独立槽位。
        let runtime = lock();
        let (done, received) = std::sync::mpsc::channel();
        let worker = thread::spawn(move || {
            publish_feedback(
                Some("com.first"),
                Some(Sample {
                    pid: 2,
                    ..Sample::default()
                }),
            );
            done.send(()).unwrap();
        });
        received
            .recv_timeout(Duration::from_secs(2))
            .expect("FPS feedback blocked on allocation");
        drop(runtime);
        worker.join().unwrap();
        assert_eq!(feedback("com.first").unwrap().pid, 2);
        publish_feedback(None, None);
    }
}
