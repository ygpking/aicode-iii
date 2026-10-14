package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.extension.ExtensionRepository
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 未指定 `agent` 参数派发的子代理（`isSubAgent = true`，无自定义定义）必须按子代理身份组装提示词：
 * 注入 `agent/subagent-base.md` 基线，**不**注入主代理静态片段（否则它会拿到「默认执行者是子代理」
 * 这类派发条款，而它的工具集已剔除 `task`，被要求派活却没有派活工具）。
 *
 * 内置提示词用真实资产（`src/main/assets/prompts/`，测试工作目录为 `app/`）拷进临时
 * `aicodeDir/prompts/`，让 `resolvePrompt` 的本地副本分支命中，避免依赖 mock 的 assets 读取。
 */
class SystemPromptProviderSubAgentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val aicodeDir get() = File(tmp.root, "aicode")

    private fun provider(): SystemPromptProvider {
        // 单测工作目录为 app/（同 PromptAssetsAuditTest）；兼容从仓库根执行的场景。
        val realPrompts = listOf(File("src/main/assets/prompts"), File("app/src/main/assets/prompts"))
            .firstOrNull { it.isDirectory }
            ?: error("未找到提示词资产目录（测试工作目录应为 app/ 或仓库根）")
        realPrompts.copyRecursively(File(aicodeDir, "prompts"), overwrite = true)

        val installer = mockk<ContainerInstaller>(relaxed = true)
        every { installer.aicodeDir } returns aicodeDir

        // 各来源显式桩空：不依赖 relaxed 对集合返回值的默认行为，只留被测的组装逻辑。
        val skillRepository = mockk<SkillRepository>(relaxed = true)
        every { skillRepository.listSkills() } returns emptyList()
        val memoryRepository = mockk<MemoryRepository>(relaxed = true)
        every { memoryRepository.listMemories(any()) } returns emptyList()
        val agentDefinitionRepository = mockk<AgentDefinitionRepository>(relaxed = true)
        every { agentDefinitionRepository.listEnabled() } returns emptyList()
        val extensionRepository = mockk<ExtensionRepository>(relaxed = true)
        every { extensionRepository.promptDirs(any()) } returns emptyList()

        val executionModeHolder = mockk<ExecutionModeHolder>(relaxed = true)
        every { executionModeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT

        return SystemPromptProvider(
            context = mockk<Context>(relaxed = true),
            skillRepository = skillRepository,
            memoryRepository = memoryRepository,
            containerInstaller = installer,
            agentDefinitionRepository = agentDefinitionRepository,
            extensionRepository = extensionRepository,
            executionModeHolder = executionModeHolder
        )
    }

    private fun context(isSubAgent: Boolean) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "",
        language = null,
        sessionId = "test-session",
        isSubAgent = isSubAgent
    )

    @Test
    fun genericSubAgent_injectsSubAgentBase() {
        val prompt = provider().build(context(isSubAgent = true))

        assertTrue("应包含 subagent-base.md 的特征串", prompt.contains("# 子代理运行基线"))
        assertTrue("应声明子代理身份", prompt.contains("当前角色 (subagent)"))
    }

    @Test
    fun genericSubAgent_excludesMainAgentStaticRules() {
        val prompt = provider().build(context(isSubAgent = true))

        assertFalse("不应包含 00-identity.md 的主代理身份段", prompt.contains("# 身份"))
        assertFalse("不应包含主代理派发条款", prompt.contains("默认执行者是子代理"))
        assertFalse("不应包含可用子代理清单（子代理不能嵌套派发）", prompt.contains("可用子代理 (subagents)"))
    }

    @Test
    fun mainAgent_stillUsesFullMainBranch() {
        val prompt = provider().build(context(isSubAgent = false))

        assertTrue("主代理应包含 00-identity.md 的身份段", prompt.contains("# 身份"))
        assertTrue("主代理应包含派发条款", prompt.contains("默认执行者是子代理"))
    }
}
