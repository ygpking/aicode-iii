package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_TIMESTAMP_PREFIX
import com.aicode.feature.agent.domain.model.stripSummaryTimestampNotice
import com.aicode.feature.agent.domain.model.summaryTimestampNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要时点标注的纯逻辑回归：标注让接手者能分辨「摘要（折叠边界前快照）」与「tail 保留段（更新原文）」
 * 的新旧；滚动压缩取旧摘要时必须剥掉标注，否则会被摘要模型抄进新摘要。
 */
class CompactionSummaryTimestampTest {

    private val body = "## 用户原话（逐字保留）\n1. 「你好」\n\n## 任务状态\n- 已完成：无"

    @Test
    fun noticeContainsTimestampAndGuidance() {
        val notice = summaryTimestampNotice("2026-10-11 10:15:31")
        assertTrue(notice.startsWith(CONTEXT_SUMMARY_TIMESTAMP_PREFIX))
        assertTrue(notice.contains("2026-10-11 10:15:31"))
        assertTrue(notice.contains("以保留消息原文为准"))
    }

    @Test
    fun stripRemovesNoticeAndKeepsBody() {
        val stamped = summaryTimestampNotice("2026-10-11 10:15:31") + body
        assertEquals(body, stripSummaryTimestampNotice(stamped))
    }

    @Test
    fun stripKeepsTextWithoutNotice() {
        assertEquals(body, stripSummaryTimestampNotice(body))
    }

    @Test
    fun stripOnNoticeOnlyReturnsBlank() {
        val notice = summaryTimestampNotice("2026-10-11 10:15:31").trimEnd()
        assertEquals("", stripSummaryTimestampNotice(notice))
    }

    @Test
    fun previewAfterStripReturnsFirstBodyLine() {
        // SessionHistoryRepositoryImpl 取 preview 的组合逻辑：先剥标注再取首个非空行。
        val content = summaryTimestampNotice("2026-10-11 10:15:31") + "\n" + body
        val preview = stripSummaryTimestampNotice(content)
            .lineSequence().firstOrNull { it.isNotBlank() }
        assertEquals("## 用户原话（逐字保留）", preview)
    }
}
