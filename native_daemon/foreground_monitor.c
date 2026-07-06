#define _GNU_SOURCE
#include "foreground_monitor.h"

#include <ctype.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define APPOPT_CMDLINE_MAX 512
#define APPOPT_PATH_MAX 160
#define APPOPT_TOP_PACKAGE_MAX 16

static const char *TOP_APP_GROUP_PATHS[] = {
    "/dev/cpuset/top-app/cgroup.procs",
    "/dev/cpuset/top-app/tasks",
    "/dev/cpuctl/top-app/cgroup.procs",
    "/dev/cpuctl/top-app/tasks",
    "/dev/cpuset/foreground_window/cgroup.procs",
    "/dev/cpuset/foreground_window/tasks",
    "/dev/cpuctl/foreground_window/cgroup.procs",
    "/dev/cpuctl/foreground_window/tasks",
};

static bool is_number_text(const char *text) {
    if (!text || !*text) return false;
    for (const char *p = text; *p; p++) {
        if (!isdigit((unsigned char)*p)) return false;
    }
    return true;
}

static bool parse_pid_text(const char *text, pid_t *pid) {
    if (!is_number_text(text) || !pid) return false;
    long value = strtol(text, NULL, 10);
    if (value <= 0 || value > 4194304) return false;
    *pid = (pid_t)value;
    return true;
}

static bool read_text_file(const char *path, char *buf, size_t size) {
    if (!buf || size == 0) return false;
    buf[0] = '\0';
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, buf, size - 1);
    close(fd);
    if (n <= 0) return false;
    buf[n] = '\0';
    return true;
}

static pid_t read_tgid(pid_t tid) {
    char path[APPOPT_PATH_MAX];
    snprintf(path, sizeof(path), "/proc/%d/status", tid);

    FILE *fp = fopen(path, "r");
    if (!fp) return tid;

    char line[256];
    pid_t tgid = tid;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "Tgid:", 5) == 0) {
            char *value = line + 5;
            while (*value && isspace((unsigned char)*value)) value++;
            value[strcspn(value, "\r\n\t ")] = '\0';
            pid_t parsed = 0;
            if (parse_pid_text(value, &parsed)) tgid = parsed;
            break;
        }
    }
    fclose(fp);
    return tgid;
}

static bool read_process_first_arg(pid_t pid, char *buf, size_t size) {
    char path[APPOPT_PATH_MAX];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (!read_text_file(path, buf, size)) return false;
    return buf[0] != '\0';
}

static bool normalize_package(const char *proc_name, char *pkg, size_t size) {
    if (!proc_name || !*proc_name || !pkg || size == 0) return false;
    pkg[0] = '\0';

    if (strchr(proc_name, '/')) return false;
    if (strcmp(proc_name, "system_server") == 0 ||
        strcmp(proc_name, "surfaceflinger") == 0 ||
        strcmp(proc_name, "zygote") == 0 ||
        strcmp(proc_name, "zygote64") == 0 ||
        strcmp(proc_name, "servicemanager") == 0 ||
        strcmp(proc_name, "hwservicemanager") == 0 ||
        strcmp(proc_name, "vndservicemanager") == 0) {
        return false;
    }

    if (!strchr(proc_name, '.')) return false;

    snprintf(pkg, size, "%s", proc_name);
    char *colon = strchr(pkg, ':');
    if (colon) *colon = '\0';
    return pkg[0] != '\0';
}

static bool proc_matches_target(const char *proc_name, const char *target_pkg) {
    if (!proc_name || !target_pkg || !*target_pkg) return false;
    size_t len = strlen(target_pkg);
    return strncmp(proc_name, target_pkg, len) == 0 &&
        (proc_name[len] == '\0' || proc_name[len] == ':');
}

static bool pid_seen(pid_t pid, const pid_t *seen, int count) {
    for (int i = 0; i < count; i++) {
        if (seen[i] == pid) return true;
    }
    return false;
}

