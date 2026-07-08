#define SF_MAX_CANDS  16
#define SF_LAYER_MAX  256

/* =====================================================================
 * 直连 binder 抓 SurfaceFlinger dump (对齐 Scene 思路, 替代 fork dumpsys)
 *
 * 原理: dumpsys 本身就是通过 binder 向服务发 DUMP_TRANSACTION
 *   (code = B_PACK_CHARS('_','D','M','P')), 传入一个 fd 和参数列表
 *   (如 "--latency" "<layer>"), 服务把 dump 文本写进该 fd。这里直接
 *   走 /dev/binder ioctl 复刻该过程:
 *     1) 向 servicemanager(handle 0) 发 checkService("SurfaceFlinger")
 *        取得 SF 的 binder handle(缓存复用, 仅首次有一次 binder 往返)。
 *     2) 建管道, 把写端 fd 经 binder 传给 SF, 发 DUMP_TRANSACTION 附 dump
 *        参数; 后台读线程从读端收 dump 文本(防 SF 写满管道阻塞)。
 *   这样每个采样窗口不再 fork /system/bin/dumpsys, 开销大幅降低。
 *
 * 通杀 Android 12-16: 仅依赖 /dev/binder uapi(linux/android/binder.h),
 *   该 ABI 长期稳定; writeInterfaceToken 的 strictmode/worksource/'SYST'
 *   头在 Android 8+ 一致。不定义 BINDER_IPC_32BIT, 故 32/64 位 ABI 共用同一
 *   套结构体布局(binder_uintptr_t 均为 __u64), 全 ABI 启用。任一步失败
 *   (取不到 handle / ioctl 被 SELinux 拒 / 真 32 位内核 ABI 不匹配)即永久
 *   回退 CLI dumpsys。
 * ===================================================================== */
#ifdef APPOPT_HAVE_BINDER
#define SF_DUMP_CODE   ((uint32_t)B_PACK_CHARS('_','D','M','P'))  /* DUMP_TRANSACTION */
#define SVC_CHECK_CODE 2u                          /* IServiceManager::checkService */

static int     g_bnd_fd    = -2;     /* -2 未尝试; -1 已判定不可用; >=0 已打开 */
static void*   g_bnd_map   = NULL;
static size_t  g_bnd_mapsz = 0;
static uint32_t g_sf_handle = 0;
static bool    g_sf_have   = false;

/* 极简 Parcel 写入器(写进调用方提供的栈缓冲, 记录 binder 对象偏移) */
typedef struct { uint8_t* b; size_t pos, cap; binder_size_t off[2]; size_t noff; bool bad; } bparcel;
static void bp_init(bparcel* p, uint8_t* buf, size_t cap){ p->b=buf; p->pos=0; p->cap=cap; p->noff=0; p->bad=false; }
static void bp_raw(bparcel* p, const void* s, size_t n){ if(p->pos+n>p->cap){p->bad=true;return;} memcpy(p->b+p->pos,s,n); p->pos+=n; }
static void bp_pad(bparcel* p){ while(p->pos & 3u){ if(p->pos>=p->cap){p->bad=true;return;} p->b[p->pos++]=0; } }
static void bp_i32(bparcel* p, int32_t v){ bp_raw(p,&v,4); }
/* UTF-16 字符串(Parcel::writeString16): int32 字符数, 然后 char16 + 结尾 0, 4 字节对齐 */
static void bp_str16(bparcel* p, const char* s){
    size_t n = s ? strlen(s) : 0;
    bp_i32(p, (int32_t)n);
    for (size_t i=0;i<n;i++){ uint16_t c=(uint8_t)s[i]; bp_raw(p,&c,2); }
    uint16_t z=0; bp_raw(p,&z,2);
    bp_pad(p);
}
/* writeInterfaceToken: int32 strict_policy | (kHeader<<16) , int32 work_source(-1),
 * int32 'SYST'(header marker), 再 writeString16(interface)。匹配 Android 10+。 */
