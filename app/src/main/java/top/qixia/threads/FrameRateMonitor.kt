package top.qixia.threads

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.FileObserver
import android.os.SystemClock
import android.view.WindowManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

/**
 * 真实帧率(FPS)接收器。
 *
 * 优先使用 Android 本地 socket 接收 Rust 守护进程推送的 FPS 文本行, 失败时保留旧的
 * fps 文件 + FileObserver 方案作为兜底。这样 socket 被 SELinux/ROM 行为拦住时,
 * 悬浮球仍能继续显示文件通道输出。
 */
class FrameRateMonitor(
    private val context: Context,
    private val onFps: (Float) -> Unit
) {

    private val fpsFile = File(context.filesDir, FPS_FILENAME)
    private val frameReport = HistoryFrameReport()
    internal fun takeFrameMaximum() = frameReport.take(SystemClock.elapsedRealtime())
    private val socketLock = Any()
    private var observer: FileObserver? = null
    private var server: LocalServerSocket? = null
    private var activeSocket: LocalSocket? = null
    private var refreshRateCap = 0f
    private var refreshRateReadAt = 0L
    @Volatile private var running = false

    @Volatile var socketName: String? = null
        private set
    @Volatile var socketToken: String? = null
        private set

    companion object {
        const val FPS_FILENAME = "fps"
        private const val SOCKET_HELLO_TIMEOUT_MS = 3_000
        // 客户端通过 hello 后也必须持续发送数据；失联连接不能永久占住唯一接收通道。
        private const val SOCKET_IDLE_TIMEOUT_MS = 15_000
        private const val SOCKET_MAX_LINE_LENGTH = 64
    }

    fun start() {
        if (running) return
        running = true

        startFileFallback()
        startSocketServer()
    }

    fun stop() {
        running = false
        observer?.stopWatching()
        observer = null
        closeSocketChannel()
    }

    private fun startFileFallback() {
        try {
            if (!fpsFile.exists()) fpsFile.createNewFile()
        } catch (_: Exception) {
            // 创建失败不致命, 守护进程能写文件时 FileObserver 仍可兜底。
        }

        observer = buildObserver().also { it.startWatching() }
    }

    private fun startSocketServer() {
        val name = "qixia_fps_${android.os.Process.myPid()}_${System.nanoTime()}"
        val token = UUID.randomUUID().toString().replace("-", "")
        try {
            val localServer = LocalServerSocket(name)
            synchronized(socketLock) {
                server = localServer
                socketName = name
                socketToken = token
            }
            Thread({ socketAcceptLoop(localServer, token) }, "QixiaThreadsFpsSock").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            socketName = null
            socketToken = null
            server = null
        }
    }

    private fun socketAcceptLoop(localServer: LocalServerSocket, token: String) {
        while (running) {
            val socket = try {
                localServer.accept()
            } catch (_: Exception) {
                break
            }

            val accepted = synchronized(socketLock) {
                if (!running || server !== localServer) {
                    false
                } else {
                    try {
                        activeSocket?.close()
                    } catch (_: Exception) {
                    }
                    activeSocket = socket
                    true
                }
            }
            if (!accepted) {
                try { socket.close() } catch (_: Exception) {}
                break
            }
            readSocket(socket, token)
        }
    }

    private fun readSocket(socket: LocalSocket, token: String) {
        try {
            // 无效客户端不能用一个不发送 hello 的连接永久占住接收线程。
            socket.soTimeout = SOCKET_HELLO_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val hello = reader.readLine() ?: return
            if (hello != "hello $token") return
            socket.soTimeout = SOCKET_IDLE_TIMEOUT_MS

            while (running) {
                val line = reader.readLine() ?: break
                if (line.length > SOCKET_MAX_LINE_LENGTH) continue
                if (frameReport.accept(line, SystemClock.elapsedRealtime())) continue
                val fps = line.trim().toFloatOrNull() ?: continue
                emitFps(fps)
            }
        } catch (_: Exception) {
        } finally {
            synchronized(socketLock) {
                if (activeSocket === socket) activeSocket = null
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun closeSocketChannel() {
        synchronized(socketLock) {
            socketName = null
            socketToken = null
            try { activeSocket?.close() } catch (_: Exception) {}
            activeSocket = null
            try { server?.close() } catch (_: Exception) {}
            server = null
        }
    }

    /**
     * 构造监听 fps 文件 CLOSE_WRITE 的 FileObserver。
     * Android 10(API29)+ 推荐用 File 构造器(老的 String 构造器已废弃)。
     */
    private fun buildObserver(): FileObserver {
        return object : FileObserver(fpsFile, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (!running) return
                if (event and CLOSE_WRITE != 0) readAndEmitFile()
            }
        }
    }

    /** 读取 fps 文件内容并回调; 内容非法或读不到则忽略。 */
    private fun readAndEmitFile() {
        val fps = try {
            val text = fpsFile.readText().trim()
            if (text.isEmpty()) return
            text.toFloatOrNull() ?: return
        } catch (_: Exception) {
            return
        }
        emitFps(fps)
    }

    private fun emitFps(fps: Float) {
        if (fps < 0f || !running) return
        onFps(clampToRefreshRate(fps))
    }

    private fun clampToRefreshRate(fps: Float): Float {
        if (fps <= 0f) return fps
        val cap = currentRefreshRateCap()
        if (cap <= 0f) return fps
        return fps.coerceAtMost(cap)
    }

    @Suppress("DEPRECATION")
    private fun currentRefreshRateCap(): Float {
        val now = SystemClock.elapsedRealtime()
        if (refreshRateCap > 0f && now - refreshRateReadAt < 5_000L) {
            return refreshRateCap
        }

        val rate = try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.defaultDisplay?.refreshRate ?: 0f
        } catch (_: Exception) {
            0f
        }

        refreshRateCap = if (rate in 30f..1000f) rate + 0.5f else 0f
        refreshRateReadAt = now
        return refreshRateCap
    }
}
