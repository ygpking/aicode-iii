package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 工具的参数规格（工具 [AgentTool.parameters] 的极简投影）。
 *
 * @param required 必填参数名。
 * @param properties 已声明参数名（含必填）。
 */
internal data class ToolSpec(
    val name: String,
    val required: Set<String>,
    val properties: Set<String>,
)

/** 校验结果：通过，或一组人类可读的问题描述。 */
internal sealed interface ValidationResult {
    data object Ok : ValidationResult
    data class Problems(val messages: List<String>) : ValidationResult
}

/**
 * 工具参数校验：把「缺必填」变成显式问题，供上层回写让模型自纠，
 * 而不是让错误调用一头撞进执行层产生难懂失败（例如漏 `content` 的 writeFile 会写出空文件）。
 *
 * 只校验必填项，不拦多余参数——AiCode 工具按名读取参数、无视未声明项，
 * 拦多余参数会把本可成功的调用误判为失败。
 */
internal object ToolArgValidator {
    fun validate(spec: ToolSpec, args: Map<String, JsonElement>): ValidationResult {
        val missing = spec.required
            .filter { args[it] == null || args[it] is JsonNull }
            .sorted()
        return if (missing.isEmpty()) {
            ValidationResult.Ok
        } else {
            ValidationResult.Problems(listOf("缺少必填参数：${missing.joinToString(", ")}"))
        }
    }
}

/**
 * 单轮工具调用数上限：超出部分当轮不执行，回写说明让模型下一轮再调。
 *
 * 返回 (本轮执行的部分, 超出被跳过的部分)，顺序保持。
 */
internal fun <T> partitionByRoundLimit(calls: List<T>, limit: Int): Pair<List<T>, List<T>> {
    require(limit > 0) { "limit 必须为正" }
    return if (calls.size <= limit) calls to emptyList() else calls.take(limit) to calls.drop(limit)
}

/**
 * 未知工具名的纠错指引：给出编辑距离最近的候选与全部可用工具，
 * 让「幻觉工具名」变成可见自纠而非一句冷冰冰的「工具不存在」。
 */
internal fun unknownToolGuidance(name: String, allNames: Set<String>): String {
    val threshold = maxOf(2, name.length / 3)
    val candidates = allNames
        .map { it to TextSimilarity.editDistance(name, it) }
        .sortedBy { it.second }
        .filter { it.second <= threshold }
        .take(3)
        .map { it.first }

    return buildString {
        append("工具 $name 不存在。")
        if (candidates.isNotEmpty()) append("是否想调用：${candidates.joinToString(", ")}？")
        if (allNames.isNotEmpty()) append("当前可用工具：${allNames.sorted().joinToString(", ")}")
    }
}


