package com.aicode.feature.agent.domain.tool.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class ManageMcpTool @Inject constructor(
    private val mcpConfigRepository: McpConfigRepository
) : AgentTool() {
    private companion object {
        const val TAG = "ManageMcpTool"
    }

    override val name = "manageMcp"
    override val description = "管理 Model Context Protocol (MCP) 服务器。支持添加、删除与列表查询。添加/删除会修改 Agent 配置，不会自动安装 Node/Python 等运行时。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "执行的操作类型，必填",
            enum = listOf("list", "add_stdio", "add_http", "remove")
        ),
        "server_name" to ToolParameter(
            name = "server_name",
            type = ParameterType.STRING,
            description = "MCP 服务器名称（操作非 list 时必填），只能含 ASCII 字母、数字、下划线与连字符",
            required = false
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "执行命令，如 npx 或 python (仅 add_stdio 必填)",
            required = false
        ),
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.ARRAY,
            description = "命令参数数组 (仅 add_stdio 可选)",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "HTTP 远程服务地址 (仅 add_http 必填)",
            required = false
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "配置作用域：global（全局，默认）或 project（当前项目，仅当前工作区生效，项目级优先于全局）",
            enum = listOf("global", "project"),
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val server = args["server_name"]?.jsonPrimitive?.contentOrNull ?: "未指定"
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "当前项目" else "全局"
        val summary = when (action) {
            "add_stdio" -> "添加本地 MCP server: $server ($scopeLabel)"
            "add_http" -> "添加 HTTP MCP server: $server ($scopeLabel)"
            "remove" -> "移除 MCP server: $server ($scopeLabel)"
            "list" -> "列出 MCP server ($scopeLabel)"
            else -> "管理 MCP server"
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认修改 MCP 配置",
            summary = summary,
            details = "操作：$action\n服务：$server\n作用域：$scopeLabel\n该操作会修改 Agent 的 MCP 配置，新增配置将在下一次会话生效。",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少 action 参数", "MISSING_ACTION")
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "当前项目" else "全局"

        // 扩展包贡献的同名 server 会遮蔽本作用域的目录条目（同作用域内扩展胜出），
        // 新增/删除同名条目既不可见也不生效，属静默失效，故与 UI 同一判定直接拒绝。
        fun extensionConflict(name: String): ToolResult.Error? = if (mcpConfigRepository.isExtensionServer(name, scope)) {
            ToolResult.Error(
                "$scopeLabel 的 MCP server「$name」由扩展包提供，不能通过工具修改。" +
                    "请改动扩展包自身配置，或换一个名字。",
                "MCP_SERVER_FROM_EXTENSION"
            )
        } else {
            null
        }

        suspend fun readServers(): List<McpServerConfig> =
            if (scope == McpScope.PROJECT) mcpConfigRepository.getProjectServers() else mcpConfigRepository.getGlobalServers()

        suspend fun writeServers(servers: List<McpServerConfig>) {
            if (scope == McpScope.PROJECT) mcpConfigRepository.setProjectServers(servers) else mcpConfigRepository.setGlobalServers(servers)
        }

        /**
         * 与 [com.aicode.feature.agent.domain.mcp.McpConfigRepository.serialize] 结构一致，
         * 但把 headers/env 的值替换为占位符，仅保留键名——让模型知道「有这个字段」
         * 而不泄露其值。
         *
         * 仅用于回显给模型的路径（action="list"）。写盘路径必须继续使用未掩码的
         * serialize()，否则读-改-写会丢字段。
         */
        fun serializeMasked(servers: List<McpServerConfig>): String {
            val masked = servers.map { server ->
                server.copy(
                    headers = server.headers.mapValues { (_, v) -> if (v.isBlank()) "" else "[REDACTED]" },
                    env = server.env.mapValues { (_, v) -> if (v.isBlank()) "" else "[REDACTED]" }
                )
            }
            return mcpConfigRepository.serialize(masked)
        }

        return try {
            when (action) {
                "list" -> {
                    val servers = readServers()
                    // 掩码化：serialize() 会把 headers/env 的明文凭据一并回吐，
                    // 而本返回值直接进入模型上下文（并随之进入会话历史、备份包、
                    // 轨迹日志）。用户执行「列出服务器」时不会预期它回吐 Token。
                    ToolResult.Success(JsonPrimitive(serializeMasked(servers)))
                }
                "remove" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("remove 缺少 server_name", "MISSING_SERVER_NAME")
                    extensionConflict(name)?.let { return it }
                    val servers = readServers().toMutableList()
                    // ignoreCase：与合并（小写作 key）及 UI 的忽略大小写匹配一致，否则大小写不一致时删不掉。
                    val removed = servers.removeIf { it.name.equals(name, ignoreCase = true) }
                    if (removed) {
                        writeServers(servers)
                        FileLogger.i(TAG, "manage_mcp remove: server=$name scope=$scopeLabel")
                        ToolResult.Success(JsonPrimitive("已成功移除 $scopeLabel MCP server: $name"))
                    } else {
                        ToolResult.Error("未找到 $scopeLabel MCP server: $name。可用 manageMcp(action=\"list\", scope=...) 查看现有 server。", "MCP_SERVER_NOT_FOUND")
                    }
                }
                "add_stdio" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少 server_name", "MISSING_SERVER_NAME")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name), "INVALID_SERVER_NAME")
                    extensionConflict(name)?.let { return it }
                    val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少 command", "MISSING_COMMAND")
                    val commandArgs = args["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    
                    val newServer = McpServerConfig(
                        name = name,
                        command = command,
                        args = commandArgs,
                        env = emptyMap(),
                        enabled = true
                    )
                    
                    val servers = readServers().toMutableList()
                    servers.removeIf { it.name.equals(name, ignoreCase = true) }
                    servers.add(newServer)
                    writeServers(servers)
                    
                    FileLogger.i(TAG, "manage_mcp add_stdio: server=$name scope=$scopeLabel command=$command")
                    ToolResult.Success(JsonPrimitive("成功添加 $scopeLabel 本地 MCP server: $name。配置将在下一次会话生效。若命令依赖 Node/Python 等运行时，请通过命令工具在用户确认后安装。"))
                }
                "add_http" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少 server_name", "MISSING_SERVER_NAME")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name), "INVALID_SERVER_NAME")
                    extensionConflict(name)?.let { return it }
                    val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少 url", "MISSING_URL")
                    
                    val newServer = McpServerConfig(
                        name = name,
                        url = url,
                        headers = emptyMap(),
                        enabled = true
                    )
                    
                    val servers = readServers().toMutableList()
                    servers.removeIf { it.name.equals(name, ignoreCase = true) }
                    servers.add(newServer)
                    writeServers(servers)
                    
                    FileLogger.i(TAG, "manage_mcp add_http: server=$name scope=$scopeLabel url=$url")
                    ToolResult.Success(JsonPrimitive("成功添加 $scopeLabel HTTP MCP server: $name. 配置将在下一次会话生效。"))
                }
                else -> ToolResult.Error("未知的 action: $action。支持：list / add_stdio / add_http / remove。", "INVALID_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "manage_mcp 执行失败: ${e.message}", e)
            ToolResult.Error("管理 MCP 失败: ${e.message}。检查 server 名称、命令或 URL 后重试。", "MCP_MANAGE_FAILED")
        }
    }

    private fun invalidNameMessage(name: String): String =
        "server_name 只能含 ASCII 字母、数字、下划线与连字符（当前：$name）——server 名称会拼成 mcp__<server_name>__<tool> 送给模型，必须符合工具命名规范。"

    private fun resolveScope(args: Map<String, JsonElement>): McpScope =
        when (args["scope"]?.jsonPrimitive?.contentOrNull) {
            "project", "PROJECT" -> McpScope.PROJECT
            else -> McpScope.GLOBAL
        }
    
}
