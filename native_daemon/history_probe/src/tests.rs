use super::*;
use std::{
    fs as disk,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!(
            "qixia-metrics-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        )))
    }
    fn put(&self, path: &str, text: &str) {
        let file = self.0.join(path.trim_start_matches('/'));
        disk::create_dir_all(file.parent().unwrap()).unwrap();
        disk::write(file, text).unwrap();
    }
    fn policy(&self, id: u32, cores: &str, freq: &str) {
        self.put(
            &format!("/sys/devices/system/cpu/cpufreq/policy{id}/related_cpus"),
            cores,
        );
        self.put(
            &format!("/sys/devices/system/cpu/cpufreq/policy{id}/scaling_cur_freq"),
            freq,
        );
    }
    fn thermal(&self, id: u32, kind: &str, temp: &str) {
        self.put(&format!("/sys/class/thermal/thermal_zone{id}/type"), kind);
        self.put(&format!("/sys/class/thermal/thermal_zone{id}/temp"), temp);
    }
    fn battery(&self, status: &str, current: &str, voltage: &str) {
        for (k, v) in [
            ("type", "Battery"),
            ("status", status),
            ("current_now", current),
            ("voltage_now", voltage),
            ("temp", "327"),
            ("capacity", "71"),
        ] {
            self.put(&format!("/sys/class/power_supply/battery/{k}"), v);
        }
    }
    fn collector(&self) -> Collector {
        Collector::new(&self.0)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        if self.0.is_dir() {
            disk::remove_dir_all(&self.0).unwrap();
        }
    }
}
fn close(sample: &Sample, key: &str, expected: f64) {
    assert!(
        (sample.values[key] - expected).abs() < 0.01,
        "{key}: {:?}",
        sample.values.get(key)
    );
}

