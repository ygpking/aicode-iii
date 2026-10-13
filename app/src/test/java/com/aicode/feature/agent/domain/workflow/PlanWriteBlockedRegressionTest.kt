package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN 只读沙箱的入口级回归：`planMode` 改成 AUTO_APPROVE 后，写拦截必须仍然生效。
 *
 * 钉的是**调用侧顺序**（DENY 判定先于 AUTO_APPROVE 短路），不是 `evaluate` 本身——
 * `ToolPermissionPolicyEngineTest` 只测被调函数，把 `requestPermissionIfNeeded` 里两段
 * 顺序对调，那里照样全绿。同型于记忆 `test-entrypoint-not-callee`。
 */
class PlanWriteBlockedRegressionTest {

    private fun engine(): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveForCurrentProject() } returns emptyList<PermissionRule>()
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns false
        return ToolPermissionPolicyEngine(repo, safety)
    }

    /** 同时具备「写工作区」能力与 AUTO_APPROVE 策略——正是 planMode 改动后的形态。 */
    private fun autoApprovedWriter(): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.name } returns "autoApprovedWriter"
        every { t.capabilities } returns setOf(ToolCapability.WRITE_WORKSPACE)
        every { t.effectiveCapabilities(any()) } returns setOf(ToolCapability.WRITE_WORKSPACE)
        every { t.permissionPolicy } returns ToolPermissionPolicy.AUTO_APPROVE
        return t
    }

    private fun workflow(): StatefulAgentWorkflow = StatefulAgentWorkflow(
        mockk<ToolRegistry>(relaxed = true),
        mockk<AIProviderRepository>(relaxed = true),
        mockk<OpenAIApi>(relaxed = true),
        mockk<AnthropicApi>(relaxed = true),
        mockk<GeminiApi>(relaxed = true),
        mockk<SystemPromptProvider>(relaxed = true),
        mockk<ToolPermissionManager>(relaxed = true),
        // 必须是真引擎：换成 mockk 也会编译通过，但断言恒真（假绿）
        engine(),
        mockk<ContextCompactor>(relaxed = true),
        mockk<ToolOutputStore>(relaxed = true),
        mockk<ModelMetadataService>(relaxed = true),
        mockk<CompactionModelSettingsRepository>(relaxed = true),
        mockk<TitleModelSettingsRepository>(relaxed = true),
        mockk<DefaultModelSettingsRepository>(relaxed = true),
        mockk<GeneralSettingsRepository>(relaxed = true),
        mockk<SessionUseCase>(relaxed = true),
        mockk<MessagePersistenceUseCase>(relaxed = true),
        mockk<CheckpointManager>(relaxed = true),
        mockk<LlmCallRecordDao>(relaxed = true),
        mockk<ProviderKeyRotator>(relaxed = true),
        mockk<AgentNotificationCenter>(relaxed = true),
        mockk<AgentEventInjector>(relaxed = true),
        mockk<FileAccessProvider>(relaxed = true),
        mockk<MemoryRepository>(relaxed = true),
        mockk<EvidenceLedgerRepository>(relaxed = true),
    )

    private suspend fun StatefulAgentWorkflow.check(mode: AgentMode) =
        requestPermissionIfNeeded(
            tool = autoApprovedWriter(),
            callId = "c1",
            arguments = emptyMap(),
            argsPreview = "",
            mode = mode,
            sessionId = "s1",
        )

    @Test
    fun planModeWriteTool_isDeniedDespiteAutoApprovePolicy() = runTest {
        val result = workflow().check(AgentMode.PLAN)

        // 若有人把 AUTO_APPROVE 短路上移到 DENY 判定之前，这里会变成 approved=true
        assertFalse("PLAN 下的写工具必须被拦，AUTO_APPROVE 不得绕过", result.approved)
        assertEquals("PLAN_MODE_REJECTED", result.errorCode)
    }

    @Test
    fun sameToolOutsidePlan_isApprovedByAutoApprove() = runTest {
        // 对照组：同一工具在 BUILD 下按 AUTO_APPROVE 放行，证明上面那条失败确由 PLAN 拦截所致
        assertTrue(workflow().check(AgentMode.BUILD).approved)
    }
}
