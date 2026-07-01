package top.suto.appopt

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
 * 优先使用 Android 本地 socket 接收 C 守护进程推送的 FPS 文本行, 失败时保留旧的
 * fps 文件 + FileObserver 方案作为兜底。这样 socket 被 SELinux/ROM 行为拦住时,
 * 悬浮球仍能继续显示文件通道输出。
 */
class FrameRateMonitor(
    private val context: Context,
    private val onFps: (Float) -> Unit
) {

    private val fpsFile = File(context.filesDir, FPS_FILENAME)
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
    }

    fun start() {
        if (running) return
        running = true

        startFileFallback()
        startSocketServer()

        // 启动时先读一次当前值(可能守护进程已经写过), 避免胶囊一直空白。
        readAndEmitFile()
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
        val name = "appopt_fps_${android.os.Process.myPid()}_${System.nanoTime()}"
        val token = UUID.randomUUID().toString().replace("-", "")
        try {
            val localServer = LocalServerSocket(name)
            server = localServer
            socketName = name
            socketToken = token
            Thread({ socketAcceptLoop(localServer, token) }, "AppOptFpsSock").apply {
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

            if (!running) {
                try { socket.close() } catch (_: Exception) {}
                break
            }

            try {
                activeSocket?.close()
            } catch (_: Exception) {
            }
            activeSocket = socket
            readSocket(socket, token)
        }
    }

    private fun readSocket(socket: LocalSocket, token: String) {
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val hello = reader.readLine() ?: return
            if (hello != "hello $token") return

            while (running) {
                val line = reader.readLine() ?: break
                val fps = line.trim().toFloatOrNull() ?: continue
                emitFps(fps)
            }
        } catch (_: Exception) {
        } finally {
            if (activeSocket === socket) activeSocket = null
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun closeSocketChannel() {
        socketName = null
        socketToken = null
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        try { server?.close() } catch (_: Exception) {}
        server = null
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
