/* ebpf_fps.c —— eBPF uprobe 游戏提交帧率采集实现(不依赖外部 libbpf)
 *
 * 为什么不链接 libbpf.a:
 *   libbpf 静态库约 300KB+, 且对 BTF/CO-RE 有较多假设, 在 Android 各版本上行为
 *   不一。本模块的 BPF 程序极简(单 uprobe + 单 RingBuf, 无 BTF 依赖), 因此直接
 *   用 bpf() 系统调用手工: 解析 .bpf.o ELF -> 创建 map -> 重定位 map fd -> 加载
 *   prog -> perf_event_open attach uprobe -> mmap RingBuf 读事件。约 600 行, 全自包含。
 */
#define _GNU_SOURCE
#include "ebpf_fps.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <elf.h>
#include <dirent.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <linux/perf_event.h>
#include <time.h>

/* perf 常量补充 */
#ifndef PERF_TYPE_TRACEPOINT
#define PERF_TYPE_TRACEPOINT 2
#endif
#ifndef PERF_FLAG_FD_CLOEXEC
#define PERF_FLAG_FD_CLOEXEC (1UL << 3)
#endif

/* ===================== bpf() 系统调用封装 ===================== */

/* 复制 uapi/linux/bpf.h 中需要用到的最小子集, 避免依赖完整内核头文件 */
#ifndef __NR_bpf
# if defined(__aarch64__)
#  define __NR_bpf 280
# elif defined(__arm__)
#  define __NR_bpf 386
# elif defined(__x86_64__)
#  define __NR_bpf 321
# elif defined(__i386__)
#  define __NR_bpf 357
# else
#  error "未知架构, 需补充 __NR_bpf"
# endif
#endif

/* bpf 命令 */
enum {
    BPF_MAP_CREATE   = 0,
    BPF_PROG_LOAD    = 5,
    BPF_LINK_CREATE  = 28,   /* 内核 5.7+ */
};
/* map 类型 */
enum {
    BPF_MAP_TYPE_RINGBUF_ = 27,
};
/* 程序类型 */
enum {
    BPF_PROG_TYPE_KPROBE_ = 2,   /* uprobe 复用 KPROBE 程序类型 */
};
/* link 类型 */
enum {
    BPF_LINK_TYPE_PERF_EVENT_ = 7,
};
/* attach 类型 */
enum {
    BPF_PERF_EVENT_UPROBE_ = 0,
};

/* union bpf_attr 的精简版(只含本模块用到的字段, 布局与内核一致)。
 * 内核按 attr_size 读取, 多余字节填 0 即可。这里用足够大的匿名结构对齐。 */
union bpf_attr_compat {
    /* BPF_MAP_CREATE */
    struct {
        uint32_t map_type;
        uint32_t key_size;
        uint32_t value_size;
        uint32_t max_entries;
        uint32_t map_flags;
        uint32_t inner_map_fd;
        uint32_t numa_node;
        char     map_name[16];
        uint32_t map_ifindex;
        uint32_t btf_fd;
        uint32_t btf_key_type_id;
        uint32_t btf_value_type_id;
    } map_create;
    /* BPF_PROG_LOAD */
    struct {
        uint32_t prog_type;
        uint32_t insn_cnt;
        uint64_t insns;
        uint64_t license;
        uint32_t log_level;
        uint32_t log_size;
        uint64_t log_buf;
        uint32_t kern_version;
        uint32_t prog_flags;
        char     prog_name[16];
    } prog_load;
    /* BPF_LINK_CREATE */
    struct {
        uint32_t prog_fd;
        uint32_t target_fd;   /* perf_event fd for uprobe */
        uint32_t attach_type;
        uint32_t flags;
    } link_create;
    /* 保证 union 至少和内核 bpf_attr 一样大, 防止内核越界读 */
    char _pad[128];
};

static int sys_bpf(int cmd, union bpf_attr_compat *attr, unsigned int size) {
    return (int)syscall(__NR_bpf, cmd, attr, size);
}

/* ===================== BPF 指令(用于重定位) ===================== */

/* struct bpf_insn 布局(8 字节) */
struct bpf_insn {
    uint8_t  code;
    uint8_t  dst_reg : 4;
    uint8_t  src_reg : 4;
    int16_t  off;
    int32_t  imm;
};