static void bp_iface(bparcel* p, const char* iface){
    bp_i32(p, (int32_t)0x9c | (int32_t)(0x53 << 16)); /* STRICT_MODE_PENALTY 占位 | header 'S' 高位 */
    bp_i32(p, -1);                                    /* work source uid = unset */
    bp_i32(p, (int32_t)B_PACK_CHARS('S','Y','S','T'));/* kHeader: header token marker */
    bp_str16(p, iface);
}
/* 写一个 binder fd 对象(flat_binder_object, type=BINDER_TYPE_FD) 到 parcel,
 * 同时登记 object 偏移。复刻 Parcel::writeFileDescriptor: 直接写对象,
 * flags=0x7f|ACCEPTS_FDS, cookie=0(不转移所有权, 我们自己关)。 */
static void bp_fd(bparcel* p, int fd){
    if (p->noff >= 2) { p->bad=true; return; }
    bp_pad(p);
    struct flat_binder_object obj; memset(&obj,0,sizeof(obj));
    obj.hdr.type = BINDER_TYPE_FD;
    obj.flags = 0;                   /* 与 libbinder writeFileDescriptor 一致: fd 对象 flags=0 */
    obj.cookie = 0;                  /* takeOwnership=false(驱动只 dup, 我们自己关) */
    obj.binder = 0;
    obj.handle = (uint32_t)fd;
    binder_size_t at = p->pos;
    bp_raw(p, &obj, sizeof(obj));
    p->off[p->noff++] = at;
}

/* 打开并 mmap /dev/binder, 完成 BINDER_VERSION 协商。成功返回 fd, 失败 -1。 */
static int bnd_open(void){
    int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    if (fd < 0) { printf("[FPS][binder] open /dev/binder 失败: %s\n", strerror(errno)); return -1; }
    struct binder_version ver; memset(&ver,0,sizeof(ver));
    if (ioctl(fd, BINDER_VERSION, &ver) < 0) {
        printf("[FPS][binder] BINDER_VERSION ioctl 失败: %s\n", strerror(errno)); close(fd); return -1;
    }
    if (ver.protocol_version != BINDER_CURRENT_PROTOCOL_VERSION) {
        printf("[FPS][binder] 协议版本 %d != %d\n", ver.protocol_version, BINDER_CURRENT_PROTOCOL_VERSION);
        close(fd); return -1;
    }
    size_t msz = 128 * 1024;        /* 只收 dump 应答头, 128KB 足够; dump 正文走管道 */
    void* m = mmap(NULL, msz, PROT_READ, MAP_PRIVATE, fd, 0);
    if (m == MAP_FAILED) { printf("[FPS][binder] mmap 失败: %s\n", strerror(errno)); close(fd); return -1; }
    uint32_t mx = 0;                /* 限制 binder 线程数为 0(只用本线程同步收发) */
    ioctl(fd, BINDER_SET_MAX_THREADS, &mx);
    g_bnd_map = m; g_bnd_mapsz = msz;
    return fd;
}

/* 执行一次同步 binder 事务(handle, code, 入参 parcel), 收集 reply 数据。
 * reply 数据写入 rbuf(返回长度), 同时通过 *txn_err 返回事务状态(0=成功)。
 * 仅解析我们需要的命令: BR_REPLY(取 reply parcel) / BR_TRANSACTION_COMPLETE /
 * BR_DEAD_REPLY / BR_FAILED_REPLY。其余命令按其负载长度跳过。
 * 返回 reply 字节数(可能 0), 失败返回 -1。 */
