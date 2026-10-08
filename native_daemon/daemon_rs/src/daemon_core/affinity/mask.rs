use crate::CPU_MASK_WORDS;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CpuMask {
    pub(super) words: [u64; CPU_MASK_WORDS],
}
impl CpuMask {
    pub(crate) fn count(&self) -> u32 {
        self.words.iter().map(|word| word.count_ones()).sum()
    }

    pub(crate) fn empty() -> Self {
        Self {
            words: [0; CPU_MASK_WORDS],
        }
    }

    pub(crate) fn parse(input: &str) -> Option<Self> {
        let mut mask = Self::empty();
        let mut any = false;

        for part in input.split(',') {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }

            let (start, end) = if let Some((left, right)) = part.split_once('-') {
                let start = left.trim().parse::<usize>().ok()?;
                let end = right.trim().parse::<usize>().ok()?;
                if start > end {
                    return None;
                }
                (start, end)
            } else {
                let cpu = part.parse::<usize>().ok()?;
                (cpu, cpu)
            };

            for cpu in start..=end {
                mask.set(cpu)?;
                any = true;
            }
        }

        if any {
            Some(mask)
        } else {
            None
        }
    }

    pub(crate) fn set(&mut self, cpu: usize) -> Option<()> {
        let word = cpu / 64;
        if word >= CPU_MASK_WORDS {
            return None;
        }
        let bit = cpu % 64;
        self.words[word] |= 1u64 << bit;
        Some(())
    }

    pub(crate) fn or_assign(&mut self, other: &Self) {
        for (left, right) in self.words.iter_mut().zip(other.words.iter()) {
            *left |= *right;
        }
    }

    pub(crate) fn intersection(&self, other: &Self) -> Self {
        let mut mask = Self::empty();
        for ((out, left), right) in mask
            .words
            .iter_mut()
            .zip(self.words.iter())
            .zip(other.words.iter())
        {
            *out = left & right;
        }
        mask
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.words.iter().all(|word| *word == 0)
    }

    pub(crate) fn to_list(&self) -> String {
        let mut ranges = Vec::new();
        let mut cpu = 0usize;
        let max = CPU_MASK_WORDS * 64;

        while cpu < max {
            if !self.contains(cpu) {
                cpu += 1;
                continue;
            }

            let start = cpu;
            while cpu + 1 < max && self.contains(cpu + 1) {
                cpu += 1;
            }
            let end = cpu;
            if start == end {
                ranges.push(start.to_string());
            } else {
                ranges.push(format!("{start}-{end}"));
            }
            cpu += 1;
        }

        ranges.join(",")
    }

    pub(crate) fn contains(&self, cpu: usize) -> bool {
        let word = cpu / 64;
        if word >= CPU_MASK_WORDS {
            return false;
        }
        (self.words[word] & (1u64 << (cpu % 64))) != 0
    }

    pub(crate) fn is_subset_of(&self, other: &Self) -> bool {
        self.words
            .iter()
            .zip(other.words.iter())
            .all(|(left, right)| (left & !right) == 0)
    }

    pub(crate) fn to_low64(&self) -> Option<u64> {
        self.words[1..]
            .iter()
            .all(|word| *word == 0)
            .then_some(self.words[0])
    }

    pub(crate) fn from_low64(value: u64) -> Self {
        let mut mask = Self::empty();
        mask.words[0] = value;
        mask
    }
}