/* BPF_PSEUDO_MAP_FD: ld_imm64 指令的 src_reg 标记, 表示 imm 是 map fd */
#define BPF_PSEUDO_MAP_FD 1
#define BPF_LD_IMM64_OPCODE 0x18   /* BPF_LD | BPF_DW | BPF_IMM */

/* ===================== libgui 帧提交候选符号表 ===================== */

/* 按优先级排列(详见设计文档 §6)。运行时逐个尝试, 第一个能产生事件的即锁定。 */
static const char *LIBGUI_FRAME_SYMBOLS[] = {
    /* ANativeWindow 入口, 最贴近 app 提交; 实测可命中主流引擎渲染线程 */
    "_ZN7android7Surface16hook_queueBufferEP13ANativeWindowP19ANativeWindowBufferi",
    /* 旧版 deprecated 入口 */
    "_ZN7android7Surface27hook_queueBuffer_DEPRECATEDEP13ANativeWindowP19ANativeWindowBuffer",
    /* 新版 queueBuffer ABI(A14+ 可能) */
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferiPNS_24SurfaceQueueBufferOutputE",
    /* 旧版 queueBuffer ABI(A12-13, 部分 14-16) */
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi",
    /* 内部实现 fallback */
    "_ZN7android7Surface19queueBufferInternalEP13ANativeWindowP19ANativeWindowBufferi",
    NULL
};

/* ===================== 帧事件结构(与 BPF 程序一致) ===================== */

struct frame_event {
    uint64_t timestamp_ns;
    uint32_t pid;
    uint32_t tid;
    uint64_t surface_ptr;
};

/* ===================== FPS 滑动窗口 ===================== */

#define FPS_WINDOW_CAP 256          /* 最多保留最近 256 帧时间戳 */
#define FPS_WINDOW_NS  1000000000ULL/* 1 秒窗口 */
#define MIN_FRAME_NS   1000000ULL   /* <1ms 视为重复事件, 丢弃 */
#define MAX_FRAME_NS   200000000ULL /* >200ms 视为暂停/loading, 不计瞬时帧间隔 */

/* ===================== context 定义 ===================== */

struct ebpf_fps_ctx {
    int prog_fd;
    int map_fd;
    int perf_fd;                    /* uprobe attach 的 perf_event fd */
    void *ring_mem;                 /* mmap 的 RingBuf(数据区) */
    void *ring_meta;               /* mmap 的 RingBuf 元数据页(consumer/producer pos) */
    size_t ring_data_size;          /* 数据区大小(= max_entries) */
    pid_t target_pid;
    const char *locked_symbol;      /* 锁定的帧提交符号名 */

    /* 滑动窗口: 环形数组存最近帧时间戳 */
    uint64_t ts_ring[FPS_WINDOW_CAP];
    int ts_head;                    /* 下一个写入位置 */
    int ts_count;                   /* 当前有效帧数 */
    uint64_t last_ts;               /* 上一帧时间戳(BPF ktime_ns, 仅用于帧间隔过滤) */
    double cur_fps;                 /* 最近算出的瞬时 FPS */
    time_t last_frame_time;         /* 最后收到帧的 wall time(用于无帧归零判断) */
};

/* ===================== 能力探测 ===================== */

ebpf_cap_t ebpf_fps_probe_capability(void) {
    /* 尝试创建一个 RingBuf map: 成功即说明 bpf syscall + ringbuf 都可用 */
    union bpf_attr_compat attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_create.map_type = BPF_MAP_TYPE_RINGBUF_;
    attr.map_create.key_size = 0;
    attr.map_create.value_size = 0;
    attr.map_create.max_entries = 4096;

    int fd = sys_bpf(BPF_MAP_CREATE, &attr, sizeof(attr));
    if (fd < 0) {
        if (errno == ENOSYS || errno == EPERM)
            return EBPF_CAP_NO_BPF_SYSCALL;
        if (errno == EINVAL)
            return EBPF_CAP_NO_RINGBUF;   /* 类型不认识 -> 无 ringbuf 支持 */
        return EBPF_CAP_NO_BPF_SYSCALL;
    }
    close(fd);
    return EBPF_CAP_OK;
}

