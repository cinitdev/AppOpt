package top.qixia.threads

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** 交由系统合并调度的低频清理；不常驻、不需要 Root，也不唤醒屏幕。 */
class HistoryCleanupService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        task = scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching { HistoryRetention.pruneLocal(applicationContext) }.isSuccess }
            jobFinished(params, !success)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 0x7157
        fun schedule(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                if (scheduler.getPendingJob(JOB_ID) != null) return
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, HistoryCleanupService::class.java))
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true).build())
            }
        }
    }
}
