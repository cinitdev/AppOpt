package top.qixia.threads

import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import top.qixia.threads.databinding.DialogModuleUpdateBinding

object ModuleUpdateDialog {
    fun activeUpdate(activity: AppCompatActivity): ModuleUpdater.UpdateInfo? =
        ViewModelProvider(activity)[ModuleUpdateDownloadViewModel::class.java].activeUpdate()

    fun show(
        activity: AppCompatActivity,
        update: ModuleUpdater.UpdateInfo,
        onDismiss: (() -> Unit)? = null
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
        ) return false
        val viewModel = ViewModelProvider(activity)[ModuleUpdateDownloadViewModel::class.java]
        if (!viewModel.attachDialog()) return false

        val view = DialogModuleUpdateBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        val handler = Handler(Looper.getMainLooper())
        var handoffScheduled = false
        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                viewModel.detachDialog()
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)

        view.updateVersionSummary.text = "模块更新需要下载并刷入，重启后生效"
        view.updateCurrentVersion.text = "${update.localVersion} (${update.localVersionCode})"
        view.updateLatestVersion.text = "${update.remoteVersion} (${update.remoteVersionCode})"
        view.updateChangelog.apply {
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 13f
            setLineSpacing(3f, 1.0f)
            ModuleUpdateMarkdown.create(activity).setMarkdown(this, update.changelogText)
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
        }

        fun launchInstaller(state: ModuleUpdateDownloadViewModel.State) {
            if (handoffScheduled || state.stage != ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL ||
                !state.installAuthorized
            ) {
                return
            }
            val zipPath = state.zipPath ?: return
            val targetUpdate = state.update ?: return
            handoffScheduled = true
            handler.postDelayed({
                handoffScheduled = false
                if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) {
                    return@postDelayed
                }
                if (!viewModel.claimForInstall(zipPath)) return@postDelayed
                try {
                    activity.startActivity(UpdateInstallActivity.intent(activity, targetUpdate, zipPath))
                    dialog.dismiss()
                } catch (_: Exception) {
                    viewModel.handoffFailed(
                        activity.applicationContext,
                        "打开刷入页面失败，请手动刷入"
                    )
                }
            }, INSTALL_HANDOFF_DELAY_MS)
        }

        fun render(state: ModuleUpdateDownloadViewModel.State) {
            val belongsToDialog = state.update?.let {
                it.remoteVersionCode == update.remoteVersionCode && it.zipUrl == update.zipUrl
            } == true
            if (!belongsToDialog && state.stage != ModuleUpdateDownloadViewModel.Stage.IDLE) return

            view.updateInstallStatus.visibility =
                if (state.status.isBlank()) View.GONE else View.VISIBLE
            view.updateInstallStatus.text = state.status
            view.updateProgress.visibility = when (state.stage) {
                ModuleUpdateDownloadViewModel.Stage.RESUME_READY,
                ModuleUpdateDownloadViewModel.Stage.DOWNLOADING,
                ModuleUpdateDownloadViewModel.Stage.DETECTING_MANAGER,
                ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL,
                ModuleUpdateDownloadViewModel.Stage.RETAINING_MANUAL -> View.VISIBLE
                else -> View.GONE
            }
            state.percent?.let {
                view.updateProgress.isIndeterminate = false
                view.updateProgress.progress = it.coerceIn(0, 100)
            } ?: run {
                view.updateProgress.isIndeterminate =
                    state.stage == ModuleUpdateDownloadViewModel.Stage.DOWNLOADING
            }

            val busy = state.stage in setOf(
                ModuleUpdateDownloadViewModel.Stage.DOWNLOADING,
                ModuleUpdateDownloadViewModel.Stage.DETECTING_MANAGER,
                ModuleUpdateDownloadViewModel.Stage.RETAINING_MANUAL
            ) || (state.stage == ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL &&
                state.installAuthorized)
            view.updateLater.isEnabled = !busy
            view.updateInstall.isEnabled = state.stage in setOf(
                ModuleUpdateDownloadViewModel.Stage.IDLE,
                ModuleUpdateDownloadViewModel.Stage.RESUME_READY,
                ModuleUpdateDownloadViewModel.Stage.FAILED,
                ModuleUpdateDownloadViewModel.Stage.MANUAL_READY,
                ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL
            ) && !(state.stage == ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL &&
                state.installAuthorized)
            view.updateInstall.text = when (state.stage) {
                ModuleUpdateDownloadViewModel.Stage.IDLE -> "下载并刷入"
                ModuleUpdateDownloadViewModel.Stage.RESUME_READY -> "继续下载"
                ModuleUpdateDownloadViewModel.Stage.DOWNLOADING -> "下载中"
                ModuleUpdateDownloadViewModel.Stage.DETECTING_MANAGER -> "正在检测"
                ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL ->
                    if (state.installAuthorized) "准备刷入" else "确认刷入"
                ModuleUpdateDownloadViewModel.Stage.RETAINING_MANUAL -> "正在保存"
                ModuleUpdateDownloadViewModel.Stage.MANUAL_READY,
                ModuleUpdateDownloadViewModel.Stage.FAILED -> "重试"
                ModuleUpdateDownloadViewModel.Stage.HANDED_OFF -> "已交接"
            }
            launchInstaller(state)
        }

        val observer = Observer<ModuleUpdateDownloadViewModel.State>(::render)
        view.updateProgress.progress = 0
        view.updateLater.setOnClickListener {
            viewModel.cancelSession(discardArtifact = true)
            dialog.dismiss()
        }
        view.updateInstall.setOnClickListener {
            when (viewModel.state.value?.stage) {
                ModuleUpdateDownloadViewModel.Stage.FAILED,
                ModuleUpdateDownloadViewModel.Stage.MANUAL_READY ->
                    viewModel.retry(activity.applicationContext)
                ModuleUpdateDownloadViewModel.Stage.RESUME_READY ->
                    viewModel.start(activity.applicationContext, update)
                ModuleUpdateDownloadViewModel.Stage.READY_TO_INSTALL -> {
                    viewModel.authorizeInstall(activity.applicationContext)
                    viewModel.state.value?.let(::launchInstaller)
                }
                ModuleUpdateDownloadViewModel.Stage.IDLE, null ->
                    viewModel.start(activity.applicationContext, update)
                else -> Unit
            }
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(view.root)
        dialog.setOnShowListener {
            dialog.behavior.apply {
                state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isHideable = false
            }
            configureSheetWidth(activity, dialog)
        }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            viewModel.state.removeObserver(observer)
            viewModel.detachDialog()
            activity.lifecycle.removeObserver(lifecycleObserver)
            onDismiss?.invoke()
        }
        return try {
            dialog.show()
            viewModel.state.observe(activity, observer)
            viewModel.resumeAuthorizedSession(activity.applicationContext)
            true
        } catch (_: RuntimeException) {
            handler.removeCallbacksAndMessages(null)
            viewModel.state.removeObserver(observer)
            activity.lifecycle.removeObserver(lifecycleObserver)
            viewModel.detachDialog()
            // show() 失败时不会触发 BottomSheet 的 dismiss 回调，主动释放调用方的忙碌状态。
            onDismiss?.invoke()
            false
        }
    }

    private fun configureSheetWidth(activity: AppCompatActivity, dialog: BottomSheetDialog) {
        val sheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val availableWidth = activity.resources.displayMetrics.widthPixels
        val density = activity.resources.displayMetrics.density
        val maxWidth = (560f * density + 0.5f).toInt()
        val margin = (32f * density + 0.5f).toInt()
        val params = sheet.layoutParams
        params.width = if (activity.resources.configuration.smallestScreenWidthDp >= 600) {
            minOf(maxWidth, (availableWidth - margin).coerceAtLeast(1))
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (params is CoordinatorLayout.LayoutParams) {
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        sheet.layoutParams = params
    }

    private const val INSTALL_HANDOFF_DELAY_MS = 700L
}
