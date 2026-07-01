#ifndef APPOPT_APP_STATE_MONITOR_H
#define APPOPT_APP_STATE_MONITOR_H

#include <stdbool.h>
#include <sys/types.h>

typedef struct {
    bool target_top_app;
    pid_t target_pid;
    int scanned;
    int package_count;
    char packages[1024];
} app_top_state_result;

bool app_top_state_check(const char *target_pkg, app_top_state_result *out);
int app_state_print_cli(const char *pkg, const char *cached_pid_arg);

#endif /* APPOPT_APP_STATE_MONITOR_H */