const char *ebpf_cap_str(ebpf_cap_t cap) {
    switch (cap) {
        case EBPF_CAP_OK:             return "可用";
        case EBPF_CAP_NO_BPF_SYSCALL: return "bpf系统调用被禁用";
        case EBPF_CAP_NO_RINGBUF:     return "内核不支持RingBuf";
        case EBPF_CAP_NO_UPROBE:      return "不支持uprobe";
        case EBPF_CAP_LOAD_FAILED:    return "BPF程序加载失败";
        case EBPF_CAP_OBJ_NOT_FOUND:  return "找不到BPF字节码文件";
        default:                      return "未知";
    }
}

/* ===================== ELF 符号解析(目标进程 libgui.so) ===================== */

/* 从 /proc/<pid>/maps 找到该进程实际加载的 libgui.so 路径(区分 lib64/lib)。
 * 写入 out(容量 outsz), 成功返回 true。 */
static bool find_libgui_path(pid_t pid, char *out, size_t outsz) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", (int)pid);
    FILE *f = fopen(maps_path, "r");
    if (!f) return false;

    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        /* maps 行尾是路径; 找含 "libgui.so" 的可执行映射 */
        char *p = strstr(line, "/");
        if (!p) continue;
        if (strstr(p, "libgui.so")) {
            /* 去掉行尾换行 */
            size_t L = strlen(p);
            while (L > 0 && (p[L-1] == '\n' || p[L-1] == '\r')) p[--L] = '\0';
            strncpy(out, p, outsz - 1);
            out[outsz - 1] = '\0';
            found = true;
            break;
        }
    }
    fclose(f);
    return found;
}

/* 在 ELF 文件中查找符号, 返回其 file offset(用于 uprobe attach)。
 * 同时扫描 .dynsym 与 .symtab(若存在)。找不到返回 0。
 * file_offset = st_value - LOAD.p_vaddr + LOAD.p_offset (见设计文档 §10)。 */
