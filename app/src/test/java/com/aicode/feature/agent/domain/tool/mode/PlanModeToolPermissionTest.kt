package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * planMode 不再走用户授权弹窗：进出 PLAN 都自动放行。
 *
 * 判定发生在 workflow 的 `requestPermissionIfNeeded`——它在 policyEngine.evaluate 之后
 * 按 permissionPolicy == AUTO_APPROVE 直接放行，故安全拦截（PLAN 下的写操作、
 * Shizuku 红线）仍先于此处生效。
 */
class PlanModeToolPermissionTest {

    @Test
    fun planModeTool_isAutoApproved() {
        val tool = PlanModeTool(mockk<ChatSessionDao>(relaxed = true))
        assertEquals(ToolPermissionPolicy.AUTO_APPROVE, tool.permissionPolicy)
    }
}
