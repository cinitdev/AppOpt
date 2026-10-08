package top.qixia.threads.compose

import org.junit.Assert.*
import org.junit.Test

class LogStartupGroupingTest {
    private val time = 1790848136212L
    private fun event(sequence: Int, message: String, tag: String = "RS", level: String = "INFO",
        pid: Int = 6189, timestamp: Long = time + sequence) =
        "@QIXIA/1\t$timestamp\t$pid:$sequence\t$level\t$tag\t1\t$message"

    private fun startup(): List<String> = buildList {
        addAll(listOf("启动 QixiaThreads Rust 守护 v1.8.6", "作者: 一只小柒夏",
            "配置文件: /data/adb/modules/QixiaThreads/config/applist.conf",
            "包名 UID 映射: /data/adb/modules/QixiaThreads/config/state/package_uid.map",
            "检查间隔: 2 秒", "cpuset 运行组: /dev/cpuset/QiXiaRs", "目标范围: 全部配置应用",
            "Android 版本: 12 (API 31)", "设备品牌: Redmi Redmi K40",
            "内核版本: Linux 4.19.113-perf").mapIndexed { index, message -> event(index + 1, message) })
        add(event(11, "活跃线程采集 → 核心能力建议 → App 确认保存；不自动写入规则", "校准"))
        add(event(12, "平均负载达到 5% 的活跃线程参与建议，保留通配合并；不读取旧负载档位与指定核心", "校准"))
        (0..7).forEach { add(event(it + 13, "CPU $it capacity=Some(${if (it < 4) 313 else if (it < 7) 777 else 1024})", "校准")) }
        add(event(21, "QixiaThreads 版本 1.8.6"))
        add(event(22, "配置文件监控模式: inotify 事件通知 + 60 秒内容校验"))
        add(event(23, "启用守护进程验证 socket"))
        add(event(24, "启用自动校准线程"))
        add(event(25, "启用真实帧率监测线程 (多进程 eBPF / SF fallback)"))
        add(event(26, "状态更新: pkg= state=idle\\n目标离开前台或自动分配已关闭，恢复系统调度", "auto"))
        add(event(27, "守护验证 socket 已监听: @qixia_daemon_top.qixia.threads_v1", "CTRL"))
        add(event(28, "已续接上次守护进程的线程恢复基线: 581 条"))
        add(event(29, "规则加载完成: 规则=717 auto=0 应用/进程=139 基础包=115"))
        add(event(30, "包名 UID 映射: 已加载 32 个, appId快路径 31 个, 缺少映射 83 个"))
    }

    @Test fun suppliedThirtyStartupEventsBecomeOneCardWithoutLosingRawLines() {
        val raw = startup()
        val entries = LogEventParser.parse(LogSource.DAEMON, raw.joinToString("\n"))
        val card = logCards(entries).single()
        assertTrue(card.startup)
        assertEquals(30, card.entries.size)
        assertEquals(raw, card.entries.map { it.copyText })
        assertTrue(card.entries[25].message.contains("idle\n目标离开"))
        assertEquals(30, LogsUiState(entries = entries).visibleEntries.size)
    }

    @Test fun runtimeReloadAndWarningsAreNeverHiddenInStartupDetails() {
        val raw = startup().take(28) + listOf(
            event(29, "规则加载完成: 无法读取", level = "ERROR"),
            event(30, "包名 UID 映射: 部分缺失", level = "WARN"),
            event(31, "扫描计划: appId快路径=[com.game] 缺少映射=[]"),
            event(32, "规则加载完成: 规则=718 auto=0"),
            event(33, "状态更新: pkg=com.game state=active\\n已接管", "auto")
        )
        val entries = LogEventParser.parse(LogSource.DAEMON, raw.joinToString("\n"))
        val cards = logCards(entries)
        assertEquals(5, cards.size)
        assertEquals(29, cards.single { it.startup }.entries.size)
        assertEquals(2, LogsUiState(entries = entries, filter = LogFilter.ATTENTION).visibleEntries.size)
        assertTrue(logCards(LogsUiState(entries = entries, filter = LogFilter.ATTENTION).visibleEntries).none { it.startup })
    }

    @Test fun filtersApplyToIndividualEventsBeforeGroupingAndCopy() {
        val entries = LogEventParser.parse(LogSource.DAEMON, startup().joinToString("\n"))
        val filtered = LogsUiState(entries = entries, category = LogCategory.CALIBRATION, query = "CPU capacity")
        val card = logCards(filtered.visibleEntries).single()
        assertEquals(8, card.entries.size)
        assertTrue(card.entries.all { it.category == LogCategory.CALIBRATION })
        assertFalse(LogTextFormatter.readable(card.entries).contains("设备品牌"))
        assertEquals(logCards(entries).single().key, card.key)
    }

    @Test fun differentProcessesAndRunsDoNotShareAStartupCard() {
        val raw = listOf(event(1, "启动 QixiaThreads Rust 守护 v1.8.6"), event(2, "设备品牌: A", pid = 123),
            event(2, "设备品牌: B"), event(1, "启动 QixiaThreads Rust 守护 v1.8.6", pid = 9000),
            event(2, "设备品牌: C", pid = 9000))
        val cards = logCards(LogEventParser.parse(LogSource.DAEMON, raw.joinToString("\n")))
        assertEquals(3, cards.size)
        assertEquals(listOf(2, 2), cards.filter { it.startup }.map { it.entries.size })
        assertEquals(3, cards.map { it.key }.distinct().size)
    }

    @Test fun missingStartExpiredWindowAndRuntimeEventsStayIndependent() {
        val raw = listOf(event(1, "设备品牌: no start"), event(2, "启动 QixiaThreads Rust 守护 v1.8.6"),
            event(3, "运行摘要: 进程=2"), event(4, "设备品牌: too late", timestamp = time + 10003),
            event(5, "规则加载完成: 规则=1"))
        val cards = logCards(LogEventParser.parse(LogSource.DAEMON, raw.joinToString("\n")))
        assertEquals(5, cards.size)
        assertEquals(1, cards.count { it.startup })
    }
}
