use std::collections::BTreeSet;

#[derive(Clone, Debug, PartialEq)]
pub(super) struct Core {
    pub id: usize,
    pub capacity: u64,
}

pub(super) fn has_capacity_info(cores: &[Core], allowed: u64) -> bool {
    if allowed == 0 || cores.iter().any(|core| core.id >= 64) {
        return false;
    }
    let mut ids = BTreeSet::new();
    let mut known = 0;
    for core in cores.iter().filter(|core| allowed & (1u64 << core.id) != 0) {
        if !(1..=1_000_000).contains(&core.capacity) || !ids.insert(core.id) {
            return false;
        }
        known |= 1u64 << core.id;
    }
    known == allowed
}

pub(super) fn read_cores() -> Vec<Core> {
    qixia_kernel_info::cpu::cores(std::path::Path::new("/"))
        .into_iter()
        .filter(|c| c.id < 64)
        .map(|c| Core {
            id: c.id,
            capacity: c.capacity.unwrap_or(0),
        })
        .collect()
}