static uint64_t resolve_symbol_file_offset(const char *lib_path, const char *symbol) {
    int fd = open(lib_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    Elf64_Ehdr eh;
    if (read(fd, &eh, sizeof(eh)) != (ssize_t)sizeof(eh) ||
        memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 ||
        eh.e_ident[EI_CLASS] != ELFCLASS64) {
        close(fd); return 0;
    }

    /* 读 section header 表 */
    Elf64_Shdr *shs = malloc((size_t)eh.e_shnum * sizeof(Elf64_Shdr));
    if (!shs) { close(fd); return 0; }
    if (pread(fd, shs, (size_t)eh.e_shnum * sizeof(Elf64_Shdr), eh.e_shoff)
        != (ssize_t)((size_t)eh.e_shnum * sizeof(Elf64_Shdr))) {
        free(shs); close(fd); return 0;
    }

    /* 读 program header 表(算 file offset 需要 LOAD 段) */
    Elf64_Phdr *phs = malloc((size_t)eh.e_phnum * sizeof(Elf64_Phdr));
    if (!phs) { free(shs); close(fd); return 0; }
    if (pread(fd, phs, (size_t)eh.e_phnum * sizeof(Elf64_Phdr), eh.e_phoff)
        != (ssize_t)((size_t)eh.e_phnum * sizeof(Elf64_Phdr))) {
        free(phs); free(shs); close(fd); return 0;
    }

    uint64_t result = 0;

    /* 遍历所有 SYMTAB/DYNSYM section */
    for (int i = 0; i < eh.e_shnum && result == 0; i++) {
        if (shs[i].sh_type != SHT_SYMTAB && shs[i].sh_type != SHT_DYNSYM)
            continue;
        Elf64_Shdr *strsh = &shs[shs[i].sh_link];   /* 关联的字符串表 */

        size_t nsyms = shs[i].sh_size / sizeof(Elf64_Sym);
        Elf64_Sym *syms = malloc(shs[i].sh_size);
        char *strtab = malloc(strsh->sh_size);
        if (!syms || !strtab) { free(syms); free(strtab); continue; }

        if (pread(fd, syms, shs[i].sh_size, shs[i].sh_offset) == (ssize_t)shs[i].sh_size &&
            pread(fd, strtab, strsh->sh_size, strsh->sh_offset) == (ssize_t)strsh->sh_size) {

            for (size_t s = 0; s < nsyms; s++) {
                if (ELF64_ST_TYPE(syms[s].st_info) != STT_FUNC) continue;
                if (syms[s].st_value == 0) continue;
                if (syms[s].st_name >= strsh->sh_size) continue;
                const char *name = strtab + syms[s].st_name;
                if (strcmp(name, symbol) != 0) continue;

                /* 找到符号; 用 LOAD 段把 vaddr 转 file offset */
                uint64_t st_value = syms[s].st_value;
                for (int p = 0; p < eh.e_phnum; p++) {
                    if (phs[p].p_type != PT_LOAD) continue;
                    if (!(phs[p].p_flags & PF_X)) continue;   /* 可执行段 */
                    if (st_value >= phs[p].p_vaddr &&
                        st_value < phs[p].p_vaddr + phs[p].p_memsz) {
                        result = st_value - phs[p].p_vaddr + phs[p].p_offset;
                        break;
                    }
                }
                if (result) break;
            }
        }
        free(syms); free(strtab);
    }

    free(phs); free(shs); close(fd);
    return result;
}

/* ===================== BPF 对象加载 ===================== */

/* 从 .bpf.o 加载: 解析 ELF, 创建 RingBuf map, 重定位 prog 中的 map fd, 加载 prog。
 * 成功设置 *out_prog_fd / *out_map_fd 返回 0, 失败返回 -1。 */
static int load_bpf_object(const char *path, int *out_prog_fd, int *out_map_fd) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;

    /* 整文件读入内存 */
    Elf64_Ehdr eh;
    if (read(fd, &eh, sizeof(eh)) != (ssize_t)sizeof(eh) ||
        memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0) {
        close(fd); return -1;
    }
    off_t fsz = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (fsz <= 0 || fsz > (1 << 20)) { close(fd); return -1; }
    char *buf = malloc(fsz);
    if (!buf || read(fd, buf, fsz) != fsz) { free(buf); close(fd); return -1; }
    close(fd);

    Elf64_Shdr *shs = (Elf64_Shdr *)(buf + eh.e_shoff);
    const char *shstr = buf + shs[eh.e_shstrndx].sh_offset;

    /* 定位 section: prog(uprobe/...), maps(.maps), license, 以及 prog 的重定位段 */
    Elf64_Shdr *prog_sh = NULL, *rel_sh = NULL, *symtab_sh = NULL;
    int prog_idx = -1;
    for (int i = 0; i < eh.e_shnum; i++) {
        const char *nm = shstr + shs[i].sh_name;
        if (strncmp(nm, "uprobe/", 7) == 0 && shs[i].sh_type == SHT_PROGBITS) {
            prog_sh = &shs[i]; prog_idx = i;
        }
        if (shs[i].sh_type == SHT_SYMTAB) symtab_sh = &shs[i];
    }
    if (!prog_sh) { free(buf); return -1; }

    /* 找针对 prog section 的重定位段 .reluprobe/... */
    for (int i = 0; i < eh.e_shnum; i++) {
        if (shs[i].sh_type == SHT_REL && (int)shs[i].sh_info == prog_idx) {
            rel_sh = &shs[i]; break;
        }
    }

    /* 1. 创建 RingBuf map */
    union bpf_attr_compat attr;
    memset(&attr, 0, sizeof(attr));
    attr.map_create.map_type = BPF_MAP_TYPE_RINGBUF_;
    attr.map_create.max_entries = 4096;
    strncpy(attr.map_create.map_name, "events", sizeof(attr.map_create.map_name)-1);
    int map_fd = sys_bpf(BPF_MAP_CREATE, &attr, sizeof(attr));
    if (map_fd < 0) { free(buf); return -1; }

    /* 2. 复制 prog 指令到可写缓冲并做 map fd 重定位 */
    size_t insn_cnt = prog_sh->sh_size / sizeof(struct bpf_insn);
    struct bpf_insn *insns = malloc(prog_sh->sh_size);
    if (!insns) { close(map_fd); free(buf); return -1; }
    memcpy(insns, buf + prog_sh->sh_offset, prog_sh->sh_size);

    /* 重定位: 把引用 map 的 ld_imm64 指令的 imm 改成 map_fd, src_reg 标 PSEUDO_MAP_FD */
    if (rel_sh && symtab_sh) {
        Elf64_Rel *rels = (Elf64_Rel *)(buf + rel_sh->sh_offset);
        size_t nrel = rel_sh->sh_size / sizeof(Elf64_Rel);
        for (size_t r = 0; r < nrel; r++) {
            uint64_t roff = rels[r].r_offset;           /* 指令内字节偏移 */
            size_t iidx = roff / sizeof(struct bpf_insn);
            if (iidx >= insn_cnt) continue;
            /* 该指令应是 ld_imm64(引用 map), 设为 map fd */
            if (insns[iidx].code == BPF_LD_IMM64_OPCODE) {
                insns[iidx].src_reg = BPF_PSEUDO_MAP_FD;
                insns[iidx].imm = map_fd;
            }
        }
    }

    /* 3. 加载 prog */
    static char log_buf[8192];
    memset(&attr, 0, sizeof(attr));
    attr.prog_load.prog_type = BPF_PROG_TYPE_KPROBE_;
    attr.prog_load.insn_cnt = (uint32_t)insn_cnt;
    attr.prog_load.insns = (uint64_t)(uintptr_t)insns;
    attr.prog_load.license = (uint64_t)(uintptr_t)"GPL";
    attr.prog_load.log_level = 1;
    attr.prog_load.log_size = sizeof(log_buf);
    attr.prog_load.log_buf = (uint64_t)(uintptr_t)log_buf;
    strncpy(attr.prog_load.prog_name, "qbuf_fps", sizeof(attr.prog_load.prog_name)-1);

    int prog_fd = sys_bpf(BPF_PROG_LOAD, &attr, sizeof(attr));
    if (prog_fd < 0) {
        fprintf(stderr, "[eBPF] prog load 失败: %s\n  verifier log:\n%s\n",
                strerror(errno), log_buf);
        close(map_fd); free(insns); free(buf); return -1;
    }

    free(insns);
    free(buf);
    *out_prog_fd = prog_fd;
    *out_map_fd = map_fd;
    return 0;
}

