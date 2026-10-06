package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续工具调用分组的展开判定。
 *
 * 展开与否 = **上层持久化的手动选择**（[AIAgentViewModel.toolExpansionOverrides]，按 [toolGroupKey] 取）：
 * 没有手动记录就一律收起，工具调用统一默认不展开。
 *
 * **唯一例外**：本轮仍在进行时，最末一个分组自动展开——那批成员行还在逐条追加，收起会看不到
 * 正在产生的内容。回合收工后恢复默认收起。手动选择始终优先于这条自动规则。
 *
 * 重点守四条：①手动选择跨重建稳定（持久化的语义就是每次重建都按同一份覆盖走）；
 * ②没有手动记录时、且轮次已收工时，任何分组都不自动展开；
 * ③进行中的展开只作用于最末一批，不回溯影响历史分组，收工后必须回到收起态；
 * ④手动收起优先于进行中的自动展开。
 */
class ToolGroupExpansionTest {

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "看一下")

    /** 随工具调用发出的助手过渡说明：界面折成一行、归入同一次工具调用（见 [AgentUIMessage.isToolPreface]）。 */
    private fun preface(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "先看一下这个", isToolPreface = true)

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        overrides: Map<String, Boolean> = emptyMap(),
        turnRunning: Boolean = false,
    ) = buildChatItems(messages, overrides, turnRunning)

    @Test
    fun finishedTurn_collapsesByDefault() {
        val items = items(listOf(tool("t1"), tool("t2")))
        assertFalse(items.first().groupExpanded)
        // 收起时不生成成员行
        assertFalse(items.any { it.key == "t1" })
        assertEquals(1, items.size)
    }

    @Test
    fun runningTurn_expandsLastGroup() {
        // 进行中的分组是「当下正在发生的事」：成员行还在逐条追加，此刻收起会把刚流式吐出的
        // 过渡说明连同已有内容一起藏掉，界面上就是「字吐出来又被收回去」。
        val items = items(listOf(tool("t1"), tool("t2")), turnRunning = true)
        assertTrue("进行中的分组必须展开", items.first().groupExpanded)
        assertTrue(items.any { it.key == "t1" })
        assertTrue(items.any { it.key == "t2" })
    }

    @Test
    fun runningTurn_expandsOnlyLastGroup() {
        // 历史分组不受影响：只有最末一批连续成员是「进行中」，前面几批早已收工。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val items = items(messages, turnRunning = true)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertFalse("历史分组保持收起", headers[0].groupExpanded)
        assertTrue("最末分组展开", headers[1].groupExpanded)
    }

    @Test
    fun runningTurn_withoutTrailingToolRows_expandsNothing() {
        // 进行中但末尾不是工具行（工具已跑完、正在追写最终答复）：末尾那批成员早已结束，
        // 不该因为「本轮还在跑」就把历史分组拉开展示。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
        )
        val items = items(messages, turnRunning = true)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(1, headers.size)
        assertFalse("末尾无工具行时不得展开", headers[0].groupExpanded)
    }

    @Test
    fun runningTurn_emptyList_doesNotCrash() {
        // 边界：空列表时倒扫下溢（下标记为 -1）。
        assertTrue(items(emptyList(), turnRunning = true).isEmpty())
    }

    @Test
    fun runningTurn_finishedAfterward_collapsesAgain() {
        // 同一份消息，回合收工（turnRunning=false）后必须回到默认收起态，不会永久展开。
        val messages = listOf(tool("t1"), tool("t2"))
        assertTrue(items(messages, turnRunning = true).first().groupExpanded)
        assertFalse(items(messages, turnRunning = false).first().groupExpanded)
    }

    @Test
    fun runningTurn_manualCollapseIsRespected() {
        // 手动选择优先于自动展开（与参考实现同序）：用户主动收起一个还在跑的分组是明确意图，
        // 自动规则不得把它改回展开——文档里「手动选择之后会一直按你的选择显示」这句不能破。
        val items = items(
            listOf(tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
            turnRunning = true,
        )
        assertFalse("手动收起过：进行中也不自动展开", items.first().groupExpanded)
    }

    @Test
    fun groupWithSingleMember_collapsesByDefault() {
        // 单条工具调用同样折成一行且默认收起：工具调用的默认态统一，不因成员多少而变
        val items = items(listOf(tool("t1")))
        assertEquals(1, items.size)
        assertFalse(items.first().groupExpanded)
    }

    @Test
    fun noGroupAutoExpands() {
        // 收工后的轮次一律默认收起，历史分组与最新分组一视同仁。
        // 注意：这里只覆盖 turnRunning=false。进行中的轮次会展开最末分组（见
        // runningTurn_expandsLastGroup），那是「过程正在追加、藏起来会丢字」的必要例外，
        // 不是自动弹开的历史行为回归。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val items = items(messages)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertTrue("默认全部收起", headers.none { it.groupExpanded })
        // 都没展开：两个分组头 + 中间那条助手消息，没有成员行
        assertEquals(3, items.size)
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true))
        assertTrue(expanded.first().groupExpanded)
        // 同一份覆盖重建（列表滚动回收 / 切页返回后重组走的就是这条路径）：状态必须一致
        val rebuilt = items(messages, overrides = mapOf(groupKey to true))
        assertTrue(rebuilt.first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
        // 展开时成员行才生成：头 + 2 个成员
        assertTrue(rebuilt.any { it.key == "t1" })
        assertTrue(rebuilt.any { it.key == "t2" })
    }

    @Test
    fun manualCollapse_isRespected() {
        val items = items(
            listOf(tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
        )
        assertFalse("手动收起过：保持收起", items.first().groupExpanded)
        assertEquals(1, items.size)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            tool("t1"),
            AgentUIMessage(id = "u1", role = MessageRole.USER, content = "继续"),
            tool("t2"),
        )
        val items = items(messages)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertEquals("toolgroup:t1", headers[0].key)
        assertEquals("toolgroup:t2", headers[1].key)
    }

    @Test
    fun toolWithAttachments_isNotGrouped() {
        // sendFile / generateImage 产出的文件行就是结果本身：不参与「N 次工具调用」折叠，
        // 否则分组默认收起就等于把发来的文件藏起来。它自己是一级 item，同时把左右两批工具切开。
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            tool("t1"), tool("t2"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t3"), tool("t4"),
        )
        val items = items(messages)
        assertEquals("带附件的工具自己要是一级 item", 1, items.count { it.key == "send" })
        // 两侧的连续工具各自成组（分组默认收起，所以只剩两个头）
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(listOf("toolgroup:t1", "toolgroup:t3"), headers.map { it.key })
        assertTrue("分组默认收起", headers.none { it.groupExpanded })
        // send 不是任何分组的成员：不会因为它落在两个分组之间而被折叠吞掉
        assertFalse(items.any { it.toolGroup?.any { member -> member.id == "send" } == true })
    }

    // ---- 成员行缩进判定（isExpandedGroupMember）----

    @Test
    fun membersOfExpandedGroup_areIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        // items = [头, t1, t2]
        assertFalse("分组头自身不缩进", isExpandedGroupMember(items, 0, isToolRow = false))
        assertTrue("展开组的成员应缩进", isExpandedGroupMember(items, 1, isToolRow = true))
        assertTrue(isExpandedGroupMember(items, 2, isToolRow = true))
    }

    @Test
    fun collapsedGroupHasNoMembers() {
        val items = items(listOf(tool("t1"), tool("t2")))
        assertEquals(1, items.size)
        assertFalse(isExpandedGroupMember(items, 0, isToolRow = false))
    }

    @Test
    fun ordinaryToolRowOutsideGroup_isNotIndented() {
        // 两组都被手动展开：中间那条是单成员组，它必须从**自己的**分组头判定，
        // 不能越过助手正文去继承前一个分组的缩进。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t9"),
        )
        val items = items(
            messages,
            overrides = mapOf(groupKey to true, "toolgroup:t9" to true),
        )
        val firstMember = items.indexOfFirst { it.key == "t1" }
        val secondHeader = items.indexOfFirst { it.key == "toolgroup:t9" }
        val secondMember = items.indexOfFirst { it.key == "t9" }
        assertTrue("前置条件：两个分组都应展开", firstMember > 0 && secondHeader > firstMember && secondMember > secondHeader)

        assertTrue("第一组与其成员连续，成员应缩进", isExpandedGroupMember(items, firstMember, isToolRow = true))
        assertFalse("分组头自身不缩进", isExpandedGroupMember(items, secondHeader, isToolRow = false))
        assertTrue("第二组自己的成员应缩进", isExpandedGroupMember(items, secondMember, isToolRow = true))
    }

    @Test
    fun nonToolRow_isNeverIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertFalse(isExpandedGroupMember(items, 1, isToolRow = false))
    }

    @Test
    fun outOfRangeIndex_isNotIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertFalse(isExpandedGroupMember(items, 0, isToolRow = true))
        assertFalse(isExpandedGroupMember(items, items.size, isToolRow = true))
        assertFalse(isExpandedGroupMember(items, -1, isToolRow = true))
    }

    // ---- 过渡说明归入工具分组 ----

    @Test
    fun prefaceAndItsTools_foldIntoOneGroup() {
        // 实测形态：助手说一句（带 tool_calls），工具执行完落一条结果行。
        val messages = listOf(
            preface("p1"),
            tool("t1"),
            preface("p2"),
            tool("t2"),
        )
        val items = items(messages)

        assertEquals("过渡说明与工具行应折成一组", 1, items.size)
        assertEquals("toolgroup:p1", items.first().key)
        assertEquals(4, items.first().toolGroup?.size)
    }

    @Test
    fun groupCount_countsToolRowsNotPreface() {
        val messages = listOf(preface("p1"), tool("t1"), preface("p2"), tool("t2"), tool("t3"))
        val members = items(messages).first().toolGroup!!

        assertEquals("只数真实的工具结果行，过渡说明不算", 3, toolCallCountOf(members))
    }

    @Test
    fun groupCount_neverReportsZeroWhilePrefacePending() {
        // 工具还未开始执行：组内只有过渡说明（它只会在真的带着工具调用时产生）。
        val members = items(listOf(preface("p1"))).first().toolGroup!!

        assertEquals("不得报「 0 次工具调用」", 1, toolCallCountOf(members))
    }

    @Test
    fun expandedGroup_indentsItsPrefaceToo() {
        val messages = listOf(preface("p1"), tool("t1"))
        val items = items(messages, overrides = mapOf("toolgroup:p1" to true))
        val prefaceIndex = items.indexOfFirst { it.key == "p1" }
        val toolIndex = items.indexOfFirst { it.key == "t1" }

        assertTrue("前置条件：组应展开", prefaceIndex > 0 && toolIndex > prefaceIndex)
        assertTrue("成员过渡说明应缩进", isExpandedGroupMember(items, prefaceIndex, isToolRow = true))
        assertTrue("成员工具行应缩进", isExpandedGroupMember(items, toolIndex, isToolRow = true))
    }
}
