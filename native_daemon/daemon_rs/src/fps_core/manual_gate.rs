//! 为用户明确请求的 FPS 监测提供短暂恢复窗口，不用于核心亲和性分配。
use std::time::{Duration, Instant};

#[derive(Default)]
pub(super) struct ManualFallback {
    since: Option<Instant>,
    checked: Option<Instant>,
    allowed: bool,
}
impl ManualFallback {
    pub(super) fn reset(&mut self) {
        *self = Self::default();
    }
    pub(super) fn allows(&mut self, now: Instant, probe: impl FnOnce() -> bool) -> bool {
        let since = *self.since.get_or_insert(now);
        // 屏幕或前台状态未知时，不能无限期维持监测器运行。
        if now.duration_since(since) >= Duration::from_secs(30) {
            return false;
        }
        if self
            .checked
            .is_none_or(|t| now.duration_since(t) >= Duration::from_secs(2))
        {
            self.checked = Some(now);
            self.allowed = probe();
        }
        self.allowed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fallback_is_rate_limited_and_expires_even_with_positive_probes() {
        let mut gate = ManualFallback::default();
        let now = Instant::now();
        assert!(gate.allows(now, || true));
        assert!(gate.allows(now + Duration::from_millis(500), || panic!(
            "probe repeated"
        )));
        assert!(!gate.allows(now + Duration::from_secs(2), || false));
        assert!(!gate.allows(now + Duration::from_secs(30), || panic!("expired probe")));
        gate.reset();
        assert!(gate.allows(now + Duration::from_secs(31), || true));
    }
}