/* ===================== uprobe attach ===================== */

/* 通过 perf_event_open 创建 uprobe 并绑定 BPF 程序。
 * 用 PMU 动态类型(/sys/bus/event_source/devices/uprobe/type)创建。
 * 成功返回 perf_fd, 失败返回 -1。 */
/* ===================== uprobe 附加 ===================== */

/* 方法 1: tracefs uprobe (frame-analyzer-ebpf 方式)
 * 优点: 不依赖 /sys/bus/event_source/devices/uprobe/type
 * 原理: 直接写 /sys/kernel/[debug/]tracing/uprobe_events 注册 uprobe
 */
static int attach_uprobe_tracefs(const char *lib_path, uint64_t offset, pid_t pid, int prog_fd) {
    /* 尝试 tracefs 位置 */
    const char *tracefs_paths[] = {
        "/sys/kernel/tracing/uprobe_events",
        "/sys/kernel/debug/tracing/uprobe_events",
        NULL
    };

    const char *uprobe_events = NULL;
    for (int i = 0; tracefs_paths[i]; i++) {
        if (access(tracefs_paths[i], W_OK) == 0) {
            uprobe_events = tracefs_paths[i];
            break;
        }
    }
    if (!uprobe_events) return -1;

    /* 生成唯一的 uprobe 事件名 */
    char event_name[64];
    snprintf(event_name, sizeof(event_name), "appopt_probe_%d_%lx",
             (int)pid, (unsigned long)offset);

    /* 写入 uprobe_events: p:event_name path:offset */
    FILE *fp = fopen(uprobe_events, "a");
    if (!fp) return -1;

    fprintf(fp, "p:%s %s:0x%lx\n", event_name, lib_path, (unsigned long)offset);
    fclose(fp);

    /* 打开 events/uprobes/event_name/id 获取事件 ID */
    char id_path[256];
    snprintf(id_path, sizeof(id_path),
             "%s/../events/uprobes/%s/id",
             uprobe_events, event_name);

    fp = fopen(id_path, "r");
    if (!fp) {
        /* 清理注册的事件 */
        fp = fopen(uprobe_events, "a");
        if (fp) {
            fprintf(fp, "-:%s\n", event_name);
            fclose(fp);
        }
        return -1;
    }

    int event_id = -1;
    if (fscanf(fp, "%d", &event_id) != 1) {
        fclose(fp);
        return -1;
    }
    fclose(fp);

    /* 用 perf_event_open 打开该事件 */
    struct perf_event_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.type = PERF_TYPE_TRACEPOINT;
    attr.size = sizeof(attr);
    attr.config = event_id;

    int perf_fd = (int)syscall(__NR_perf_event_open, &attr, pid, -1, -1, PERF_FLAG_FD_CLOEXEC);
    if (perf_fd < 0) {
        /* 清理 */
        fp = fopen(uprobe_events, "a");
        if (fp) {
            fprintf(fp, "-:%s\n", event_name);
            fclose(fp);
        }
        return -1;
    }

    /* 绑定 BPF 程序 */
    if (ioctl(perf_fd, PERF_EVENT_IOC_SET_BPF, prog_fd) < 0) {
        close(perf_fd);
        fp = fopen(uprobe_events, "a");
        if (fp) {
            fprintf(fp, "-:%s\n", event_name);
            fclose(fp);
        }
        return -1;
    }

    if (ioctl(perf_fd, PERF_EVENT_IOC_ENABLE, 0) < 0) {
        close(perf_fd);
        return -1;
    }

    return perf_fd;
}

