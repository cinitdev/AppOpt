//! 两类历史统一按结束时间保留七天，不限制会话数量。
pub(crate) const MAX_AGE_MS: u64 = 7 * 24 * 60 * 60 * 1000;
pub(crate) fn expired(ended_ms: u64, now_ms: u64) -> bool {
    ended_ms > 0 && ended_ms < now_ms.saturating_sub(MAX_AGE_MS)
}
