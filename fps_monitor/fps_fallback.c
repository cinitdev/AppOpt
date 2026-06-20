/* fps_fallback.c —— SurfaceFlinger dump 回退方案实现
 *
 * 策略1(默认): dumpsys SurfaceFlinger --latency 差分读(锁定主渲染层)
 * 策略2(回退): dumpsys SurfaceFlinger --timestats (Android 16 等 --latency 失效时)
 *
 * 通过 AppOpt.c 提供的 sf_dump_binder() 调用 SF dump(binder 直连,快速)。 */
#define _GNU_SOURCE
#include "fps_fallback.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* SF 相关常量(与 AppOpt.c 保持一致) */
#define SF_MAX_CANDS  16
#define SF_LAYER_MAX  256
#define FPS_RELOCK_MISS 3       /* 主层连续读不到帧的次数阈值,超过重新发现 */
#define FPS_PROBE_FAIL  4       /* --latency 连续无有效帧时间戳,超过切 timestats */
#define FPS_FRESH_NS    5000000000ULL /* 只把最近 5 秒仍在上屏的 layer 当作活跃层 */

struct fps_fallback_ctx {
    char pkg[256];

    /* --latency 模式状态 */
    char main_layer[SF_LAYER_MAX];    /* 已锁定的主渲染层 */
    unsigned long long since;          /* 主层差分基准(已统计到的最后上屏时间戳) */
    int miss;                          /* 主层连续读不到帧的次数 */
    int probe_fail;                    /* --latency 连续无有效帧时间戳次数 */

    /* timestats 回退状态 */
    bool ts_mode;                      /* true = 已切到 timestats */
    long ts_last;                      /* 上窗口 totalFrames */
    time_t ts_last_time;               /* 上窗口时间(用于算间隔) */
};

static void timestats_disable(void);

static bool g_timestats_enabled = false;
static bool g_cleanup_registered = false;

static void fps_fallback_process_cleanup(void) {
    if (g_timestats_enabled) {
        timestats_disable();
    }
}

static void fps_fallback_register_cleanup(void) {
    if (!g_cleanup_registered) {
        atexit(fps_fallback_process_cleanup);
        g_cleanup_registered = true;
    }
}

/* ===================== 内部辅助函数 ===================== */

static unsigned long long now_monotonic_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0ULL;
    return (unsigned long long)ts.tv_sec * 1000000000ULL +
           (unsigned long long)ts.tv_nsec;
}

static bool latency_ts_is_fresh(unsigned long long latest, unsigned long long now) {
    if (latest == 0ULL || now == 0ULL) return false;
    if (latest > now) return true;
    return now - latest <= FPS_FRESH_NS;
}

static bool pkg_boundary_char(char c) {
    unsigned char u = (unsigned char)c;
    return c == '\0' || !(isalnum(u) || c == '_' || c == '.');
}

static bool layer_matches_pkg(const char *text, size_t len, const char *pkg) {
    if (!text || !pkg || !pkg[0]) return false;
    size_t pkglen = strlen(pkg);
    if (pkglen == 0 || pkglen > len) return false;

    const char *p = text;
    size_t remain = len;
    while (remain >= pkglen) {
        char *hit = memmem(p, remain, pkg, pkglen);
        if (!hit) return false;
        size_t off = (size_t)(hit - text);
        char before = (off == 0) ? '\0' : text[off - 1];
        char after = (off + pkglen >= len) ? '\0' : text[off + pkglen];
        if (pkg_boundary_char(before) && pkg_boundary_char(after)) {
            return true;
        }
        size_t consumed = (size_t)(hit - p) + 1;
        p += consumed;
        remain -= consumed;
    }
    return false;
}

/* 从 --list 输出里挑出含目标包名的候选 layer 名, 存入 cands(每个 SF_LAYER_MAX)。
 * --list 每行一个层名(可能形如 "63:c486 名字#27294" 带前缀行号, 也可能纯名字),
 * 去掉行首 "N:" 行号前缀。返回候选个数。*/
