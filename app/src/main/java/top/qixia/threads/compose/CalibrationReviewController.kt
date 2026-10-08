package top.qixia.threads.compose

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.qixia.threads.CalibrationDraftStore
import top.qixia.threads.CalibrationReview
import top.qixia.threads.SupersededCalibrationDraftException

data class CalibrationReviewsState(
    val pending: List<CalibrationReview> = emptyList(), val activeId: String? = null,
    val busy: Boolean = false, val error: String? = null
) { val active get() = pending.find { it.draft.fileName == activeId } }

internal data class CalibrationReviewsRefresh(
    val state: CalibrationReviewsState,
    val toOpen: CalibrationReview?
)

/** 存储层为每个包提供一份最新结果，已打开的弹窗继续对应同一个包。 */
internal fun reconcileCalibrationReviews(
    current: CalibrationReviewsState,
    pending: List<CalibrationReview>,
    seen: Set<String>
): CalibrationReviewsRefresh {
    val previous = current.active
    val retained = pending.firstOrNull { it.draft.fileName == current.activeId }
    if (retained != null) return CalibrationReviewsRefresh(current.copy(pending = pending), null)

    val replacement = previous?.let { active ->
        pending.firstOrNull { it.draft.packageName == active.draft.packageName }
            ?.copy(original = null)
    }
    val next = replacement ?: pending.firstOrNull { it.draft.fileName !in seen }
    val refreshed = if (replacement == null) pending else pending.map {
        if (it.draft.fileName == replacement.draft.fileName) replacement else it
    }
    return CalibrationReviewsRefresh(
        current.copy(pending = refreshed, activeId = next?.draft?.fileName, error = null), next
    )
}

/** 不使用定时器，在恢复或刷新时导入结果，所有 IO 均在主线程外串行执行。 */
class CalibrationReviewController(context: Context, private val scope: CoroutineScope,
                                  private val onSaved: () -> Unit) {
    private val store = CalibrationDraftStore(context)
    private val mutex = Mutex()
    private val seen = mutableSetOf<String>()
    private val mutable = MutableStateFlow(CalibrationReviewsState())
    val state = mutable.asStateFlow()

    fun refresh() {
        if (!beginOperation()) return
        scope.launch(Dispatchers.IO) {
            val superseded = mutex.withLock {
                var retry = false
                runCatching { store.load() }.onSuccess { pending ->
                    seen.retainAll(pending.map { it.draft.fileName }.toSet())
                    val refreshed = reconcileCalibrationReviews(mutable.value, pending, seen)
                    mutable.value = refreshed.state
                    refreshed.toOpen?.let { retry = openLocked(it) }
                }.onFailure { error ->
                    mutable.update { it.copy(error = error.message ?: "待确认结果读取失败") }
                }
                mutable.update { it.copy(busy = false) }
                retry
            }
            if (superseded) refresh()
        }
    }

    fun open(id: String? = null) {
        if (!beginOperation()) return
        scope.launch(Dispatchers.IO) {
            val superseded = mutex.withLock {
                val review = mutable.value.pending.firstOrNull { id == null || it.draft.fileName == id }
                val retry = review?.let(::openLocked) ?: false
                mutable.update { it.copy(busy = false) }
                retry
            }
            if (superseded) refresh()
        }
    }

    private fun openLocked(review: CalibrationReview): Boolean {
        val key = review.draft.fileName
        seen += key
        mutable.update { state -> state.copy(activeId = key, busy = true, error = null,
            pending = state.pending.map { if (it.draft.fileName == key) review else it }) }
        var superseded = false
        runCatching { store.open(review) }.onSuccess { ready ->
            mutable.update { state -> state.copy(pending = state.pending.map { if (it.draft.fileName == key) ready else it }) }
        }.onFailure { error ->
            superseded = error is SupersededCalibrationDraftException
            mutable.update { it.copy(error = error.message ?: "读取现有配置失败，请重试") }
        }
        return superseded
    }

    fun dismiss() { mutable.update { if (it.busy) it else it.copy(activeId = null, error = null) } }

    private fun beginOperation(clearError: Boolean = false): Boolean {
        while (true) {
            val current = mutable.value
            if (current.busy) return false
            if (mutable.compareAndSet(current, current.copy(busy = true,
                    error = if (clearError) null else current.error))) return true
        }
    }

    fun select(index: Int, cpus: Set<Int>) {
        changeSelection(cpus) { current ->
            if (index !in current.selections.indices) null else current.copy(
                selections = current.selections.mapIndexed { i, old -> if (i == index) cpus else old })
        }
    }

    fun selectProcess(owner: String, cpus: Set<Int>) {
        changeSelection(cpus) { current ->
            if (owner !in current.processOwners) null else current.copy(
                processSelections = current.processSelections + (owner to cpus))
        }
    }

    private fun changeSelection(cpus: Set<Int>, change: (CalibrationReview) -> CalibrationReview?) {
        val id = mutable.value.activeId ?: return
        val current = mutable.value.active ?: return
        if (!current.draft.capacities.keys.containsAll(cpus)) return
        if (!beginOperation()) return
        scope.launch(Dispatchers.IO) {
            val superseded = mutex.withLock {
                val review = mutable.value.pending.find { it.draft.fileName == id }
                val next = review?.let(change)
                var retry = false
                if (next != null) runCatching { store.persist(next) }.onSuccess {
                    mutable.update { s -> s.copy(pending = s.pending.map { if (it.draft.fileName == id) next else it }, error = null) }
                }.onFailure { e ->
                    retry = e is SupersededCalibrationDraftException
                    mutable.update { it.copy(error = "修改未保存：${e.message}") }
                }
                mutable.update { it.copy(busy = false) }
                retry
            }
            if (superseded) refresh()
        }
    }

    fun reloadOriginal() {
        val key = mutable.value.active?.draft?.fileName ?: return
        if (!beginOperation()) return
        scope.launch(Dispatchers.IO) {
            val superseded = mutex.withLock {
                val review = mutable.value.pending.find { it.draft.fileName == key }
                val retry = review?.let { openLocked(it.copy(original = null)) } ?: false
                mutable.update { it.copy(busy = false) }
                retry
            }
            if (superseded) refresh()
        }
    }

    fun finish(discard: Boolean = false) {
        val key = mutable.value.active?.draft?.fileName ?: return
        if (!beginOperation(clearError = true)) return
        scope.launch(Dispatchers.IO) {
            var success = false
            var superseded = false
            mutex.withLock {
                val review = mutable.value.pending.find { it.draft.fileName == key }
                if (review != null) runCatching { if (discard) store.discard(review.draft) else store.save(review) }
                    .onSuccess {
                        success = true
                        mutable.update { s -> s.copy(pending = s.pending.filterNot { it.draft.fileName == key }, activeId = null) }
                    }.onFailure { e ->
                        superseded = e is SupersededCalibrationDraftException
                        mutable.update { it.copy(error = e.message ?: "保存失败，结果已保留") }
                    }
                mutable.update { it.copy(busy = false) }
            }
            if (success) scope.launch(Dispatchers.Main) { onSaved(); refresh() }
            else if (superseded) refresh()
        }
    }
}
