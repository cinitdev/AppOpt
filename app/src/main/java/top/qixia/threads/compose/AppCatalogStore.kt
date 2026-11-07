package top.qixia.threads.compose

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用 Tab 与刷新率规则共用的桌面应用目录。
 *
 * 目标：
 * - 只查询一次桌面入口，两个页面消费同一份数据，避免刷新率页每次进入都重新查；
 * - 冷启动先用上次的磁盘快照，列表立刻可见，再后台刷新；
 * - 任意页面手动刷新都会更新同一份数据，另一个页面自动跟随。
 */
data class AppCatalogState(
    val apps: List<AppItemModel> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val refreshedAtElapsedMs: Long = 0L
)

internal fun isAppCatalogCacheFresh(nowElapsedMs: Long, cachedAtElapsedMs: Long, freshnessMs: Long): Boolean =
    cachedAtElapsedMs > 0L && nowElapsedMs >= cachedAtElapsedMs && freshnessMs > 0L &&
        nowElapsedMs - cachedAtElapsedMs < freshnessMs

class AppCatalogStore(private val app: Application, private val scope: CoroutineScope) {
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    // 同一时刻只允许一次查询；失败时保留旧列表。
    private val inFlight = AtomicBoolean(false)
    private val mutable = MutableStateFlow(restore())
    val state: StateFlow<AppCatalogState> = mutable.asStateFlow()

    /** 缓存仍新鲜时直接复用；过期或只有快照时后台刷新，不阻塞调用方。 */
    fun ensureFresh() {
        val current = mutable.value
        if (current.loaded && isAppCatalogCacheFresh(
                SystemClock.elapsedRealtime(), current.refreshedAtElapsedMs, FRESH_MS
            )) return
        refresh()
    }

    /** 手动刷新（下拉刷新、应用 Tab 刷新共用）。 */
    fun refresh() {
        if (!inFlight.compareAndSet(false, true)) return
        mutable.update { it.copy(refreshing = true) }
        scope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { launchableAppCatalog(app) }
                val previous = mutable.value
                when {
                    // 查询失败或系统暂时不返回桌面入口时保留旧列表，避免列表瞬间清空。
                    apps.isEmpty() && previous.apps.isNotEmpty() ->
                        Log.w(TAG, "应用目录为空，保留上一次的列表")
                    // 从未加载成功且确实没有桌面应用时也要结束加载态，避免一直转圈。
                    apps.isEmpty() -> mutable.value = AppCatalogState(
                        apps = emptyList(),
                        loaded = true,
                        refreshing = false,
                        refreshedAtElapsedMs = SystemClock.elapsedRealtime()
                    )
                    else -> {
                        mutable.value = AppCatalogState(
                            apps = apps,
                            loaded = true,
                            refreshing = false,
                            refreshedAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                        persist(apps)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "应用目录读取失败，保留上一次的列表", error)
            } finally {
                mutable.update { it.copy(refreshing = false) }
                inFlight.set(false)
            }
        }
    }

    private fun persist(apps: List<AppItemModel>) {
        runCatching {
            val array = JSONArray()
            apps.forEach { item ->
                array.put(JSONObject().put(KEY_PACKAGE, item.packageName).put(KEY_LABEL, item.label))
            }
            preferences.edit()
                .putString(KEY_SNAPSHOT, JSONObject()
                    .put(KEY_VERSION, SNAPSHOT_VERSION)
                    .put(KEY_APPS, array)
                    .toString())
                .apply()
        }.onFailure { Log.w(TAG, "应用目录快照写入失败", it) }
    }

    private fun restore(): AppCatalogState {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return AppCatalogState()
        return runCatching {
            val payload = JSONObject(raw)
            val apps = if (payload.optInt(KEY_VERSION) == SNAPSHOT_VERSION) {
                payload.optJSONArray(KEY_APPS)?.let(::decodeApps).orEmpty()
            } else emptyList()
            if (apps.isEmpty()) AppCatalogState()
            // 快照只用于立刻展示，时间戳保持 0 表示必须后台刷新一次。
            else AppCatalogState(apps = apps, loaded = true, refreshing = false, refreshedAtElapsedMs = 0L)
        }.getOrElse {
            Log.w(TAG, "应用目录快照读取失败，按无缓存处理", it)
            AppCatalogState()
        }
    }

    private fun decodeApps(array: JSONArray): List<AppItemModel> {
        val apps = ArrayList<AppItemModel>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val pkg = item.optString(KEY_PACKAGE).takeIf { it.isNotBlank() } ?: continue
            val label = item.optString(KEY_LABEL).ifBlank { pkg }
            apps += AppItemModel(packageName = pkg, label = label, installed = true, icon = null)
        }
        return apps
    }

    private companion object {
        const val TAG = "QixiaAppCatalog"
        const val PREFERENCES = "app_catalog"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_VERSION = "version"
        const val KEY_APPS = "apps"
        const val KEY_PACKAGE = "package"
        const val KEY_LABEL = "label"
        const val SNAPSHOT_VERSION = 1
        /** 进程内缓存 5 分钟；手动刷新和页面刷新不受此限制。 */
        const val FRESH_MS = 5 * 60 * 1000L
    }
}