#[test]
fn qualcomm_uses_real_policy_ids_and_kgsl() {
    let f = Fixture::new();
    f.policy(0, "0-3", "1804800");
    f.policy(6, "6 7", "3187200");
    f.put("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "670000000");
    f.put("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "42 %");
    f.thermal(0, "cpu-0-0", "45500");
    f.thermal(1, "gpu", "43000");
    f.thermal(2, "skin", "99000");
    let sample = f.collector().sample(1);
    close(&sample, "cpu_mhz.6_7", 3187.2);
    close(&sample, "gpu_mhz", 670.0);
    close(&sample, "gpu_usage", 42.0);
    close(&sample, "cpu_c", 45.5);
}
#[test]
fn mediatek_ged_index_is_not_the_frequency_or_usage() {
    let f = Fixture::new();
    f.policy(0, "0-5", "2000000");
    f.policy(6, "6-7", "3300000");
    f.put("/sys/kernel/ged/hal/current_freqency", "3 900000");
    f.put("/sys/kernel/ged/hal/gpu_utilization", "65 10 25");
    f.put(
        "/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq",
        "4266000000",
    );
    f.thermal(0, "mtktscpu", "54000");
    f.thermal(1, "mtktsgpu", "50000");
    let sample = f.collector().sample(1);
    close(&sample, "gpu_mhz", 900.0);
    close(&sample, "gpu_usage", 65.0);
    close(&sample, "ddr_mhz", 2133.0);
    close(&sample, "gpu_c", 50.0);
}
#[test]
fn pixel_tensor_uses_mali_device_without_fixed_address() {
    let f = Fixture::new();
    f.put(
        "/sys/devices/platform/1f000000.mali/clock_info",
        "BASIC STATUS\n gpu0 clock (top level) : 400000 kHz\n gpu1 clock (shaders) : 850000 kHz\n",
    );
    f.put("/sys/devices/platform/1f000000.mali/utilization", "73");
    f.thermal(0, "BIG", "61000");
    f.thermal(1, "G3D", "57000");
    let sample = f.collector().sample(1);
    close(&sample, "gpu_mhz", 850.0);
    close(&sample, "gpu_usage", 73.0);
    close(&sample, "cpu_c", 61.0);
}
#[test]
fn generic_aosp_devfreq_and_legacy_per_cpu_cpufreq() {
    let f = Fixture::new();
    for id in 0..2 {
        f.put(
            &format!("/sys/devices/system/cpu/cpu{id}/cpufreq/related_cpus"),
            "0,1",
        );
        f.put(
            &format!("/sys/devices/system/cpu/cpu{id}/cpufreq/cpuinfo_cur_freq"),
            "1800000",
        );
    }
    f.put("/sys/class/devfreq/10000000.gpu/cur_freq", "700000000");
    let sample = f.collector().sample(1);
    close(&sample, "cpu_mhz.0_1", 1800.0);
    close(&sample, "gpu_mhz", 700.0);
    assert!(!sample.values.contains_key("gpu_usage"));
}
#[test]
fn incomplete_policy_directory_can_read_frequency_from_per_cpu_alias() {
    let f = Fixture::new();
    f.put("/sys/devices/system/cpu/present", "0-1");
    f.put("/sys/devices/system/cpu/cpufreq/policy0/related_cpus", "0 1");
    f.put("/sys/devices/system/cpu/cpu0/cpufreq/affected_cpus", "0 1");
    f.put("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq", "1600000");
    close(&f.collector().sample(1), "cpu_mhz.0_1", 1600.0);
}
#[test]
fn core_usage_excludes_guest_idle_iowait_and_recovers_resets() {
    let f = Fixture::new();
    f.policy(0, "0 1", "1000000");
    f.put(
        "/proc/stat",
        "cpu 10 0 10 50 10 0 0 0 99 99\ncpu0 10 0 0 10 0 0 0 0\ncpu1 10 0 0 10 0 0 0 0\n",
    );
    let mut c = f.collector();
    assert!(!c.sample(1000).values.contains_key("cpu_usage"));
    f.put(
        "/proc/stat",
        "cpu 30 0 30 90 30 0 0 0 199 199\ncpu0 10 0 0 110 0 0 0 0\ncpu1 110 0 0 10 0 0 0 0\n",
    );
    let sample = c.sample(3000);
    close(&sample, "cpu_usage", 40.0);
    close(&sample, "cpu_core.0", 0.0);
    close(&sample, "cpu_core.1", 100.0);
    close(&sample, "cpu_cluster.0_1", 50.0);
    f.put("/proc/stat", "cpu0 1 0 0 1 0 0 0 0\n");
    let sample = c.sample(5000);
    assert!(!sample.values.contains_key("cpu_core.0"));
    assert!(!sample.values.contains_key("cpu_cluster.0_1"));
    f.put("/proc/stat", "cpu0 11 0 0 11 0 0 0 0\n");
    assert!(!c.sample(15000).values.contains_key("cpu_core.0"));
    f.put("/proc/stat", "cpu0 21 0 0 21 0 0 0 0\n");
    close(&c.sample(17000), "cpu_core.0", 50.0);
}
#[test]
fn discharging_uses_abi_units_and_either_current_sign() {
    for current in ["1000000", "-1000000"] {
        let f = Fixture::new();
        f.battery("Discharging", current, "4000000");
        let sample = f.collector().sample(1);
        close(&sample, "power_w", 4.0);
        close(&sample, "battery_ma", 1000.0);
        close(&sample, "battery_c", 32.7);
    }
}
#[test]
fn external_online_blocks_stale_battery_status_and_wrong_units_are_rejected() {
    let f = Fixture::new();
    f.battery("Discharging", "1000000", "4000000");
    f.put("/sys/class/power_supply/usb/type", "USB_PD");
    f.put("/sys/class/power_supply/usb/online", "1");
    let sample = f.collector().sample(1);
    assert_eq!(sample.charging, Some(true));
    assert!(!sample.values.contains_key("power_w"));
    f.put("/sys/class/power_supply/usb/online", "0");
    f.battery("Discharging", "1000000", "4000");
    assert!(!f.collector().sample(1).values.contains_key("power_w"));
    for status in ["Unknown", "Full", "Charging", "Not charging"] {
        f.battery(status, "1000000", "4000000");
        assert!(!f.collector().sample(1).values.contains_key("power_w"));
    }
}

#[test]
fn main_charger_is_not_mistaken_for_a_battery_pack() {
    let f = Fixture::new();
    f.battery("Discharging", "1000000", "4000000");
    f.put("/sys/class/power_supply/main/type", "Mains");
    f.put("/sys/class/power_supply/main/online", "1");
    let sample = f.collector().sample(1);
    assert_eq!(sample.charging, Some(true));
    assert!(!sample.values.contains_key("power_w"));
}
#[test]
fn renamed_power_supply_uevent_fallback_does_not_merge_dual_packs() {
    let f = Fixture::new();
    f.put("/sys/class/power_supply/vendor_pack/type", "Battery");
    f.put("/sys/class/power_supply/vendor_pack/uevent","POWER_SUPPLY_STATUS=Discharging\nPOWER_SUPPLY_CURRENT_NOW=-500000\nPOWER_SUPPLY_VOLTAGE_NOW=8000000\nPOWER_SUPPLY_TEMP=300\nPOWER_SUPPLY_CAPACITY=60\n");
    f.put("/sys/class/power_supply/second_pack/type", "Battery");
    f.put("/sys/class/power_supply/second_pack/current_now", "1500000");
    let sample = f.collector().sample(1);
    close(&sample, "power_w", 4.0);
    close(&sample, "battery_c", 30.0);
}
#[test]
fn ddr_units_and_invalid_sensor_data_never_become_zero() {
    let f = Fixture::new();
    f.put(
        "/sys/kernel/helio-dvfsrc/dvfsrc_dump",
        "status\n DDR : 8533 Mbps\n",
    );
    f.put("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "NaN");
    f.put("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "101");
    f.thermal(0, "cpu0", "-274000");
    let sample = f.collector().sample(1);
    close(&sample, "ddr_mbps", 8533.0);
    for key in ["ddr_mhz", "gpu_mhz", "gpu_usage", "cpu_c"] {
        assert!(!sample.values.contains_key(key));
    }
}
#[test]
fn late_nodes_are_discovered_at_bounded_intervals() {
    let f = Fixture::new();
    let mut c = f.collector();
    f.put("/sys/class/kgsl/kgsl-3d0/gpuclk", "300000000");
    assert!(!c.sample(2000).values.contains_key("gpu_mhz"));
    close(&c.sample(60000), "gpu_mhz", 300.0);
}
#[test]
fn missing_core_pmu_does_not_create_cluster_mean() {
    let f = Fixture::new();
    f.policy(6, "6 7", "3000000");
    let c = f.collector();
    let mut out = Sample::default();
    c.add_cycles(&mut out, &BTreeMap::from([(6, 100.0)]));
    assert!(!out.values.contains_key("cpu_cycles.6_7"));
    c.add_cycles(&mut out, &BTreeMap::from([(6, 100.0), (7, 200.0)]));
    close(&out, "cpu_cycles.6_7", 150.0);
}
#[test]
fn kernel_inputs_are_bounded_and_core_lists_not_assumed_eight() {
    assert_eq!(fs::cpu_ids("0-3,6 8-9"), vec![0, 1, 2, 3, 6, 8, 9]);
    for bad in ["0-100000", "-1", "4-2", "x", "1-2-3"] {
        assert!(fs::cpu_ids(bad).is_empty());
    }
    let f = Fixture::new();
    f.put("/huge", &"x".repeat(9000));
    assert!(KernelFs::new(&f.0).read("/huge").is_none());
}
