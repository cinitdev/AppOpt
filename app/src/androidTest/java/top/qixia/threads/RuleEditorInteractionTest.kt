package top.qixia.threads

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.qixia.threads.compose.AppItemModel
import top.qixia.threads.compose.RuleEditorUiState
import top.qixia.threads.compose.theme.QixiaThreadsTheme
import top.qixia.threads.compose.ui.RuleEditorScreen

/** 所有回调仅写入内存状态，不执行 Root 命令或修改用户配置。 */
class RuleEditorInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val pkg = "com.test.game"
    private val lines = listOf("$pkg{RenderThread}=7", "$pkg:worker{Decode}=4-6", "$pkg=0-3")
    private var state by mutableStateOf(RuleEditorUiState(AppItemModel(pkg, "测试应用", true, null),
        lines, lines.joinToString("\n"), (0..7).toSet()))
    private var dismissed = false
    private var saves = 0

    private fun show() {
        compose.setContent { QixiaThreadsTheme {
            if (!dismissed) RuleEditorScreen(state, { state = state.copy(draft = it) }, { dismissed = true }, { saves++ }, {})
        } }
        compose.waitForIdle()
    }

    @Test fun editingSearchResultOnlyChangesItsOriginalRuleAndWaitsForSave() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("Decode")
        compose.onNode(hasText("Decode") and hasAnyAncestor(hasTestTag("rule-list"))).performClick()
        compose.onNodeWithTag("rule-core-range").performScrollTo().performTextReplacement("7")
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("保存更改").assertIsDisplayed()
        compose.runOnIdle {
            val rules = RuleSyntax.parse(state.draft).rules
            assertEquals("7", rules[1].cpus)
            assertEquals("$pkg:worker", rules[1].owner)
            assertEquals(lines[0], rules[0].canonicalLine)
            assertEquals(lines[2], rules[2].canonicalLine)
            assertEquals(0, saves)
        }
        compose.onNodeWithText("保存更改").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun cancelCloseKeepsTheDirtySheetVisible() {
        show()
        compose.onNodeWithText("RenderThread").performClick()
        compose.onNodeWithTag("rule-core-range").performScrollTo().performTextReplacement("6")
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("保存更改").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(state.dirty)
            assertEquals("6", RuleSyntax.parse(state.draft).rules.first().cpus)
        }
        compose.onNodeWithContentDescription("关闭规则编辑").assertIsDisplayed().performClick()
        compose.onNodeWithText("放弃未保存修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("保存更改").assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed); assertTrue(state.dirty); assertEquals(0, saves) }
    }

    @Test fun duplicateTargetIsRejectedWithoutChangingTheDraft() {
        show()
        compose.onNodeWithText("新增规则").performClick()
        compose.onNode(hasSetTextAction() and hasText("线程名称")).performScrollTo().performTextInput("RenderThread")
        compose.onNodeWithTag("rule-core-range").performScrollTo().performTextReplacement("7")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("该进程或线程已存在规则，请编辑原有规则").assertIsDisplayed()
        compose.runOnIdle { assertEquals(lines.joinToString("\n"), state.draft); assertEquals(0, saves) }
    }

    @Test fun textModeDoesNotDiscardUnrecognizedLines() {
        show()
        compose.onNodeWithContentDescription("规则工具").performClick()
        compose.onNodeWithText("文本编辑").performClick()
        val invalid = lines.joinToString("\n") + "\nunfinished{"
        compose.onNode(hasSetTextAction()).performTextReplacement(invalid)
        compose.onNodeWithContentDescription("规则工具").performClick()
        compose.onNodeWithText("图形编辑").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(invalid, state.draft); assertEquals(0, saves) }
    }
}
