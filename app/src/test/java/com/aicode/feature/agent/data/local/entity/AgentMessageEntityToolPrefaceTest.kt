package com.aicode.feature.agent.data.local.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「随工具调用发出的过渡说明」的判定。
 *
 * 界面据此把这类正文折叠成一行——模型每调一次工具就写一句，实测占总输出九成以上，
 * 直接铺开会把真正的结论冲出屏幕。判据必须与历史回放路径一致（回放按 toolCallsJson
 * 是否非空决定是否重建 tool_calls），否则会出现「界面折叠了、其实不是过渡说明」。
 */
class AgentMessageEntityToolPrefaceTest {

    private fun entity(
        role: String,
        content: String,
        toolCallsJson: String? = null,
    ) = AgentMessageEntity(
        id = "m1",
        sessionId = "s1",
        role = role,
        content = content,
        timestamp = 0L,
        toolCallsJson = toolCallsJson,
    )

    @Test
    fun assistantWithToolCalls_isToolPreface() {
        val m = entity("ASSISTANT", "接下来看调用方", toolCallsJson = "[{\"id\":\"c1\",\"name\":\"readFile\"}]")
            .toUIMessage()
        assertTrue(m.isToolPreface)
    }

    @Test
    fun assistantWithoutToolCalls_isNotToolPreface() {
        val m = entity("ASSISTANT", "这是最终答复").toUIMessage()
        assertFalse(m.isToolPreface)
    }

    @Test
    fun userAndToolMessages_areNeverToolPreface() {
        // tool_calls 只可能挂在 ASSISTANT 行；其余角色即便字段被脏数据填上也不得误判。
        val user = entity("USER", "继续", toolCallsJson = "[{\"id\":\"c1\"}]").toUIMessage()
        val tool = entity("TOOL", "done", toolCallsJson = "[{\"id\":\"c1\"}]").toUIMessage()
        assertFalse(user.isToolPreface)
        assertFalse(tool.isToolPreface)
    }
}
