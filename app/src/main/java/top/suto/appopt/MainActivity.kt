package top.suto.appopt

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import top.suto.appopt.databinding.ActivityMainBinding
import top.suto.appopt.databinding.ItemAutoAppBinding

/**
 * 引导授予悬浮窗权限, 并列出配置文件中写为 "包名=auto" 的应用。
 * 每个应用项显示图标/名称, 提供「启动」(拉起目标 App 并显示悬浮球) 与「历史」两项操作。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var hasRoot = false
    private var daemonRunning = false
    private var autoPkgs: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 启动文件监听服务 (监听 .log 变化自动导入数据库)
        startService(Intent(this, FileWatcherService::class.java))

        binding.statusSection.btnOverlay.setOnClickListener { requestOverlay() }
        binding.statusSection.btnUsage.setOnClickListener { requestUsageAccess() }
        binding.btnHistoryTop.setOnClickListener {
            startActivity(Intent(this, HistoryListActivity::class.java))
        }
        binding.btnLogTop.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.appRefresh.setOnRefreshListener {
            refreshAppList()
        }

        // root 检测 + 读配置 + 批量导入旧 .log 放后台线程, 避免 su 弹窗阻塞 UI
        thread {
            val r = DaemonBridge.hasRoot()
            val running = if (r) DaemonBridge.isDaemonRunning() else false
            val pkgs = if (r) ConfigReader.readAutoPackages() else emptyList()

            // 首次启动/旧版升级时批量导入所有 .log 到数据库(epoch 去重,只导入新数据)
            if (r && pkgs.isNotEmpty()) {
                android.util.Log.d("AppOpt", "启动时批量导入 ${pkgs.size} 个应用的 .log")
                for (pkg in pkgs) {
                    try {
                        DatabaseMigrator.migrateIfNeeded(this, pkg)
                    } catch (e: Exception) {
                        android.util.Log.e("AppOpt", "导入 $pkg 失败: ${e.message}")
                    }
                }
                android.util.Log.d("AppOpt", "批量导入完成")
            }

            runOnUiThread {
                hasRoot = r
                daemonRunning = running
                autoPkgs = pkgs
                refresh()
                buildAppList()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 守护进程可能在后台被重启/杀掉, 回到前台时后台重查一次(有 root 才有意义)
        if (hasRoot) thread {
            val running = DaemonBridge.isDaemonRunning()
            runOnUiThread {
                daemonRunning = running
                refresh()
            }
        }
    }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun refresh() {
        val overlay = hasOverlay()
        val s = binding.statusSection
        s.btnOverlay.visibility = if (overlay) View.GONE else View.VISIBLE
        s.overlayState.text = if (overlay) "已授予" else "未授予"
        setDot(s.dotOverlay, if (overlay) R.color.status_ok else R.color.status_warn)

        val usage = ForegroundDetector.hasUsageAccess(this)
        s.btnUsage.visibility = if (usage) View.GONE else View.VISIBLE
        s.usageState.text = if (usage) "已授予" else "未授予"
        setDot(s.dotUsage, if (usage) R.color.status_ok else R.color.status_warn)

        s.rootState.text = if (hasRoot) "可用" else "不可用"
        setDot(s.dotRoot, if (hasRoot) R.color.status_ok else R.color.status_off)

        // 守护进程: 无 root 时无从判断, 显示"未知"(灰); 有 root 时按 pgrep 结果显示
        when {
            !hasRoot -> {
                s.daemonState.text = "未知"
                setDot(s.dotDaemon, R.color.status_off)
            }
            daemonRunning -> {
                s.daemonState.text = "运行中"
                setDot(s.dotDaemon, R.color.status_ok)
            }
            else -> {
                s.daemonState.text = "未运行"
                setDot(s.dotDaemon, R.color.status_warn)
            }
        }
    }

    private fun setDot(dot: View, colorRes: Int) {
        dot.background?.mutate()?.setTint(ContextCompat.getColor(this, colorRes))
    }

    /** 下拉刷新: 重新读取配置文件并更新应用列表 */
    private fun refreshAppList() {
        thread {
            val pkgs = if (hasRoot) ConfigReader.readAutoPackages() else emptyList()
            runOnUiThread {
                autoPkgs = pkgs
                buildAppList()
                binding.appRefresh.isRefreshing = false
            }
        }
    }

    private fun buildAppList() {
        val a = binding.appSection
        a.appListContainer.removeAllViews()
        a.appCount.text = if (autoPkgs.isEmpty()) "" else "${autoPkgs.size} 个"
        if (autoPkgs.isEmpty()) {
            a.emptyHint.visibility = View.VISIBLE
            return
        }
        a.emptyHint.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (pkg in autoPkgs) {
            val item = ItemAutoAppBinding.inflate(inflater, a.appListContainer, false)
            val installed = isInstalled(pkg)
            item.itemName.text = appLabel(pkg)
            item.itemPkg.text = pkg
            loadIcon(pkg)?.let { item.itemIcon.setImageDrawable(it) }
                ?: item.itemIcon.setImageResource(R.drawable.ic_launcher_foreground)
            item.btnStart.isEnabled = installed
            item.btnStart.text = if (installed) "启动" else "未安装"
            item.btnStart.setOnClickListener { startAppWithBall(pkg) }
            a.appListContainer.addView(item.root)
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** 包名 -> 应用显示名; 未安装则回退为包名 */
    private fun appLabel(pkg: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    /** 加载应用图标; 未安装返回 null */
    private fun loadIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    /** 跳转系统「使用情况访问」设置页, 让用户为本应用授予 PACKAGE_USAGE_STATS */
    private fun requestUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // 部分 ROM 支持直接定位到本应用条目; 失败则回退到列表页
        try {
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                toast("请在系统设置 > 使用情况访问 中授予本应用权限")
            }
        }
    }

    /** 启动目标应用并显示悬浮球, 把目标包名传给服务用于校准 */
    private fun startAppWithBall(pkg: String) {
        if (!hasOverlay()) {
            toast("请先授予悬浮窗权限")
            refresh()
            return
        }
        // 先拉起悬浮球服务 (带目标包名)
        val svc = Intent(this, FloatingBallService::class.java)
            .putExtra(FloatingBallService.EXTRA_TARGET_PKG, pkg)
        startForegroundService(svc)

        // 再拉起目标应用
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            toast("未找到 $pkg 的启动入口, 请手动进入应用")
            moveTaskToBack(true)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
