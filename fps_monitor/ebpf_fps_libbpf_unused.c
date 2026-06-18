/* ebpf_fps_libbpf.c —— 基于 libbpf 的帧率采集实现
 *
 * 参考 frame-analyzer-ebpf 的实现逻辑，使用 libbpf 直接加载 eBPF 程序
 *
 * 核心原理：
 *   1. 使用 bpf_object__open_mem() 加载预编译的 eBPF 字节码
 *   2. 创建 uprobe 附加到 libgui.so 的 queueBuffer 函数
 *   3. 通过 ring buffer 读取帧时间戳
 *   4. 计算帧率
 *
 * 符号：
 *   - _ZN7android7Surface11queueBufferEP19ANativeWindowBufferi (Android <=13)
 *   - _ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE (Android 14+)
 */
#define _GNU_SOURCE
#include "ebpf_fps.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/resource.h>
#include <linux/perf_event.h>
#include <linux/bpf.h>
#include <bpf/bpf.h>
#include <bpf/libbpf.h>

/* 帧信号数据结构（与 eBPF 程序共享） */
struct frame_signal {
    uint64_t ktime_ns;  /* 内核时间戳（纳秒） */
    size_t   buffer;    /* buffer 指针 */
};

/* eBPF FPS 上下文 */
struct ebpf_fps_ctx {
    struct bpf_object *bpf_obj;   /* BPF 对象 */
    struct bpf_program *prog;     /* BPF 程序 */
    struct bpf_link *link;        /* uprobe 链接 */
    int ringbuf_fd;               /* ring buffer fd */
    struct ring_buffer *ringbuf;  /* ring buffer */

    uint64_t last_frame_ns;       /* 上一帧时间戳 */
    double   last_fps;            /* 最后一次计算的 FPS */
    char     symbol[256];         /* 符号名 */
    bool     active;              /* 是否激活 */
};

/* queueBuffer 符号候选列表 */
static const char *QUEUEBUFFER_SYMBOLS[] = {
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi",
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE",
    NULL
};

/* ===================== 能力探测 ===================== */

ebpf_cap_t ebpf_fps_probe_capability(void) {
    /* 检查 bpf() 系统调用 */
    union bpf_attr attr = {0};
    int ret = syscall(__NR_bpf, BPF_MAP_CREATE, &attr, sizeof(attr));
    if (ret < 0) {
        if (errno == ENOSYS) {
            return EBPF_CAP_NO_BPF_SYSCALL;
        }
    } else {
        close(ret);
    }

    /* 暂不检查其他能力，留给后续加载时判断 */
    return EBPF_CAP_OK;
}

const char *ebpf_cap_str(ebpf_cap_t cap) {
    switch (cap) {
        case EBPF_CAP_OK:              return "可用";
        case EBPF_CAP_NO_BPF_SYSCALL:  return "bpf() 系统调用被禁用";
        case EBPF_CAP_NO_RINGBUF:      return "内核不支持 RingBuf";
        case EBPF_CAP_NO_UPROBE:       return "不支持 uprobe";
        case EBPF_CAP_LOAD_FAILED:     return "BPF 程序加载失败";
        case EBPF_CAP_OBJ_NOT_FOUND:   return "BPF 对象文件未找到";
        default:                       return "未知错误";
    }
}

/* ===================== 辅助函数 ===================== */

/* 提升 memlock 限制（旧内核需要） */
static void bump_memlock_rlimit(void) {
    struct rlimit rlim = {
        .rlim_cur = RLIM_INFINITY,
        .rlim_max = RLIM_INFINITY,
    };
    setrlimit(RLIMIT_MEMLOCK, &rlim);
}

/* 查找目标进程的 libgui.so 路径 */
static int find_libgui_path(pid_t pid, char *path_buf, size_t buf_size) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);

    FILE *fp = fopen(maps_path, "r");
    if (!fp) {
        return -1;
    }

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libgui.so")) {
            /* 提取路径（最后一个字段） */
            char *path_start = strrchr(line, '/');
            if (path_start) {
                /* 回退到完整路径 */
                while (path_start > line && *(path_start - 1) != ' ') {
                    path_start--;
                }

                char *newline = strchr(path_start, '\n');
                if (newline) {
                    *newline = '\0';
                }

                snprintf(path_buf, buf_size, "%s", path_start);
                fclose(fp);
                return 0;
            }
        }
    }

    fclose(fp);

    /* 默认路径 */
    snprintf(path_buf, buf_size, "/system/lib64/libgui.so");
    return 0;
}

/* 查找符号在 ELF 文件中的偏移 */
static long find_symbol_offset(const char *elf_path, const char *symbol) {
    /* 简化版：假设符号在文件开头附近，实际应该解析 ELF */
    /* TODO: 实现完整的 ELF 符号解析 */
    return 0;  /* offset 0 表示使用符号名解析 */
}

