// 程序入口与命令行分发。
//
// AppOptRs 同一个二进制承担三类职责：
// - 默认模式：作为常驻守护进程循环读取规则并绑核。
// - 调试模式：--scan-once / --apply-once 用于真机上对照规则命中。
// - 辅助模式：--app-state 和 --ping-daemon 给 App 侧做前台识别和守护身份验证。
//
// 入口只做参数分流，业务逻辑分别放在 daemon loop、CLI、前台状态和控制 socket 模块里。
fn main() {
    let args = match parse_args(env::args().skip(1)) {
        Ok(args) => args,
        Err(msg) => {
            eprintln!("{msg}");
            print_help();
            std::process::exit(2);
        }
    };

    if args.version {
        calibration::print_version_diagnostics(VERSION);
        return;
    }

    let result = if let Some((socket_name, token)) = &args.ping_daemon {
        daemon_socket_ping_client(socket_name, token)
    } else if let Some(pkg) = &args.app_state_pkg {
        app_state_print_cli(pkg)
    } else if let Some(name) = &args.find_pid_name {
        process_index_print_pids(name)
    } else if !args.find_process_names.is_empty() {
        process_index_print_names(&args.find_process_names)
    } else if args.scan_once {
        run_once(&args, false)
    } else if args.apply_once {
        run_once(&args, true)
    } else {
        daemon_loop(&args)
    };

    if let Err(err) = result {
        eprintln!("[RS] 执行失败: {err}");
        std::process::exit(1);
    }
}

fn parse_args<I>(mut args: I) -> Result<Args, String>
where
    I: Iterator<Item = String>,
{
    let mut parsed = Args {
        config: PathBuf::from(DEFAULT_CONFIG),
        uid_map: PathBuf::from(DEFAULT_UID_MAP),
        scan_once: false,
        apply_once: false,
        version: false,
        ping_daemon: None,
        app_state_pkg: None,
        find_pid_name: None,
        find_process_names: Vec::new(),
        target_pkg: None,
        interval_secs: DEFAULT_INTERVAL_SECS,
    };

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            "-v" | "--version" => parsed.version = true,
            "-P" | "--ping-daemon" => {
                let socket_name = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要 socket 名称"))?;
                let token = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要验证 token"))?;
                parsed.ping_daemon = Some((socket_name, token));
            }
            "--app-state" => {
                let value = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要包名"))?;
                parsed.app_state_pkg = Some(value);
            }
            "--find-pid" => {
                let value = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要进程名"))?;
                parsed.find_pid_name = Some(value);
            }
            "--find-processes" => {
                parsed.find_process_names.extend(args.by_ref());
                if parsed.find_process_names.is_empty() {
                    return Err(format!("{arg} 至少需要一个进程名"));
                }
            }
            "--scan-once" => parsed.scan_once = true,
            "--apply-once" => parsed.apply_once = true,
            "-c" | "--config" => {
                let value = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要配置文件路径"))?;
                parsed.config = PathBuf::from(value);
            }
            "-s" | "--interval" => {
                let value = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要检查间隔秒数"))?;
                parsed.interval_secs = value
                    .parse::<u64>()
                    .map_err(|_| format!("检查间隔无效: {value}"))?
                    .max(1);
            }
            "--uid-map" => {
                let value = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要 UID 映射文件路径"))?;
                parsed.uid_map = PathBuf::from(value);
            }
            "--pkg" => {
                let value = args
                    .next()
                    .ok_or_else(|| format!("{arg} 需要包名"))?;
                parsed.target_pkg = Some(value);
            }
            _ => return Err(format!("未知参数: {arg}")),
        }
    }

    let exclusive_modes = [
        parsed.scan_once,
        parsed.apply_once,
        parsed.ping_daemon.is_some(),
        parsed.app_state_pkg.is_some(),
        parsed.find_pid_name.is_some(),
        !parsed.find_process_names.is_empty(),
    ]
    .into_iter()
    .filter(|enabled| *enabled)
    .count();
    if exclusive_modes > 1 {
        return Err(
            "扫描、应用、守护验证、前台状态和进程查询模式不能同时使用".to_string(),
        );
    }

    Ok(parsed)
}

fn print_help() {
    println!(
        "用法: AppOptRs [-c applist.conf] [-s 秒数] [--uid-map package_uid.map] [--pkg 包名] [--scan-once|--apply-once]\n\
         \n\
         模式:\n\
           默认模式       常驻守护并持续执行绑核\n\
           --apply-once  执行一次绑核后退出\n\
           --scan-once   只打印命中的进程/线程规则, 不修改绑核\n\
           --app-state <包名>  打印 cgroup 前台包状态\n\
           --find-pid <进程名>  从 AppOpt 进程索引查询 PID\n\
           --find-processes <名称...>  输出当前存在的进程名\n\
           -P, --ping-daemon <socket> <token>  请求守护进程回连 App 验证 socket\n\
           -v            打印版本和启动诊断\n"
    );
}
