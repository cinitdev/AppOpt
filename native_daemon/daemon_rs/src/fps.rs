use std::time::Duration;
#[path = "fps_core/manual_gate.rs"]
mod manual_gate;

#[derive(Default)]
struct FrameProgress {
    generation: u64,
    timestamp_ns: u64,
}

impl FrameProgress {
    fn observe(&mut self, generation: u64, timestamp_ns: u64) -> bool {
        if generation == 0 || timestamp_ns == 0 {
            return false;
        }
        if generation == self.generation && timestamp_ns <= self.timestamp_ns {
            return false;
        }
        self.generation = generation;
        self.timestamp_ns = timestamp_ns;
        true
    }
}

pub(crate) fn valid_fps_sample(fps: f64) -> Option<f64> {
    // 与探针 1 毫秒最小帧间隔及历史记录的有效范围保持一致；这不是屏幕刷新率限制。
    (fps.is_finite() && (0.0..=1_000.0).contains(&fps)).then_some(fps)
}

fn select_active_timestats_layer(
    previous: &[(String, i64)],
    current: &[(String, i64)],
    preferred: Option<&str>,
) -> Option<usize> {
    let mut best: Option<(usize, i64)> = None;
    for (index, (name, frames)) in current.iter().enumerate() {
        let Some((_, old_frames)) = previous.iter().find(|(old_name, _)| old_name == name) else {
            continue;
        };
        let delta = frames.saturating_sub(*old_frames);
        if delta <= 0 {
            continue;
        }
        let replace = best.is_none_or(|(best_index, best_delta)| {
            delta > best_delta
                || (delta == best_delta
                    && preferred == Some(name.as_str())
                    && preferred != Some(current[best_index].0.as_str()))
        });
        if replace {
            best = Some((index, delta));
        }
    }
    best.map(|(index, _)| index)
        .or_else(|| {
            preferred.and_then(|preferred| {
                current
                    .iter()
                    .position(|(name, _)| name.as_str() == preferred)
            })
        })
        .or_else(|| (!current.is_empty()).then_some(0))
}

fn ebpf_restart_delay(failures: u32) -> Duration {
    match failures {
        0 | 1 => Duration::from_secs(3),
        2 => Duration::from_secs(10),
        _ => Duration::from_secs(30),
    }
}

fn latency_probe_delay(failures: u32) -> Option<Duration> {
    match failures {
        0..=2 => Some(Duration::ZERO),
        3..=4 => Some(Duration::from_secs(1)),
        _ => None,
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[path = "fps_core/mod.rs"]
mod imp;

#[cfg(not(any(target_os = "android", target_os = "linux")))]
mod imp {
    pub fn start_fps_thread() -> Option<std::thread::JoinHandle<()>> {
        None
    }
}

pub(crate) use imp::start_fps_thread;
