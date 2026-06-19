# AppOpt

AppOpt is an Android CPU affinity optimization module with an accompanying app.
It is based on the original AppOpt v1.6.3 by suto, and adds automatic rule
calibration, real FPS monitoring, history recording, and a graphical control
surface.

The module is designed for rooted Android devices and supports Magisk,
KernelSU, and APatch.

## Features

- CPU affinity rules with hot reload.
- Automatic game-thread sampling and rule generation.
- FPS monitoring through eBPF uprobe, with SurfaceFlinger fallback paths.
- History recording for thread load and FPS sessions.
- Android app with floating in-game controls, environment checks, and history UI.
- Watchdog service for restarting the native daemon if it exits unexpectedly.

## How It Works

The native daemon reads package and thread rules, then applies CPU affinity and
cpuset constraints to matching processes. For `auto` calibration, it samples
thread CPU usage, groups similar thread names, ranks them by `avg/max/score`,
and emits only the top heavy thread rules plus a package-level fallback rule.

FPS monitoring prefers eBPF uprobes attached to `libgui.so` queueBuffer symbols.
When eBPF is unavailable, AppOpt falls back to SurfaceFlinger based methods.

## Project Layout

```text
AppOpt.c                         Native daemon
app/                             Android app
fps_monitor/                     FPS monitor, eBPF bridge, and fallback code
magisk_module/                   Module files
build_module.sh                  Native/module build script
FPS_EBPF_INTEGRATION.md          eBPF integration notes
AppOpt工作原理.svg                Workflow diagram
AppOpt改版特色.md                 Chinese feature overview
```

## Build

Android app:

```bash
./gradlew assembleDebug
```

Native module:

```bash
./build_module.sh
```

The module build script produces Android ABI binaries and the Magisk module zip
under `build/`.

## Requirements

- Rooted Android device.
- Magisk, KernelSU, or APatch.
- Android NDK for native builds.
- Rust toolchain for the aya eBPF bridge.

eBPF support depends on the device kernel, ROM policy, available symbols, and
target process ABI. AppOpt automatically falls back when eBPF cannot be used.

## Documentation

- [AppOpt改版特色.md](AppOpt改版特色.md)
- [FPS_EBPF_INTEGRATION.md](FPS_EBPF_INTEGRATION.md)
- [fps_monitor/README.md](fps_monitor/README.md)

## Credits

Original AppOpt v1.6.3 by suto. This enhanced version extends the native
affinity daemon with calibration, FPS monitoring, history, and an Android app.
