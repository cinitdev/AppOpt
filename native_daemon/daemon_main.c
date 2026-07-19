static void print_help(const char* prog_name) {
    printf("Usage: %s [OPTIONS]\n", prog_name);
    printf("Options:\n");
    printf("  -c <config_file>   指定配置文件 (默认: /data/adb/modules/AppOpt/config/applist.conf)\n");
    printf("  -s <interval>      设置检查间隔(秒) (必须>=1, 默认: 2)\n");
    printf("  -v                 显示程序版本\n");
    printf("  -P, --ping-daemon <socket> <token>  请求守护进程反向验证 App\n");
    printf("  --app-state <pkg>                   读取 top-app/foreground_window 并检查目标包是否前台\n");
    printf("  -h                 显示帮助信息\n");
    printf("\n示例:\n");
    printf("  %s -c /data/adb/modules/AppOpt/config/applist.conf -s 3\n", prog_name);
}

int main(int argc, char **argv) {
    /* stdout/stderr 重定向到文件(service.sh 写 AppOpt.log)时默认是全缓冲,
     * 长驻守护进程填不满 4KB 缓冲区, 日志会一直卡在内存里不落盘 ->
     * 表现为日志文件长期为空。改成行缓冲: 每输出一行立即写入文件。
     * 必须在任何 printf(含下面 init_cpu_topo)之前设置。 */
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);
    signal(SIGTERM, handle_shutdown_signal);
    signal(SIGINT, handle_shutdown_signal);
    signal(SIGHUP, handle_shutdown_signal);
    signal(SIGPIPE, SIG_IGN);

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--ping-daemon") == 0 || strcmp(argv[i], "-P") == 0) {
            if (i + 2 >= argc) {
                fprintf(stderr, "--ping-daemon 需要 <socket> <token>\n");
                return 2;
            }
            return daemon_socket_ping_client(argv[i + 1], argv[i + 2]);
        }
        if (strcmp(argv[i], "--app-state") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "--app-state 需要 <pkg>\n");
                return 2;
            }
            return app_state_print_cli(argv[i + 1], NULL);
        }
    }

    CpuTopology topo = init_cpu_topo();
    mkdir(CONFIG_DIR, 0755);
    mkdir(LOG_DIR, 0755);
    mkdir(EBPF_DIR, 0755);
    (void)calib_load_policy(&topo);
    g_topo = topo;                     /* 校准线程使用的拓扑快照 */
    char config_file[4096] = CONFIG_DIR "/applist.conf";
    int sleep_interval = 2;
    int opt;
    while ((opt = getopt(argc, argv, "c:s:hv")) != -1) {
        switch (opt) {
            case 'c':
                build_str(config_file, sizeof(config_file), optarg, NULL);
                printf("配置文件: %s\n", config_file);
                break;
            case 's':
            {
                char *endptr;
                errno = 0;
                long val = strtol(optarg, &endptr, 10);
                if (errno == ERANGE || endptr == optarg || *endptr != '\0' ||
                    val < 1 || val > INT_MAX) {
                    fprintf(stderr, "无效的时间间隔: %s\n", optarg);
                    fprintf(stderr, "间隔必须是 >=1 的整数\n");
                    exit(EXIT_FAILURE);
                }
                sleep_interval = (int)val;
                printf("检查间隔: %d 秒\n", sleep_interval);
                break;
            }
            case 'v':
                printf("AppOpt 版本 %s\n", VERSION);
                exit(EXIT_SUCCESS);
            case 'h':
                print_help(argv[0]);
                exit(EXIT_SUCCESS);
            default:
                print_help(argv[0]);
                exit(EXIT_FAILURE);
        }
    }

    struct stat st;
    if (stat(config_file, &st) != 0) {
        const char* initial_content = "# 规则编写与使用说明请参考 http://AppOpt.suto.top\n\n";
        if (write_file(AT_FDCWD, config_file, initial_content, O_WRONLY | O_CREAT | O_TRUNC)) {
            printf("配置文件不存在，重建一个空的配置文件: %s\n", config_file);
        }
    }

    AppConfig* initial_config = load_config(config_file, &topo, NULL);
    if (!initial_config) {
        fprintf(stderr, "初始配置加载失败\n");
        exit(EXIT_FAILURE);
    }
    build_str(g_config_file, sizeof(g_config_file), config_file, NULL);
    atomic_store(&current_config, initial_config);
    atomic_store(&config_updated, 1);

    inotify_fd = inotify_init1(IN_CLOEXEC);
    if (inotify_fd >= 0) {
        int flags = fcntl(inotify_fd, F_GETFL);
        if (flags >= 0) fcntl(inotify_fd, F_SETFL, flags | O_NONBLOCK);
        inotify_wd = inotify_add_watch(inotify_fd, config_file, IN_CLOSE_WRITE | IN_DELETE_SELF | IN_MOVE_SELF);
        if (inotify_wd >= 0) {
            inotify_supported = 1;
            printf("启用inotify监控配置文件变更\n");
        } else {
            close(inotify_fd);
            inotify_fd = -1;
            printf("inotify初始化失败，使用轮询模式\n");
        }
    }

    pthread_t loader_thread;
    int* interval_ptr = malloc(sizeof(int));
    if (!interval_ptr) {
        config_release(initial_config);
        if (inotify_supported) close(inotify_fd);
        exit(EXIT_FAILURE);
    }
    *interval_ptr = sleep_interval;

    if (pthread_create(&loader_thread, NULL, config_loader_thread, interval_ptr) != 0) {
        perror("配置加载器线程创建失败");
        free(interval_ptr);
        config_release(initial_config);
        if (inotify_supported) close(inotify_fd);
        exit(EXIT_FAILURE);
    }
    pthread_detach(loader_thread);

    /* 启动自动校准线程 (处理 App 下发的 start/stop 命令) */
    pthread_t calib_tid;
    if (pthread_create(&calib_tid, NULL, calib_thread, NULL) == 0) {
        pthread_detach(calib_tid);
        printf("启用自动校准线程 (=auto)\n");
    } else {
        perror("校准线程创建失败");
    }

    /* 启动真实帧率监测线程 (eBPF 优先, SurfaceFlinger 兜底) */
    pthread_t fps_tid;
    if (pthread_create(&fps_tid, NULL, fps_thread, NULL) == 0) {
        pthread_detach(fps_tid);
        printf("启用真实帧率监测线程 (eBPF/SF fallback)\n");
    } else {
        perror("帧率监测线程创建失败");
    }

    ProcCache cache = {0};
    int affinity_counter = 0;
    printf("启动AppOpt服务 v%s (作者: suto & 一只小柒夏)\n", VERSION);

    /* 输出设备信息 */
    {
        char android_ver[PROP_VALUE_MAX];
        char api_level[PROP_VALUE_MAX];
        char brand[PROP_VALUE_MAX];
        char model[PROP_VALUE_MAX];
        const char* const android_ver_keys[] = {
                "ro.build.version.release",
                "ro.system.build.version.release"
        };
        const char* const api_level_keys[] = {
                "ro.build.version.sdk",
                "ro.system.build.version.sdk"
        };
        const char* const brand_keys[] = {
                "ro.product.brand",
                "ro.product.system.brand",
                "ro.product.vendor.brand",
                "ro.product.odm.brand",
                "ro.product.product.brand"
        };
        const char* const market_model_keys[] = {
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.product.odm.marketname",
                "ro.product.system.marketname",
                "ro.product.product.marketname",
                "ro.vendor.product.marketname",
                "ro.config.marketing_name",
                "ro.vendor.oplus.market.name",
                "ro.oplus.market.name"
        };
        const char* const cert_model_keys[] = {
                "ro.product.model",
                "ro.product.vendor.model",
                "ro.product.odm.model",
                "ro.product.system.model",
                "ro.product.product.model"
        };

        /* Android 版本 */
        if (get_prop_first(android_ver, sizeof(android_ver), android_ver_keys,sizeof(android_ver_keys) / sizeof(android_ver_keys[0]))) {
            /* API Level */
            if (get_prop_first(api_level, sizeof(api_level), api_level_keys,sizeof(api_level_keys) / sizeof(api_level_keys[0]))) {
                printf("Android 版本: %s (API %s)\n", android_ver, api_level);
            } else {
                printf("Android 版本: %s\n", android_ver);
            }
        }

        /* 设备品牌和型号 */
        if (get_prop_first(brand, sizeof(brand), brand_keys,sizeof(brand_keys) / sizeof(brand_keys[0]))) {
            if (get_prop_first(model, sizeof(model), market_model_keys,sizeof(market_model_keys) / sizeof(market_model_keys[0]))) {
                printf("设备品牌: %s %s\n", brand, model);
            } else if (get_prop_first(model, sizeof(model), cert_model_keys,sizeof(cert_model_keys) / sizeof(cert_model_keys[0]))) {
                printf("设备品牌: %s %s\n", brand, model);
            } else {
                printf("设备品牌: %s\n", brand);
            }
        } else if (get_prop_first(model, sizeof(model), market_model_keys,sizeof(market_model_keys) / sizeof(market_model_keys[0]))) {
            printf("设备型号: %s\n", model);
        } else if (get_prop_first(model, sizeof(model), cert_model_keys,sizeof(cert_model_keys) / sizeof(cert_model_keys[0]))) {
            printf("设备型号: %s\n", model);
        }

        /* 内核版本 */
        struct utsname uts;
        if (uname(&uts) == 0) {
            printf("内核版本: %s %s\n", uts.sysname, uts.release);
        }
    }

    /* 启动守护进程身份验证 socket。App 用它确认这是 AppOpt 自己的守护进程, 不再只看进程名。 */
    pthread_t daemon_sock_tid;
    if (pthread_create(&daemon_sock_tid, NULL, daemon_socket_thread, NULL) == 0) {
        pthread_detach(daemon_sock_tid);
        printf("启用守护进程验证 socket\n");
    } else {
        perror("守护进程验证 socket 线程创建失败");
    }

    time_t last_policy_sync = 0;
    while (!shutdown_requested) {
        time_t now = time(NULL);
        if (now - last_policy_sync >= 5) {
            calib_sync_policy_topology(&topo);
            last_policy_sync = now;
        }

        if (atomic_exchange(&config_updated, 0)) {
            cache.scan_all_proc = true;
            cache.last_proc_count = 0;
            affinity_counter = 0;
        }

        AppConfig* cfg = get_config();
        if (cfg) {
            bool health_ready = rule_health_sync_config(cfg, NULL);
            bool health_full_scan = health_ready && rule_health_full_scan_due(&cache);
            bool foreground_discovery_scan = health_ready &&
                rule_health_foreground_discovery_scan_due(&cache);
            update_cache(
                &cache, cfg, &affinity_counter,
                health_full_scan || foreground_discovery_scan);
            if (health_ready) rule_health_update(cfg, &cache);
            affinity_counter--;
            if (affinity_counter < 1) {
                apply_affinity(&cache, &cfg->topo);
                affinity_counter = 5;
            }
            config_release(cfg);
        }
        sleep(sleep_interval);
    }
    printf("收到退出信号 %d, AppOpt 服务退出\n", shutdown_requested);
    return 0;
}
