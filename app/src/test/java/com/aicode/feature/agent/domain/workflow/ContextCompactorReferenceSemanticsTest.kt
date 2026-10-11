package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * compactIfNeeded 的引用语义回归护栏。
 *
 * 调用方（StatefulAgentWorkflow 的自动压缩与上下文超限自愈）以 `!==` 引用比较判定
 * 「本次是否真的发生了压缩」，并据此决定是否追加续跑提示（COMPACTION_CONTINUATION_NOTICE）。
 * 若未压缩路径返回 `messages.toList()` 这类**拷贝**，引用比较恒为真：
 * 每轮 LLM 调用前都会误判为已压缩并叠加续跑提示，上下文被垃圾提示持续污染。
 * 历史上 7 处未压缩返回点曾是 toList()，已全部改回原引用——本测试锁住该语义。
 */
class ContextCompactorReferenceSemanticsTest {

    private val compactor = ContextCompactor(
        agentMessageDao = mockk(relaxed = true),
        agentDatabase = mockk(relaxed = true),
        modelMetadataService = mockk {
            coEvery { resolve(any(), any(), any()) } returns ModelMetadata(id = "m", contextTokens = 1_000_000)
        },
        systemPromptProvider = mockk(relaxed = true),
        llmCallRecordDao = mockk(relaxed = true),
        generalSettingsRepository = mockk {
            coEvery { compactionThresholdPercent() } returns 90
        },
        chatSessionDao = mockk(relaxed = true),
        compactionModelSettingsRepository = mockk(relaxed = true)
    )

    private val provider = mockk<AIProvider>(relaxed = true)

    private fun user(id: String) = AgentMessage.UserMessage(id = id, content = "q-$id")
    private fun assistant(id: String) = AgentMessage.AssistantMessage(id = id, content = "a-$id")

    @Test
    fun noCompactionReturnsSameListInstance() = runTest {
        val messages = listOf(user("u1"), assistant("a1"))

        val result = compactor.compactIfNeeded(messages, provider)

        assertSame("未压缩必须返回原列表引用，返回拷贝会让调用方的 !== 判定恒真", messages, result)
    }

    @Test
    fun forcedCompactionWithTinyHistoryStillReturnsSameInstance() = runTest {
        // force=true（手动 /compress、超限自愈）但历史过小（size<=2 短路）同样应原引用返回。
        val messages = listOf(user("u1"))

        val result = compactor.compactIfNeeded(messages, provider, force = true)

        assertSame(messages, result)
    }
}