static int list_candidates(const char *pkg, char cands[][SF_LAYER_MAX], int maxc) {
    static char out[1 << 18];                 /* 256KB, --list 通常几十 KB */
    const char *args[] = { "--list" };
    if (sf_dump_binder(args, 1, out, sizeof(out)) <= 0) return 0;

    size_t pkglen = strlen(pkg);
    int nc = 0;
    char *save = NULL;
    for (char *line = strtok_r(out, "\n", &save); line && nc < maxc;
         line = strtok_r(NULL, "\n", &save)) {
        /* 去行首 "数字:" 前缀(--list 带行号时) */
        char *s = line;
        char *colon = strchr(s, ':');
        if (colon) {
            bool alldig = true;
            for (char *d = s; d < colon; d++)
                if (*d < '0' || *d > '9') { alldig = false; break; }
            if (alldig && colon > s) s = colon + 1;
        }
        while (*s == ' ' || *s == '\t') s++;
        size_t L = strlen(s);
        while (L > 0 && (s[L-1] == '\r' || s[L-1] == ' ')) s[--L] = '\0';
        if (L == 0) continue;

        /* Android 16(SF 新前端) --list 不再是纯层名, 而是带外壳的调试串:
         *   RequestedLayerState{<hex> <真实层名(可含空格)> parentId=N z=N ...}
         * 把外壳剥掉抽出真实层名, 否则整串喂给 --latency 必然匹配不到、读不到帧。
         * 旧格式(纯层名)不匹配该前缀, 保持原样。*/
        static const char PFX[] = "RequestedLayerState{";
        if (L > sizeof(PFX) - 1 && memcmp(s, PFX, sizeof(PFX) - 1) == 0) {
            char *name = s + (sizeof(PFX) - 1);     /* 跳过 "RequestedLayerState{" */
            char *sp = strchr(name, ' ');           /* 跳过 hex 句柄到首个空格 */
            if (sp) name = sp + 1;
            /* 层名终点: 取 " parentId=" 与 " z=" 中较早者, 都没有则到末尾的 '}' */
            char *end = strstr(name, " parentId=");
            char *zp = strstr(name, " z=");
            if (zp && (!end || zp < end)) end = zp;
            if (!end) { char *brace = strrchr(name, '}'); if (brace) end = brace; }
            if (end && end > name) *end = '\0';
            s = name;
            L = strlen(s);
            while (L > 0 && (s[L-1] == ' ' || s[L-1] == '\t')) s[--L] = '\0';
        }
        if (L == 0 || L >= SF_LAYER_MAX) continue;
        if (pkglen > 0 && !layer_matches_pkg(s, L, pkg)) continue;
        memcpy(cands[nc], s, L + 1);
        nc++;
    }
    return nc;
}

/* 对单个 layer 跑 --latency, 用"差分读"统计 FPS —— 与 Scene 同法, 不清缓冲。
 *
 * --latency 输出: 首行=刷新周期(ns); 其余每行 3 个 ns(空白分隔), 第2列=实际上屏时间。
 * SF 为每层滚动保留最近约 128 帧。我们不调用 --latency-clear(那会清掉全局共享缓冲、
 * 干扰同样读该缓冲的工具如 Scene FAS), 而是记住"上次已统计到的最后一帧上屏时间戳"
 * (*since, 调用方持有), 本窗口只数严格大于它的新帧:
 *   - 新帧数 m 与新帧首尾时间差 -> 本窗口瞬时 FPS = (m-1)/span
 *   - 把本窗口见到的最大上屏时间戳写回 *since, 供下一窗口续算
 * 这样既得到瞬时 FPS(不被 128 帧历史平滑), 又不破坏 SF 缓冲。
 *
 * 跳过 0 与 INT64_MAX(9223372036854775807, 未决/未上屏哨兵)。
 * *since 为 0 表示首次(无基准): 此时回退为"用本缓冲里全部有效帧"算一个初值,
 * 并把最后时间戳记入 *since, 下一窗口起转入纯差分。
 * 新帧 < 2 无法算时间差返回 -1(本窗口不可用, 但 *since 仍会推进, 不丢帧基准)。
 * valid_out/latest_out 用于区分"--latency 不可用"和"当前层暂时没有新帧"。 */
