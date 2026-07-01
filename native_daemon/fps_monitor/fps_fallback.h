/* fps_fallback.h —— SurfaceFlinger dump 回退方案 API
 *
 * 当 eBPF 不可用时, 使用 SurfaceFlinger dump 获取帧率:
 *   策略1: dumpsys SurfaceFlinger --latency (差分读,逐层,快速)
 *   策略2: dumpsys SurfaceFlinger --timestats (累计统计,兜底)
 *
 * 本模块会自动探测 --latency 是否可用(Android 16 等会失效),
 * 失效时自动切换到 --timestats。
 *
 * 依赖: AppOpt.c 提供的 sf_dump_binder() 函数(binder 直连,失败自动回退 CLI)。 */
#ifndef APPOPT_FPS_FALLBACK_H
#define APPOPT_FPS_FALLBACK_H

#include <stdbool.h>
#include <sys/types.h>

/* 外部依赖: AppOpt.c 提供的 SF binder 直连接口(编译时链接) */
extern ssize_t sf_dump_binder(const char *const args[], int nargs,
                                char *buf, size_t bufsz);

/* Fallback 上下文(不透明句柄) */
typedef struct fps_fallback_ctx fps_fallback_ctx;

/* 启动 fallback FPS 监测。
 * pkg: 目标游戏包名(用于匹配 layer)
 * 返回 ctx, 失败返回 NULL。 */
fps_fallback_ctx *fps_fallback_start(const char *pkg);

/* 轮询并返回当前 FPS。
 * 应每隔 500ms~1s 调用一次(--latency 窗口)。
 * 返回 FPS(>= 0.0), 无数据/失败返回 0.0。 */
double fps_fallback_poll(fps_fallback_ctx *ctx);

/* 停止监测,释放资源。 */
void fps_fallback_stop(fps_fallback_ctx *ctx);

#endif /* APPOPT_FPS_FALLBACK_H */
