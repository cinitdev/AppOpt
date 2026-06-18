package top.suto.appopt

import android.content.Context
import android.os.FileObserver
import java.io.File

/**
 * 真实帧率(FPS)接收器 —— 配合 C 守护进程的 perfetto frametimeline 方案。
 *
 * 数据流:
 *   守护进程(root) 跑 perfetto 抓 surfaceflinger.frametimeline, 手写解析数出
 *   目标游戏每秒的真实渲染帧数, 覆盖写到本 app 私有目录的 fps 文件
 *   (/data/data/top.suto.appopt/files/fps), 并 chcon 成 app 可读。
 *   本类用 FileObserver 监听该文件的 CLOSE_WRITE 事件, 守护每写一次即回调一次,
 *   实时、省电(无轮询)。
 *
 * 与旧的屏幕刷新率方案的区别: 这里是**应用真实提交的渲染帧率**(掉帧会真实反映),
 * 不是面板刷新档位。读不到/未启动监测时回调不会触发, 胶囊保持上一次的值。
 *
 * 回调线程: FileObserver 的回调在其内部线程触发, 调用方需自行切回主线程刷新 UI。
 */
class FrameRateMonitor(
    private val context: Context,
    private val onFps: (Float) -> Unit
) {

    private val fpsFile = File(context.filesDir, FPS_FILENAME)
    private var observer: FileObserver? = null
    @Volatile private var running = false

    companion object {
        const val FPS_FILENAME = "fps"
    }

    fun start() {
        if (running) return
        running = true

        // 确保文件存在, 否则 FileObserver 在文件被首次创建前监听不到。
        // 守护进程用 O_TRUNC 覆盖写, 不删除重建, 故这里先建一个空文件占位。
        try {
            if (!fpsFile.exists()) fpsFile.createNewFile()
        } catch (_: Exception) {
            // 创建失败(极少见)不致命, 守护进程创建该文件后 CLOSE_WRITE 仍会触发
        }

        observer = buildObserver().also { it.startWatching() }

        // 启动时先读一次当前值(可能守护进程已经写过), 避免胶囊一直空白
        readAndEmit()
    }

    fun stop() {
        running = false
        observer?.stopWatching()
        observer = null
    }

    /**
     * 构造监听 fps 文件 CLOSE_WRITE 的 FileObserver。
     * Android 10(API29)+ 推荐用 File 构造器(老的 String 构造器已废弃)。
     * 监听文件本身而非目录: 守护进程是覆盖写(open+write+close), CLOSE_WRITE 必触发。
     */
    private fun buildObserver(): FileObserver {
        return object : FileObserver(fpsFile, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (!running) return
                if (event and CLOSE_WRITE != 0) readAndEmit()
            }
        }
    }

    /** 读取 fps 文件内容并回调; 内容非法或读不到则忽略 */
    private fun readAndEmit() {
        val fps = try {
            val text = fpsFile.readText().trim()
            if (text.isEmpty()) return
            text.toFloatOrNull() ?: return
        } catch (_: Exception) {
            return
        }
        if (fps >= 0f && running) onFps(fps)
    }
}
