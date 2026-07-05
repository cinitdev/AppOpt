package top.suto.appopt

import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlin.concurrent.thread
import top.suto.appopt.databinding.DialogModuleUpdateBinding

object ModuleUpdateDialog {
    fun show(
        activity: AppCompatActivity,
        update: ModuleUpdater.UpdateInfo,
        onDismiss: (() -> Unit)? = null
    ) {
        val view = DialogModuleUpdateBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        val handler = Handler(Looper.getMainLooper())

        fun inactive(): Boolean {
            return activity.isFinishing || activity.isDestroyed || !dialog.isShowing
        }

        var downloading = false
        var downloadHandle: ModuleUpdater.DownloadHandle? = null
        var downloadedZipPath: String? = null
        var artifactHandedOff = false

        fun cancelUnhandedDownload() {
            if (!downloading || artifactHandedOff) return
            downloadHandle?.cancel()
            downloadedZipPath?.let(ModuleUpdater::discardDownloadedModule)
            downloading = false
        }

        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                cancelUnhandedDownload()
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)

        fun retainForManualInstall(zipPath: String, message: String) {
            artifactHandedOff = true
            view.updateInstallStatus.text = "正在保存模块 zip 到 Download"
            thread {
                val manualPath = ModuleUpdater.retainDownloadedModuleForManualInstall(activity, zipPath)
                activity.runOnUiThread {
                    if (inactive()) return@runOnUiThread
                    downloading = false
                    view.updateLater.isEnabled = true
                    view.updateInstall.isEnabled = true
                    view.updateInstall.text = "重试"
                    view.updateInstallStatus.text = "$message\n模块已保存到：$manualPath"
                    AppToast.show(activity, message)
                }
            }
        }

        view.updateVersionSummary.text = "模块更新需要下载并刷入，重启后生效"
        view.updateCurrentVersion.text = "${update.localVersion} (${update.localVersionCode})"
        view.updateLatestVersion.text = "${update.remoteVersion} (${update.remoteVersionCode})"
        view.updateChangelog.apply {
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 13f
            setLineSpacing(3f, 1.0f)
            markdownRenderer(activity).setMarkdown(this, update.changelogText)
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
        }

        view.updateProgress.progress = 0
        view.updateLater.setOnClickListener { dialog.dismiss() }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        view.updateInstall.setOnClickListener {
            if (downloading) return@setOnClickListener
            downloading = true
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            view.updateLater.isEnabled = false
            view.updateInstall.isEnabled = false
            view.updateInstall.text = "下载中"
            view.updateInstallStatus.visibility = View.VISIBLE
            view.updateInstallStatus.text = "准备下载模块"
            view.updateProgress.visibility = View.VISIBLE
            view.updateProgress.isIndeterminate = false
            view.updateProgress.progress = 0

            downloadHandle = ModuleUpdater.downloadModule(
                activity,
                update,
                object : ModuleUpdater.DownloadCallback {
                override fun onProgress(message: String, percent: Int?) {
                    if (inactive()) return
                    view.updateInstallStatus.text = if (percent != null) {
                        "$message（$percent%）"
                    } else {
                        message
                    }
                    if (percent != null) {
                        view.updateProgress.isIndeterminate = false
                        view.updateProgress.progress = percent
                    }
                }

                override fun onSuccess(zipPath: String) {
                    if (inactive()) return
                    downloadedZipPath = zipPath
                    view.updateInstallStatus.text = "下载完成，准备刷入模块"
                    view.updateProgress.isIndeterminate = false
                    view.updateProgress.progress = 100
                    view.updateInstall.text = "准备刷入"
                    handler.postDelayed({
                        if (inactive()) return@postDelayed
                        view.updateInstallStatus.text = "正在检测模块管理器"
                        val detectStartedAt = System.currentTimeMillis()
                        thread {
                            val managerLabel = ModuleUpdater.detectRootManagerLabel()
                            activity.runOnUiThread {
                                if (inactive()) return@runOnUiThread
                                val detectRemain = (1500L - (System.currentTimeMillis() - detectStartedAt))
                                    .coerceAtLeast(0L)
                                handler.postDelayed({
                                    if (inactive()) return@postDelayed
                                    if (managerLabel == null) {
                                        retainForManualInstall(
                                            zipPath,
                                            "没有检测到可用的模块管理器，请手动刷入"
                                        )
                                        return@postDelayed
                                    }
                                    view.updateInstallStatus.text = "检测到 $managerLabel，准备刷入模块"
                                    handler.postDelayed({
                                        if (inactive()) return@postDelayed
                                        try {
                                            artifactHandedOff = true
                                            activity.startActivity(UpdateInstallActivity.intent(activity, update, zipPath))
                                            dialog.dismiss()
                                        } catch (_: Exception) {
                                            artifactHandedOff = false
                                            retainForManualInstall(
                                                zipPath,
                                                "打开刷入页面失败，请手动刷入"
                                            )
                                        }
                                    }, 1500L)
                                }, detectRemain)
                            }
                        }
                    }, 1500L)
                }

                override fun onFailure(message: String, recoverableZipPath: String?) {
                    if (inactive()) return
                    if (recoverableZipPath != null) {
                        downloadedZipPath = recoverableZipPath
                        retainForManualInstall(recoverableZipPath, message)
                        return
                    }
                    downloading = false
                    downloadHandle = null
                    view.updateLater.isEnabled = true
                    view.updateInstall.isEnabled = true
                    view.updateInstall.text = "重试"
                    view.updateProgress.isIndeterminate = false
                    view.updateInstallStatus.visibility = View.VISIBLE
                    view.updateInstallStatus.text = message
                    AppToast.show(activity, message)
                }
                }
            )
        }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            cancelUnhandedDownload()
            activity.lifecycle.removeObserver(lifecycleObserver)
            onDismiss?.invoke()
        }
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.isHideable = false
        }
        dialog.show()
    }

    private fun markdownRenderer(activity: AppCompatActivity): Markwon {
        return Markwon.builder(activity)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(activity))
            .usePlugin(TaskListPlugin.create(activity))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
}