static double layer_fps(const char *layer, unsigned long long *since,
                        int *valid_out, unsigned long long *latest_out) {
    static char out[1 << 16];
    const char *args[] = { "--latency", layer };
    if (valid_out) *valid_out = 0;
    if (latest_out) *latest_out = 0ULL;
    if (sf_dump_binder(args, 2, out, sizeof(out)) <= 0) return -1.0;

    unsigned long long base = since ? *since : 0ULL;
    unsigned long long first = 0, last = 0, maxts = base, latest_valid = 0ULL;
    int valid = 0;
    int n = 0, lineno = 0;
    char *save = NULL;
    for (char *line = strtok_r(out, "\n", &save); line;
         line = strtok_r(NULL, "\n", &save)) {
        lineno++;
        if (lineno == 1) continue;  /* 首行是刷新周期 */
        unsigned long long a = 0, b = 0, c = 0;
        if (sscanf(line, "%llu %llu %llu", &a, &b, &c) != 3) continue;
        if (b == 0ULL || b == 9223372036854775807ULL) continue;  /* 未上屏哨兵 */
        valid++;
        if (b > latest_valid) latest_valid = b;
        if (b > maxts) maxts = b;
        if (base != 0ULL && b <= base) continue;  /* 跳过旧帧 */
        if (n == 0) first = b;
        last = b;
        n++;
    }
    if (since && maxts > base) *since = maxts;
    if (valid_out) *valid_out = valid;
    if (latest_out) *latest_out = latest_valid;

    if (n < 2 || last <= first) return -1.0;   /* 本窗口新帧不足以算时间差 */
    double span_s = (double)(last - first) / 1e9;
    if (span_s <= 0.0) return -1.0;
    return (double)(n - 1) / span_s;           /* n 个新帧 -> n-1 个间隔 */
}

/* ===== Android 16+ 回退: dumpsys SurfaceFlinger --timestats =====
 * 部分系统(实测 Android 16, 可能含某些 13/15 定制 ROM)上 --latency 只回刷新周期
 * 一行、不再吐每层那 128 帧时间戳, 故 layer_fps 永远算不出 FPS(日志表现为
 * "找到 N 个候选层但均读不到帧")。此时回退到官方 timestats: 它读 present fence,
 * 同样覆盖游戏的 SurfaceView/native 渲染层。
 *
 * 代价: --timestats -enable 是 SF 全局开关(有轻微系统开销、动了共享状态),
 * 与本项目"差分读不清缓冲、与 Scene FAS 零冲突"的初衷相反, 故仅在 --latency 经
 * 运行时探测确认失效后才启用; 其余设备行为完全不变。stop 时务必 -disable 复位。
 *
 * 取瞬时 FPS 的办法和 --latency 差分一致: enable+clear 一次后, 每窗口 -dump 读
 * 目标层累计 totalFrames, 与上窗口做差 / 窗口秒数(而非用 averageFPS 的累计平均)。*/
static void timestats_enable(void) {
    char buf[64];
    const char *args[] = { "--timestats", "-enable", "-clear" };
    if (sf_dump_binder(args, 3, buf, sizeof(buf)) >= 0) {
        g_timestats_enabled = true;
    }
}

static void timestats_disable(void) {
    char buf[64];
    const char *args[] = { "--timestats", "-disable" };
    sf_dump_binder(args, 2, buf, sizeof(buf));
    g_timestats_enabled = false;
}

/* dump timestats, 在含 pkg 的层里取累计 totalFrames 最大者(即主渲染层)。
 * 输出每层一段, 形如:
 *   layerName = SurfaceView[com.x/...]@0(BLAST)#132833
 *   totalFrames = 274
 *   averageFPS = 30.179
 * 返回最大 totalFrames; 无匹配层返回 -1。 */
static long timestats_frames(const char *pkg) {
    static char out[1 << 18];
    const char *args[] = { "--timestats", "-dump" };
    if (sf_dump_binder(args, 2, out, sizeof(out)) <= 0) return -1;

    long best = -1;
    int in_target = 0;
    char *save = NULL;
    for (char *line = strtok_r(out, "\n", &save); line;
         line = strtok_r(NULL, "\n", &save)) {
        if (strstr(line, "layerName")) {
            in_target = (pkg[0] && layer_matches_pkg(line, strlen(line), pkg)) ? 1 : 0;
            continue;
        }
        if (!in_target) continue;
        char *tf = strstr(line, "totalFrames");
        if (!tf) continue;
        char *eq = strchr(tf, '=');
        if (!eq) continue;
        long v = strtol(eq + 1, NULL, 10);
        if (v > best) best = v;
        in_target = 0;
    }
    return best;
}

/* ===================== 公共 API ===================== */