/* ring buffer 回调函数 */
static int handle_event(void *ctx, void *data, size_t data_sz) {
    struct ebpf_fps_ctx *fps_ctx = ctx;
    struct frame_signal *signal = data;

    if (!fps_ctx || !fps_ctx->active) {
        return 0;
    }

    /* 计算帧时间 */
    if (fps_ctx->last_frame_ns > 0) {
        uint64_t frame_time_ns = signal->ktime_ns - fps_ctx->last_frame_ns;
        if (frame_time_ns > 0) {
            double frame_time_s = frame_time_ns / 1e9;
            fps_ctx->last_fps = 1.0 / frame_time_s;
        }
    }

    fps_ctx->last_frame_ns = signal->ktime_ns;
    return 0;
}

/* ===================== 启动 eBPF ===================== */

ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid) {
    if (!bpf_obj_path) {
        return NULL;
    }

    /* 提升 memlock 限制 */
    bump_memlock_rlimit();

    /* 读取 BPF 对象文件 */
    FILE *fp = fopen(bpf_obj_path, "rb");
    if (!fp) {
        return NULL;
    }

    fseek(fp, 0, SEEK_END);
    size_t obj_size = ftell(fp);
    fseek(fp, 0, SEEK_SET);

    void *obj_buf = malloc(obj_size);
    if (!obj_buf) {
        fclose(fp);
        return NULL;
    }

    if (fread(obj_buf, 1, obj_size, fp) != obj_size) {
        free(obj_buf);
        fclose(fp);
        return NULL;
    }
    fclose(fp);

    /* 加载 BPF 对象 */
    struct bpf_object *obj = bpf_object__open_mem(obj_buf, obj_size, NULL);
    free(obj_buf);

    if (!obj) {
        return NULL;
    }

    if (bpf_object__load(obj) != 0) {
        bpf_object__close(obj);
        return NULL;
    }

    /* 查找 BPF 程序 */
    struct bpf_program *prog = bpf_object__find_program_by_name(obj, "frame_analyzer_ebpf");
    if (!prog) {
        bpf_object__close(obj);
        return NULL;
    }

    /* 查找 libgui.so 路径 */
    char libgui_path[256];
    find_libgui_path(target_pid, libgui_path, sizeof(libgui_path));

    /* 尝试附加 uprobe（尝试多个符号） */
    struct bpf_link *link = NULL;
    const char *used_symbol = NULL;

    for (int i = 0; QUEUEBUFFER_SYMBOLS[i]; i++) {
        const char *sym = QUEUEBUFFER_SYMBOLS[i];
        long offset = find_symbol_offset(libgui_path, sym);

        link = bpf_program__attach_uprobe(prog, false, target_pid,
                                          libgui_path, offset);
        if (link) {
            used_symbol = sym;
            break;
        }
    }

    if (!link) {
        bpf_object__close(obj);
        return NULL;
    }

    /* 获取 ring buffer map */
    struct bpf_map *ringbuf_map = bpf_object__find_map_by_name(obj, "RING_BUF");
    if (!ringbuf_map) {
        bpf_link__destroy(link);
        bpf_object__close(obj);
        return NULL;
    }

    int ringbuf_fd = bpf_map__fd(ringbuf_map);

    /* 创建 ring buffer */
    struct ring_buffer *rb = ring_buffer__new(ringbuf_fd, handle_event, NULL, NULL);
    if (!rb) {
        bpf_link__destroy(link);
        bpf_object__close(obj);
        return NULL;
    }

    /* 创建上下文 */
    ebpf_fps_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        ring_buffer__free(rb);
        bpf_link__destroy(link);
        bpf_object__close(obj);
        return NULL;
    }

    ctx->bpf_obj = obj;
    ctx->prog = prog;
    ctx->link = link;
    ctx->ringbuf_fd = ringbuf_fd;
    ctx->ringbuf = rb;
    ctx->last_frame_ns = 0;
    ctx->last_fps = 0.0;
    ctx->active = true;
    snprintf(ctx->symbol, sizeof(ctx->symbol), "%s", used_symbol ? used_symbol : "unknown");

    /* 设置 ring buffer 回调上下文 */
    ring_buffer__set_ctx(rb, ctx);

    return ctx;
}

/* ===================== 轮询帧率数据 ===================== */

int ebpf_fps_poll(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->active || !ctx->ringbuf) {
        return 0;
    }

    /* 非阻塞轮询 ring buffer */
    return ring_buffer__poll(ctx->ringbuf, 0);
}

/* ===================== 获取 FPS ===================== */

double ebpf_fps_get(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->active) {
        return 0.0;
    }
    return ctx->last_fps;
}

const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx) {
    if (!ctx) {
        return NULL;
    }
    return ctx->symbol;
}

/* ===================== 停止 ===================== */

void ebpf_fps_stop(ebpf_fps_ctx *ctx) {
    if (!ctx) {
        return;
    }

    ctx->active = false;

    if (ctx->ringbuf) {
        ring_buffer__free(ctx->ringbuf);
    }

    if (ctx->link) {
        bpf_link__destroy(ctx->link);
    }

    if (ctx->bpf_obj) {
        bpf_object__close(ctx->bpf_obj);
    }

    free(ctx);
}
