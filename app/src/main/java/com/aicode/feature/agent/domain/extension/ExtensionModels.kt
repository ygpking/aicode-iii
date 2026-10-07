package com.aicode.feature.agent.domain.extension

import kotlinx.serialization.Serializable

/**
 * 扩展包清单（`extensions/<extId>/manifest.json`）。
 *
 * 扩展是**声明式资源包**（md/JSON，不含可执行代码）：向五套 agent 能力机制贡献资源，
 * 层叠次序为「内置 < 扩展（同作用域内）、项目 > 全局（近者胜）」，与技能/记忆/MCP 既有
 * 的两级语义一致。
 *
 * @param id 扩展标识（`[a-z0-9-]`），同时是目录名与禁用管理的键。
 * @param contributes 贡献清单：各字段为**相对于扩展根的目录/文件路径**，解析时校验不越界。
 */
@Serializable
data class ExtensionManifest(
    val id: String = "",
    val name: String = "",
    val version: Int = 1,
    val description: String = "",
    val contributes: Contributes = Contributes()
) {
    @Serializable
    data class Contributes(
        /** 技能源目录（内含 `<skill-dir>/SKILL.md`），可多个。 */
        val skills: List<String> = emptyList(),
        /** 提示词片段目录（内含 `NN-<名称>.md` 数字片段），可多个。 */
        val prompts: List<String> = emptyList(),
        /** 记忆目录（内含 `<name>.md`），可多个。 */
        val memory: List<String> = emptyList(),
        /** MCP server 配置文件（`{ "server-name": {McpServerConfig} }`）。 */
        val mcp: String? = null
    )
}
