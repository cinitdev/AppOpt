package top.qixia.threads

import org.junit.Assert.*
import org.junit.Test

class RuleFormatPreservationTest {
    private val formats = listOf(
        CalibPolicy.RuleOutputFormat.LEGACY,
        CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK,
        CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK,
        CalibPolicy.RuleOutputFormat.TAGGED_BLOCK,
        CalibPolicy.RuleOutputFormat.NATURAL_BLOCK,
        CalibPolicy.RuleOutputFormat.NESTED_BLOCK,
        CalibPolicy.RuleOutputFormat.FUNCTION_BLOCK,
        CalibPolicy.RuleOutputFormat.YAML
    )

    private val rules = """
        com.ruleformat.demo=0-7
        com.ruleformat.demo{Binder:*}=7
        com.ruleformat.demo{Binder:specific}=5
        com.ruleformat.demo{Binder:*}=7
        com.ruleformat.demo{Thread-[0-9]*}=0-3
        com.ruleformat.demo{Thread-?}=4-6
        com.ruleformat.demo{*Main}=6
        com.ruleformat.demo=0-3
        com.ruleformat.demo=0-7
        com.ruleformat.demo:worker=0-3
        com.ruleformat.demo:worker=4-6
        com.ruleformat.demo:worker{thread-shared-*}=5
        com.ruleformat.demo:worker{Binder:*}=4
        com.ruleformat.demo:worker{thread-shared-*}=5
        com.ruleformat.demo:worker{Worker-?}=6
        com.ruleformat.other{RenderThread}=7
    """.trimIndent() + "\n"

    @Test fun allEightFormatsPreserveDuplicateRulesWildcardOrderAndProcessFallbacks() {
        val original = semantics(rules)
        assertEquals(16, original.values.sumOf { it.size })
        assertEquals(3, original.getValue("com.ruleformat.demo" to true).size)
        assertEquals(2, original.getValue("com.ruleformat.demo:worker" to true).size)
        for (format in formats) {
            val first = convert(rules, format)
            assertEquals("$format lost or reordered a rule", original, semantics(first.content))
            val second = convert(first.content, format)
            assertFalse("$format must be idempotent", second.changed)
            assertEquals(first.content, second.content)
            val legacy = convert(first.content, CalibPolicy.RuleOutputFormat.LEGACY)
            assertEquals("$format round trip changed semantics", original, semantics(legacy.content))
        }
    }

    @Test fun switchingAmongEverySupportedFormatNeverAccumulatesLossOrReordersWildcards() {
        val original = semantics(rules)
        var current = rules
        // 验证区块与旧版兜底混合输出之间的连续切换，
        // 不只是每次直接转换同一份原文件。
        for (format in formats + formats.reversed()) {
            current = convert(current, format).content
            assertEquals("Changed semantics after switching to $format", original, semantics(current))
        }
    }

    @Test fun unrecognizedTextAndCommentsSurviveEachFormatAndItsNextSynchronization() {
        val unknown = "future-directive(value)"
        val source = "# keep user explanation\n$unknown\n" + rules + "\n# trailing note\n"
        for (format in formats) {
            val first = convert(source, format).content
            assertEquals(semantics(source), semantics(first))
            assertEquals("$format dropped or duplicated unknown content", 1,
                first.lineSequence().count { it == unknown })
            assertTrue(first.contains("# keep user explanation"))
            assertTrue(first.contains("# trailing note"))
            assertFalse("Unknown content must not force repeated rewrites for $format", convert(first, format).changed)
        }
    }

    @Test fun ambiguousUntypedThreadNamesKeepTheirWholeOwnerInLegacyOrder() {
        val owner = "com.ruleformat.demo"
        val source = """
            $owner=0-7
            $owner{*}=0-3
            $owner{:worker}=7
            $owner{RenderThread}=5
            $owner{$owner:worker}=6
            $owner{*}=4-6
            $owner:worker=0-3
            $owner:worker{Binder:*}=4
            com.ruleformat.other{RenderThread}=7
        """.trimIndent() + "\n"
        val ownerRules = RuleSyntax.parse(source).rules.filter { it.owner == owner || it.owner.startsWith("$owner:") }
        for (format in listOf(CalibPolicy.RuleOutputFormat.AUTHOR_BLOCK,
            CalibPolicy.RuleOutputFormat.COMPACT_EXTENDED_BLOCK)) {
            val first = convert(source, format)
            assertEquals(semantics(source), semantics(first.content))
            // 有歧义的线程不能变成子进程规则，也不能在同一所属进程中
            // 移动到其他重叠通配模式之前或之后。
            val actual = RuleSyntax.parse(first.content).segments.filter { segment ->
                segment.rules.any { it.owner == owner || it.owner.startsWith("$owner:") }
            }
            assertTrue("$format must retain this owner as legacy", actual.none { it.block })
            assertEquals(ownerRules, actual.flatMap { it.rules })
            assertFalse(convert(first.content, format).changed)
        }
    }

    private fun convert(source: String, format: CalibPolicy.RuleOutputFormat): RuleFormatConverter.Conversion {
        val result = RuleFormatConverter.convert(source, format, preserveDuplicates = true)
        assertTrue("$format failed: ${result.error}", result.success)
        return requireNotNull(result.conversion)
    }

    private fun semantics(source: String): Map<Pair<String, Boolean>, List<RuleSyntax.Rule>> =
        RuleSyntax.parse(source).rules.groupBy { it.owner to (it.thread == null) }
}
