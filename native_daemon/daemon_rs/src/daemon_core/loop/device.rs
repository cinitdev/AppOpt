use std::collections::HashMap;
use std::fs;
#[cfg(any(target_os = "android", target_os = "linux"))]
use std::mem;
use std::process::Command;

// 启动时输出设备诊断，便于用户反馈日志时确认运行环境。
pub(crate) fn print_startup_device_info() {
    let properties = read_android_properties();
    let android_version = first_property(
        &properties,
        &[
            "ro.build.version.release",
            "ro.system.build.version.release",
        ],
    );
    let api_level = first_property(
        &properties,
        &["ro.build.version.sdk", "ro.system.build.version.sdk"],
    );
    if let Some(version) = android_version {
        if let Some(api) = api_level {
            log_info!("Android 版本: {version} (API {api})");
        } else {
            log_info!("Android 版本: {version}");
        }
    }

    let brand = first_property(
        &properties,
        &[
            "ro.product.brand",
            "ro.product.system.brand",
            "ro.product.vendor.brand",
            "ro.product.odm.brand",
            "ro.product.product.brand",
        ],
    );
    let market_model = first_property(
        &properties,
        &[
            "ro.product.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
            "ro.product.system.marketname",
            "ro.product.product.marketname",
            "ro.vendor.product.marketname",
            "ro.config.marketing_name",
            "ro.vendor.oplus.market.name",
            "ro.oplus.market.name",
        ],
    );
    let certified_model = first_property(
        &properties,
        &[
            "ro.product.model",
            "ro.product.vendor.model",
            "ro.product.odm.model",
            "ro.product.system.model",
            "ro.product.product.model",
        ],
    );
    if let Some(brand) = brand {
        if let Some(model) = market_model.or(certified_model) {
            log_info!("设备品牌: {brand} {model}");
        } else {
            log_info!("设备品牌: {brand}");
        }
    } else if let Some(model) = market_model.or(certified_model) {
        log_info!("设备型号: {model}");
    }

    if let Ok(release) = fs::read_to_string("/proc/sys/kernel/osrelease") {
        let release = release.trim();
        if !release.is_empty() {
            log_info!("内核版本: Linux {release}");
        }
    }
}

pub(crate) fn read_android_properties() -> HashMap<String, String> {
    let output = Command::new("/system/bin/getprop")
        .output()
        .or_else(|_| Command::new("getprop").output());
    let Ok(output) = output else {
        return HashMap::new();
    };
    let text = String::from_utf8_lossy(&output.stdout);
    let mut properties = HashMap::new();
    for line in text.lines() {
        let Some(separator) = line.find("]: [") else {
            continue;
        };
        if !line.starts_with('[') {
            continue;
        }
        let key = &line[1..separator];
        let value = line[separator + 4..]
            .strip_suffix(']')
            .unwrap_or(&line[separator + 4..]);
        if !key.is_empty() && !value.is_empty() {
            properties.insert(key.to_string(), value.to_string());
        }
    }
    properties
}

pub(crate) fn first_property<'a>(
    properties: &'a HashMap<String, String>,
    keys: &[&str],
) -> Option<&'a str> {
    keys.iter()
        .find_map(|key| properties.get(*key).map(String::as_str))
        .filter(|value| !value.is_empty())
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub(crate) fn system_process_count() -> Option<u64> {
    let mut info: libc::sysinfo = unsafe { mem::zeroed() };
    let rc = unsafe { libc::sysinfo(&mut info) };
    if rc == 0 {
        Some(info.procs as u64)
    } else {
        None
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(crate) fn system_process_count() -> Option<u64> {
    None
}
