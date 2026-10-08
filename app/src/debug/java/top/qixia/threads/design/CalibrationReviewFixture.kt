package top.qixia.threads.design

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import top.qixia.threads.CalibrationDraft
import top.qixia.threads.CalibrationReview
import top.qixia.threads.CalibrationThread
import top.qixia.threads.compose.CalibrationReviewsState
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.CalibrationReviewSheet

/** 仅在内存中确认建议，保存和放弃操作均不写设备配置。 */
@Composable
internal fun CalibrationReviewFixture() {
    var review by remember { mutableStateOf(CalibrationReview(
        draft = CalibrationDraft("1-1", "visual.fixture", 1, 60000,
            mapOf(0 to 313L, 1 to 313L, 2 to 777L, 3 to 1024L),
            listOf(CalibrationThread("visual.fixture", "LightWorker", 1.0, 2.0, 100.0, setOf(0),
                "按线程实际负载与核心能力推荐", generatedPattern = true)), "", version = 2),
        original = emptyList())) }
    QixiaThreadsTheme {
        Surface {
            CalibrationReviewSheet(CalibrationReviewsState(pending = listOf(review), activeId = review.draft.fileName), {},
                { index, cpus -> review = review.copy(selections = review.selections.mapIndexed { i, old -> if (i == index) cpus else old }) },
                {}, {}, {}, { owner, cpus -> review = review.copy(processSelections = review.processSelections + (owner to cpus)) })
        }
    }
}