/* 方法 2: perf_event ioctl (传统方法, Android 所有版本)
 * 依赖 /sys/bus/event_source/devices/uprobe/type
 */
static int attach_uprobe(const char *lib_path, uint64_t offset, pid_t pid, int prog_fd) {
    /* 读 uprobe PMU type */
    int type = -1;
    FILE *tf = fopen("/sys/bus/event_source/devices/uprobe/type", "r");
    if (tf) { if (fscanf(tf, "%d", &type) != 1) type = -1; fclose(tf); }
    if (type < 0) return -1;   /* 内核无 uprobe PMU */

    struct perf_event_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.type = type;
    attr.size = sizeof(attr);
    /* config1 = uprobe 路径字符串指针; config2 = offset */
    attr.config1 = (uint64_t)(uintptr_t)lib_path;
    attr.config2 = offset;

    /* pid>0, cpu=-1: 只跟踪该进程在任意 CPU 上的命中 */
    int perf_fd = (int)syscall(__NR_perf_event_open, &attr, pid, -1, -1, PERF_FLAG_FD_CLOEXEC);
    if (perf_fd < 0) return -1;

    if (ioctl(perf_fd, PERF_EVENT_IOC_SET_BPF, prog_fd) < 0) {
        close(perf_fd); return -1;
    }
    if (ioctl(perf_fd, PERF_EVENT_IOC_ENABLE, 0) < 0) {
        close(perf_fd); return -1;
    }
    return perf_fd;
}

/* ===================== RingBuf 读取 ===================== */

/* RingBuf 内存布局: [consumer_pos 页][producer_pos 页 + 数据区(2*max_entries)]。
 * 简化: 用 mmap 两次(元数据页可写, 数据区只读, 映射两倍实现环形)。
 * 这里采用内核标准布局, 详见 kernel/bpf/ringbuf.c。 */
static int setup_ringbuf(struct ebpf_fps_ctx *ctx) {
    long page = sysconf(_SC_PAGESIZE);
    ctx->ring_data_size = 4096;   /* 与 map max_entries 一致 */

    /* consumer page(可读写) */
    ctx->ring_meta = mmap(NULL, page, PROT_READ | PROT_WRITE, MAP_SHARED, ctx->map_fd, 0);
    if (ctx->ring_meta == MAP_FAILED) { ctx->ring_meta = NULL; return -1; }

    /* producer page + data(只读), 偏移一页。数据区映射两份实现 wrap-free 读取。 */
    size_t mmap_sz = page + 2 * ctx->ring_data_size;
    ctx->ring_mem = mmap(NULL, mmap_sz, PROT_READ, MAP_SHARED, ctx->map_fd, page);
    if (ctx->ring_mem == MAP_FAILED) {
        munmap(ctx->ring_meta, page); ctx->ring_meta = NULL; ctx->ring_mem = NULL;
        return -1;
    }
    return 0;
}