static bool package_seen(const char *pkg, char packages[][APPOPT_CMDLINE_MAX], int count) {
    for (int i = 0; i < count; i++) {
        if (strcmp(packages[i], pkg) == 0) return true;
    }
    return false;
}

static void append_package_csv(char *dst, size_t size, const char *pkg) {
    if (!dst || size == 0 || !pkg || !*pkg) return;
    size_t used = strlen(dst);
    if (used >= size - 1) return;
    if (used > 0) {
        strncat(dst, ",", size - strlen(dst) - 1);
    }
    strncat(dst, pkg, size - strlen(dst) - 1);
}

static void scan_top_app_path(const char *path, const char *target_pkg, app_top_state_result *out,
                              pid_t *seen_pids, int *seen_pid_count,
                              char packages[][APPOPT_CMDLINE_MAX], int *package_count) {
    FILE *fp = fopen(path, "r");
    if (!fp) return;

    bool is_tasks = strstr(path, "/tasks") != NULL;
    char line[64];
    while (fgets(line, sizeof(line), fp)) {
        line[strcspn(line, "\r\n")] = '\0';
        pid_t id = 0;
        if (!parse_pid_text(line, &id)) continue;

        pid_t pid = is_tasks ? read_tgid(id) : id;
        if (pid <= 0) continue;
        if (pid_seen(pid, seen_pids, *seen_pid_count)) continue;
        if (*seen_pid_count < APPOPT_TOP_PACKAGE_MAX * 8) {
            seen_pids[*seen_pid_count] = pid;
            (*seen_pid_count)++;
        }

        out->scanned++;

        char proc_name[APPOPT_CMDLINE_MAX];
        if (!read_process_first_arg(pid, proc_name, sizeof(proc_name))) continue;

        if (target_pkg && *target_pkg && proc_matches_target(proc_name, target_pkg)) {
            out->target_top_app = true;
            if (out->target_pid <= 0) out->target_pid = pid;
        }

        char pkg[APPOPT_CMDLINE_MAX];
        if (!normalize_package(proc_name, pkg, sizeof(pkg))) continue;
        if (package_seen(pkg, packages, *package_count)) continue;

        if (*package_count < APPOPT_TOP_PACKAGE_MAX) {
            snprintf(packages[*package_count], APPOPT_CMDLINE_MAX, "%s", pkg);
            (*package_count)++;
            append_package_csv(out->packages, sizeof(out->packages), pkg);
        }
    }

    fclose(fp);
}

bool app_top_state_check(const char *target_pkg, app_top_state_result *out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));

    pid_t seen_pids[APPOPT_TOP_PACKAGE_MAX * 8] = {0};
    int seen_pid_count = 0;
    char packages[APPOPT_TOP_PACKAGE_MAX][APPOPT_CMDLINE_MAX];
    memset(packages, 0, sizeof(packages));
    int package_count = 0;

    size_t path_count = sizeof(TOP_APP_GROUP_PATHS) / sizeof(TOP_APP_GROUP_PATHS[0]);
    for (size_t i = 0; i < path_count; i++) {
        scan_top_app_path(TOP_APP_GROUP_PATHS[i], target_pkg, out, seen_pids, &seen_pid_count,
                          packages, &package_count);
    }

    out->package_count = package_count;
    return out->target_top_app || package_count > 0;
}

int app_state_print_cli(const char *pkg, const char *unused_arg) {
    (void)unused_arg;

    app_top_state_result state;
    bool ok = app_top_state_check(pkg, &state);

    printf("ok=%d\n", ok ? 1 : 0);
    printf("target_top_app=%d\n", state.target_top_app ? 1 : 0);
    printf("top_app=%d\n", state.target_top_app ? 1 : 0);
    printf("pid=%d\n", state.target_pid);
    printf("scanned=%d\n", state.scanned);
    printf("package_count=%d\n", state.package_count);
    printf("packages=%s\n", state.packages);
    return 0;
}