static ssize_t bnd_transact(int fd, uint32_t handle, uint32_t code,
                            const bparcel* in, uint8_t* rbuf, size_t rcap,
                            int* txn_err){
    struct binder_transaction_data tr; memset(&tr,0,sizeof(tr));
    tr.target.handle = handle;
    tr.code = code;
    tr.flags = 0;                                  /* 同步事务(非 oneway) */
    tr.data_size = in->pos;
    tr.offsets_size = in->noff * sizeof(binder_size_t);
    tr.data.ptr.buffer = (binder_uintptr_t)(uintptr_t)in->b;
    tr.data.ptr.offsets = (binder_uintptr_t)(uintptr_t)in->off;

    /* 写缓冲: cmd(BC_TRANSACTION) + binder_transaction_data */
    uint8_t wb[sizeof(uint32_t)+sizeof(tr)];
    uint32_t bc = BC_TRANSACTION;
    memcpy(wb, &bc, sizeof(bc));
    memcpy(wb+sizeof(bc), &tr, sizeof(tr));

    uint8_t rb[4096];
    struct binder_write_read bwr;
    bool wrote = false, got_reply = false, completed = false;
    ssize_t rlen = 0; if (txn_err) *txn_err = 0;

    for (int spin = 0; spin < 64; spin++) {
        memset(&bwr,0,sizeof(bwr));
        if (!wrote) {
            bwr.write_buffer = (binder_uintptr_t)(uintptr_t)wb;
            bwr.write_size   = sizeof(wb);
        }
        bwr.read_buffer = (binder_uintptr_t)(uintptr_t)rb;
        bwr.read_size   = sizeof(rb);
        if (ioctl(fd, BINDER_WRITE_READ, &bwr) < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (bwr.write_consumed >= sizeof(wb)) wrote = true;

        /* 解析 read 返回的命令流 */
        size_t off = 0;
        while (off + sizeof(uint32_t) <= bwr.read_consumed) {
            uint32_t cmd; memcpy(&cmd, rb+off, sizeof(cmd)); off += sizeof(cmd);
            switch (cmd) {
            case BR_TRANSACTION_COMPLETE:
                completed = true; break;
            case BR_NOOP: case BR_SPAWN_LOOPER: break;
            case BR_INCREFS: case BR_ACQUIRE:
            case BR_RELEASE: case BR_DECREFS:
                off += 2*sizeof(binder_uintptr_t); break;   /* ptr+cookie, 忽略 */
            case BR_DEAD_REPLY: case BR_FAILED_REPLY:
                if (txn_err) *txn_err = -1; got_reply = true; break;
            case BR_ERROR:
                off += sizeof(uint32_t); if (txn_err) *txn_err = -1; got_reply = true; break;
            case BR_REPLY: {
                struct binder_transaction_data rt;
                if (off + sizeof(rt) > bwr.read_consumed) { return -1; }
                memcpy(&rt, rb+off, sizeof(rt)); off += sizeof(rt);
                if (rt.flags & TF_STATUS_CODE) {
                    int32_t st=0;
                    if (rt.data_size>=4 && rt.data.ptr.buffer)   /* 同数据路径: 判 buffer 非空再读 */
                        memcpy(&st,(void*)(uintptr_t)rt.data.ptr.buffer,4);
                    if (txn_err) *txn_err = st ? st : -1;
                } else if (rbuf && rcap) {
                    size_t cp = rt.data_size < rcap ? rt.data_size : rcap;
                    if (rt.data.ptr.buffer) memcpy(rbuf,(void*)(uintptr_t)rt.data.ptr.buffer,cp);
                    rlen = (ssize_t)cp;
                }
                /* 关键: reply 里若带 binder handle 对象(如 checkService 返回的服务句柄),
                 * 其引用计数是临时挂在本 reply 缓冲上的。下面 BC_FREE_BUFFER 会让驱动把
                 * 这些临时引用一并减掉, handle 随即失效。若我们还想继续用该 handle, 必须
                 * 在释放缓冲"之前"先 BC_ACQUIRE 一个强引用把它焊住。否则后续对该 handle
                 * 的事务会被驱动回 BR_FAILED_REPLY(此前 dump 全失败的真因)。 */
                if (rt.offsets_size > 0 && rt.data.ptr.offsets && rt.data.ptr.buffer) {
                    const binder_size_t* offs = (const binder_size_t*)(uintptr_t)rt.data.ptr.offsets;
                    size_t nobj = rt.offsets_size / sizeof(binder_size_t);
                    for (size_t k = 0; k < nobj; k++) {
                        struct flat_binder_object* fo =
                            (struct flat_binder_object*)((uint8_t*)(uintptr_t)rt.data.ptr.buffer + offs[k]);
                        if (fo->hdr.type == BINDER_TYPE_HANDLE) {
                            struct { uint32_t c; uint32_t h; } __attribute__((packed)) ac;
                            ac.c = BC_ACQUIRE; ac.h = fo->handle;
                            struct binder_write_read ar; memset(&ar,0,sizeof(ar));
                            ar.write_buffer=(binder_uintptr_t)(uintptr_t)&ac; ar.write_size=sizeof(ac);
                            ioctl(fd, BINDER_WRITE_READ, &ar);
                        }
                    }
                }
                /* 归还 reply 缓冲给 binder 驱动 */
                struct { uint32_t c; binder_uintptr_t p; } __attribute__((packed)) fb;
                fb.c = BC_FREE_BUFFER; fb.p = rt.data.ptr.buffer;
                struct binder_write_read fr; memset(&fr,0,sizeof(fr));
                fr.write_buffer=(binder_uintptr_t)(uintptr_t)&fb; fr.write_size=sizeof(fb);
                ioctl(fd, BINDER_WRITE_READ, &fr);
                got_reply = true; break;
            }
            default:
                /* 未知命令: 无法安全跳过其负载, 终止 */
                return got_reply ? rlen : -1;
            }
            if (got_reply) break;
        }
        if (got_reply) break;
        (void)completed;
    }
    return got_reply ? rlen : -1;
}

/* 向 servicemanager(handle 0) 发 checkService("SurfaceFlinger"), 解析 reply
 * 里的 flat_binder_object 取得 SF 的 handle。成功置 g_sf_handle 并返回 true。 */
static bool bnd_resolve_sf(int fd){
    uint8_t ibuf[256]; bparcel in; bp_init(&in, ibuf, sizeof(ibuf));
    bp_iface(&in, "android.os.IServiceManager");
    bp_str16(&in, "SurfaceFlinger");
    if (in.bad) return false;
    uint8_t rep[256]; int terr=0;
    ssize_t rl = bnd_transact(fd, 0, SVC_CHECK_CODE, &in, rep, sizeof(rep), &terr);
    if (rl < 0 || terr != 0) {
        printf("[FPS][binder] checkService(SF) 失败: rl=%zd txn_err=%d\n", rl, terr);
        return false;
    }
    /* reply: int32 (strict header) 可选 + flat_binder_object(handle 服务) 。
     * 扫描 reply 找第一个 BINDER_TYPE_HANDLE/WEAK_HANDLE 对象取 handle。 */
    for (size_t o = 0; o + sizeof(struct flat_binder_object) <= (size_t)rl; o += 4) {
        struct flat_binder_object obj; memcpy(&obj, rep+o, sizeof(obj));
        if (obj.hdr.type == BINDER_TYPE_HANDLE || obj.hdr.type == BINDER_TYPE_WEAK_HANDLE) {
            g_sf_handle = obj.handle;
            printf("[FPS][binder] 解析到 SF handle=%u\n", g_sf_handle);
            return true;
        }
    }
    printf("[FPS][binder] checkService reply(%zd 字节)里没找到 handle 对象\n", rl);
    return false;
}

/* 后台线程: 持续从管道读端把 dump 文本读进堆缓冲, 防止 SF 写满管道阻塞。 */
struct pipe_drain { int rfd; char* buf; size_t cap, used; };
static void* pipe_drain_fn(void* a){
    struct pipe_drain* d = (struct pipe_drain*)a;
    for(;;){
        if (d->used + 1 >= d->cap) {            /* 满: 读尽丢弃 */
            char t[4096]; if (read(d->rfd, t, sizeof(t)) <= 0) break; continue;
        }
        ssize_t r = read(d->rfd, d->buf + d->used, d->cap - 1 - d->used);
        if (r > 0) d->used += (size_t)r;
        else if (r == 0) break;
        else if (errno == EINTR) continue;
        else break;
    }
    if (d->buf) d->buf[d->used] = '\0';
    return NULL;
}

/* 经 binder 向 SF 发 DUMP_TRANSACTION, args 为 dump 参数(如 {"--latency","<layer>"}),
 * dump 文本收进 buf(NUL 结尾)。成功返回字节数, 失败返回 -1(调用方回退 CLI)。 */
/* 直连 binder 调 SurfaceFlinger dump (比 CLI dumpsys 快 10 倍+, 无 fork 开销)。
 * args: dump 参数数组(如 {"--latency", "LayerName"})
 * 返回写入 buf 的字节数; 失败返回 -1。
 * 非 static: 供 fps_fallback.c 等外部模块调用。 */
ssize_t sf_dump_binder(const char* const args[], int nargs,
                       char* buf, size_t bufsz){
    if (g_bnd_fd == -1) return -1;                /* 已判定不可用 */
    if (g_bnd_fd == -2) {                          /* 首次: 打开 + 解析 SF handle */
        int fd = bnd_open();
        if (fd < 0) { g_bnd_fd = -1; return -1; }
        g_bnd_fd = fd;
        g_sf_have = bnd_resolve_sf(fd);
        if (!g_sf_have) { /* 仍保留 fd, 下次重试解析 */ }
    }
    if (!g_sf_have) {
        g_sf_have = bnd_resolve_sf(g_bnd_fd);
        if (!g_sf_have) return -1;
    }

    int pfd[2];
    if (pipe(pfd) != 0) return -1;

    /* 组 dump parcel: writeFileDescriptor(写端) + int32 argc + argc * String16 。
     * BBinder::dump(fd, args) 的 onTransact 解码顺序: 先 readFileDescriptor,
     * 再 readInt32(argc), 再 argc 个 readString16。无 interface token。 */
    uint8_t ibuf[1024]; bparcel in; bp_init(&in, ibuf, sizeof(ibuf));
    bp_fd(&in, pfd[1]);            /* 写端 fd 对象(writeFileDescriptor) */
    bp_i32(&in, nargs);
    for (int i=0;i<nargs;i++) bp_str16(&in, args[i]);
    if (in.bad) { close(pfd[0]); close(pfd[1]); return -1; }

    /* 起读线程收 dump(SF 在事务期间写管道; 我们必须并发读) */
    struct pipe_drain d = { pfd[0], buf, bufsz, 0 };
    pthread_t th;
    if (pthread_create(&th, NULL, pipe_drain_fn, &d) != 0) {
        close(pfd[0]); close(pfd[1]); return -1;
    }

    uint8_t rep[512]; int terr=0;
    ssize_t rl = bnd_transact(g_bnd_fd, g_sf_handle, SF_DUMP_CODE, &in, rep, sizeof(rep), &terr);
    close(pfd[1]);                 /* 关写端: SF 那份已 dup, 此处关闭使读端能 EOF */
    pthread_join(th, NULL);
    close(pfd[0]);

    if (rl < 0 || terr != 0) {     /* 事务真失败(可能 SELinux 拒/handle 失效) */
        static int dlog = 0;
        if (!dlog) { dlog = 1;
            printf("[FPS][binder] dump 事务失败: rl=%zd txn_err=%d 收到=%zu字节 (arg0=%s)\n",
                   rl, terr, d.used, nargs > 0 ? args[0] : "(无参)");
        }
        g_sf_have = false;          /* 下次重新解析 handle */
        return -1;                  /* 让上层回退 CLI */
    }
    /* terr==0 且 rl>=0: 事务成功。d.used 可能为 0(如 --latency-clear 本就无文本输出),
     * 这仍是"binder 成功", 不能当失败回退 CLI。返回 >=0 由上层据此判定 binder 可用。 */
    return (ssize_t)d.used;
}
#endif /* APPOPT_HAVE_BINDER */