/* 读 consumer/producer 位置(原子). 内核用 unsigned long. */
static unsigned long ring_load_pos(volatile unsigned long *p) {
    return __atomic_load_n(p, __ATOMIC_ACQUIRE);
}
static void ring_store_consumer(volatile unsigned long *p, unsigned long v) {
    __atomic_store_n(p, v, __ATOMIC_RELEASE);
}

/* RingBuf 记录头: 8 字节 (len + flags). 见内核 BPF_RINGBUF_BUSY_BIT 等定义 */
#define RINGBUF_BUSY_BIT    (1U << 31)
#define RINGBUF_DISCARD_BIT (1U << 30)
#define RINGBUF_HDR_SZ      8

/* 处理一条帧事件: 更新滑动窗口与瞬时 FPS */
static void on_frame(struct ebpf_fps_ctx *ctx, const struct frame_event *ev) {
    uint64_t now = ev->timestamp_ns;

    /* 帧间隔过滤 */
    if (ctx->last_ts != 0) {
        uint64_t dt = now > ctx->last_ts ? now - ctx->last_ts : 0;
        if (dt < MIN_FRAME_NS) { /* 重复事件, 忽略 */ return; }
    }
    ctx->last_ts = now;

    /* 更新最后收到帧的真实时间(用于无帧归零判断) */
    ctx->last_frame_time = time(NULL);

    /* 存入环形窗口 */
    ctx->ts_ring[ctx->ts_head] = now;
    ctx->ts_head = (ctx->ts_head + 1) % FPS_WINDOW_CAP;
    if (ctx->ts_count < FPS_WINDOW_CAP) ctx->ts_count++;

    /* 算最近 1 秒内的帧数 = FPS */
    int cnt = 0;
    for (int i = 0; i < ctx->ts_count; i++) {
        int idx = (ctx->ts_head - 1 - i + FPS_WINDOW_CAP) % FPS_WINDOW_CAP;
        if (now - ctx->ts_ring[idx] <= FPS_WINDOW_NS) cnt++;
        else break;
    }
    ctx->cur_fps = (double)cnt;   /* 1 秒窗口内帧数即 FPS */
}

int ebpf_fps_poll(ebpf_fps_ctx *ctx) {
    if (!ctx || !ctx->ring_mem || !ctx->ring_meta) return 0;
    long page = sysconf(_SC_PAGESIZE);

    volatile unsigned long *consumer = (volatile unsigned long *)ctx->ring_meta;
    volatile unsigned long *producer = (volatile unsigned long *)ctx->ring_mem;
    char *data = (char *)ctx->ring_mem + page;   /* 数据区在 producer 页之后 */
    (void)page;

    unsigned long cons = ring_load_pos(consumer);
    unsigned long prod = ring_load_pos(producer);

    int n = 0;
    while (cons < prod) {
        char *rec = data + (cons & (ctx->ring_data_size - 1));
        uint32_t len_flags = __atomic_load_n((volatile uint32_t *)rec, __ATOMIC_ACQUIRE);
        if (len_flags & RINGBUF_BUSY_BIT) break;   /* 还在写, 下次再读 */

        uint32_t len = len_flags & 0x3fffffff;
        uint32_t total = (RINGBUF_HDR_SZ + len + 7) & ~7U;   /* 8 字节对齐 */

        if (!(len_flags & RINGBUF_DISCARD_BIT) && len >= sizeof(struct frame_event)) {
            const struct frame_event *ev = (const struct frame_event *)(rec + RINGBUF_HDR_SZ);
            on_frame(ctx, ev);
            n++;
        }
        cons += total;
        ring_store_consumer(consumer, cons);
    }
    return n;
}

double ebpf_fps_get(ebpf_fps_ctx *ctx) {
    if (!ctx) return 0.0;
    /* 若超过 2 秒无新帧(用 wall time 判断), FPS 归零 */
    if (ctx->last_frame_time > 0) {
        time_t now = time(NULL);
        if (now > ctx->last_frame_time && now - ctx->last_frame_time > 2) {
            return 0.0;  /* 超时无帧 */
        }
    }
    return ctx->cur_fps;
}

const char *ebpf_fps_symbol(ebpf_fps_ctx *ctx) {
    return ctx ? ctx->locked_symbol : NULL;
}