fps_fallback_ctx *fps_fallback_start(const char *pkg) {
    fps_fallback_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    fps_fallback_register_cleanup();

    strncpy(ctx->pkg, pkg, sizeof(ctx->pkg) - 1);
    ctx->pkg[sizeof(ctx->pkg) - 1] = '\0';
    ctx->main_layer[0] = '\0';
    ctx->since = 0ULL;
    ctx->miss = 0;
    ctx->probe_fail = 0;
    ctx->ts_mode = false;
    ctx->ts_last = -1;
    ctx->ts_last_time = 0;

    printf("[Fallback] 启动 SF dump 监测: %s (--latency binder 直连差分读)\n", pkg);
    return ctx;
}

double fps_fallback_poll(fps_fallback_ctx *ctx) {
    if (!ctx) return 0.0;

    /* === timestats 回退模式 === */
    if (ctx->ts_mode) {
        long now = timestats_frames(ctx->pkg);
        time_t now_time = time(NULL);
        if (now < 0) return 0.0;

        if (ctx->ts_last >= 0 && now >= ctx->ts_last && ctx->ts_last_time > 0) {
            double dt = difftime(now_time, ctx->ts_last_time);
            if (dt > 0.0) {
                double fps = (double)(now - ctx->ts_last) / dt;
                ctx->ts_last = now;
                ctx->ts_last_time = now_time;
                return fps;
            }
        }
        ctx->ts_last = now;
        ctx->ts_last_time = now_time;
        return 0.0;
    }

    /* === 默认 --latency 差分模式 === */

    /* 未锁主层 / 主层失效: 重新发现 */
    if (ctx->main_layer[0] == '\0') {
        static char cands[SF_MAX_CANDS][SF_LAYER_MAX];
        int nc = list_candidates(ctx->pkg, cands, SF_MAX_CANDS);
        int best = -1;
        double best_fps = -1.0;
        unsigned long long best_ts = 0ULL;
        int valid_total = 0;
        unsigned long long now_ns = now_monotonic_ns();

        for (int i = 0; i < nc; i++) {
            unsigned long long ts = 0ULL;
            unsigned long long latest = 0ULL;
            int valid = 0;
            double f = layer_fps(cands[i], &ts, &valid, &latest);
            valid_total += valid;
            if (!latency_ts_is_fresh(latest, now_ns)) continue;
            if (f > best_fps) {
                best_fps = f;
                best = i;
                best_ts = ts;
            }
        }

        if (best >= 0 && best_fps >= 0.0) {
            strncpy(ctx->main_layer, cands[best], sizeof(ctx->main_layer) - 1);
            ctx->main_layer[sizeof(ctx->main_layer) - 1] = '\0';
            ctx->miss = 0;
            ctx->probe_fail = 0;
            ctx->since = best_ts;
            printf("[Fallback] 锁定主层(binder 直连): %s (~%.1f fps)\n", ctx->main_layer, best_fps);
            return best_fps;
        } else {
            /* 只有完全没有有效帧时间戳,才说明 --latency 可能不可用。
             * 如果有时间戳但都不活跃,只是没找到当前主层,继续等/重试,不要切 timestats。 */
            if (nc > 0 && valid_total == 0 && ++ctx->probe_fail >= FPS_PROBE_FAIL) {
                ctx->ts_mode = true;
                ctx->ts_last = -1;
                timestats_enable();
                printf("[Fallback] --latency 连续 %d 窗口无有效帧时间戳,切换到 timestats (binder 直连)\n",
                       ctx->probe_fail);
            } else if (valid_total > 0) {
                ctx->probe_fail = 0;
            }
            return 0.0;
        }
    }

    /* 已锁主层: 差分读 */
    int valid = 0;
    unsigned long long latest = 0ULL;
    double fps = layer_fps(ctx->main_layer, &ctx->since, &valid, &latest);
    if (fps < 0.0) {
        if (valid > 0 && latency_ts_is_fresh(latest, now_monotonic_ns())) {
            ctx->miss = 0;
            ctx->probe_fail = 0;
            return 0.0;
        }
        if (++ctx->miss >= FPS_RELOCK_MISS) {
            ctx->main_layer[0] = '\0';
            ctx->miss = 0;
            ctx->since = 0ULL;
        }
        return 0.0;
    } else {
        ctx->miss = 0;
        ctx->probe_fail = 0;
        return fps;
    }
}

void fps_fallback_stop(fps_fallback_ctx *ctx) {
    if (!ctx) return;
    if (ctx->ts_mode) timestats_disable();
    printf("[Fallback] 停止监测: %s\n", ctx->pkg);
    free(ctx);
}