/* ===================== 启动/停止 ===================== */

/* attach 一个符号并验证: 等待最多 timeout_ms, 期间累计事件 >= need 即算命中 */
static bool try_symbol(struct ebpf_fps_ctx *ctx, const char *lib_path,
                       const char *symbol, int timeout_ms, int need) {
    uint64_t off = resolve_symbol_file_offset(lib_path, symbol);
    if (off == 0) return false;

    /* 优先尝试 tracefs uprobe (frame-analyzer-ebpf 方式，不依赖 uprobe PMU type) */
    int perf_fd = attach_uprobe_tracefs(lib_path, off, ctx->target_pid, ctx->prog_fd);
    if (perf_fd < 0) {
        /* 回退到传统 perf_event ioctl 方法 */
        perf_fd = attach_uprobe(lib_path, off, ctx->target_pid, ctx->prog_fd);
        if (perf_fd < 0) return false;
    }
    ctx->perf_fd = perf_fd;

    /* attach 后才能建立 RingBuf 映射(map 已创建, 此处映射内存) */
    if (!ctx->ring_mem && setup_ringbuf(ctx) < 0) {
        close(perf_fd); ctx->perf_fd = -1; return false;
    }

    /* 验证窗口: 轮询事件 */
    int got = 0;
    int waited = 0;
    while (waited < timeout_ms) {
        usleep(50 * 1000);
        waited += 50;
        got += ebpf_fps_poll(ctx);
        if (got >= need) {
            ctx->locked_symbol = symbol;
            return true;
        }
    }

    /* 未命中: detach 此符号, 试下一个 */
    ioctl(perf_fd, PERF_EVENT_IOC_DISABLE, 0);
    close(perf_fd);
    ctx->perf_fd = -1;
    return false;
}

ebpf_fps_ctx *ebpf_fps_start(const char *bpf_obj_path, pid_t target_pid) {
    /* BPF 字节码文件存在性 */
    if (access(bpf_obj_path, R_OK) != 0) {
        fprintf(stderr, "[eBPF] 找不到 BPF 字节码: %s\n", bpf_obj_path);
        return NULL;
    }

    /* 找目标进程 libgui.so */
    char lib_path[256];
    if (!find_libgui_path(target_pid, lib_path, sizeof(lib_path))) {
        fprintf(stderr, "[eBPF] 进程 %d 未加载 libgui.so\n", (int)target_pid);
        return NULL;
    }

    struct ebpf_fps_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    ctx->prog_fd = ctx->map_fd = ctx->perf_fd = -1;
    ctx->target_pid = target_pid;

    /* 加载 BPF 程序 */
    if (load_bpf_object(bpf_obj_path, &ctx->prog_fd, &ctx->map_fd) < 0) {
        free(ctx);
        return NULL;
    }

    /* 逐个候选符号 attach + 验证(每个等 2 秒, 命中需 >=5 帧) */
    for (int i = 0; LIBGUI_FRAME_SYMBOLS[i]; i++) {
        if (try_symbol(ctx, lib_path, LIBGUI_FRAME_SYMBOLS[i], 2000, 5)) {
            fprintf(stderr, "[eBPF] 锁定符号: %s @ %s\n", LIBGUI_FRAME_SYMBOLS[i], lib_path);
            return ctx;
        }
    }

    fprintf(stderr, "[eBPF] 所有候选符号均无帧事件, 放弃 eBPF\n");
    ebpf_fps_stop(ctx);
    return NULL;
}

void ebpf_fps_stop(ebpf_fps_ctx *ctx) {
    if (!ctx) return;
    long page = sysconf(_SC_PAGESIZE);
    if (ctx->perf_fd >= 0) {
        ioctl(ctx->perf_fd, PERF_EVENT_IOC_DISABLE, 0);
        close(ctx->perf_fd);
    }
    if (ctx->ring_mem)  munmap(ctx->ring_mem, page + 2 * ctx->ring_data_size);
    if (ctx->ring_meta) munmap(ctx->ring_meta, page);
    if (ctx->map_fd >= 0)  close(ctx->map_fd);
    if (ctx->prog_fd >= 0) close(ctx->prog_fd);
    free(ctx);
}
