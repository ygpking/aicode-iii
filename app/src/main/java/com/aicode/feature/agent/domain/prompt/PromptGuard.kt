package com.aicode.feature.agent.domain.prompt

/**
 * 提示片段的占位符校验结果。
 */
internal sealed interface PlaceholderCheck {
    data object Ok : PlaceholderCheck

    /** 片段引用了未知变量（既不在已知变量集，也不在保留变量集）。 */
    data class UnknownVariables(val names: List<String>) : PlaceholderCheck
}

/**
 * 提示片段的占位符契约校验。
 *
 * system prompt 片段里的 `{{...}}` 只有两类合法：
 * - **可渲染变量**（`AICODE_*`）：由 [SystemPromptProvider.renderVariables] 替换为真实内容；
 * - **保留变量**（`{{INSTRUCTION}}` 等）：压缩流程另作替换，本类不校验、原样保留。
 *
 * 任何其它名字都意味着拼写错误——`renderVariables` 只按已知名字 replace，拼错的名字会被
 * 原样注入模型（模型看到 `{{AICODE_SKIL}}` 这种垃圾），且不会有任何报错。故须在运行期/单测中显式断言。
 */
internal object PromptPlaceholderChecker {

    private val PLACEHOLDER = Regex("""\{\{([A-Za-z0-9_]+)\}\}""")

    /**
     * @param body 待校验的片段正文。
     * @param knownVariables 可渲染变量名（不含花括号），如 `AICODE_SKILLS`。
     * @param reservedVariables 保留变量名，不参与校验。
     */
    fun check(
        body: String,
        knownVariables: Set<String>,
        reservedVariables: Set<String>,
    ): PlaceholderCheck {
        val unknown = PLACEHOLDER.findAll(body)
            .map { it.groupValues[1] }
            .filter { it !in knownVariables && it !in reservedVariables }
            .distinct()
            .toList()
        return if (unknown.isEmpty()) PlaceholderCheck.Ok else PlaceholderCheck.UnknownVariables(unknown)
    }
}

/**
 * 前缀缓存契约审计命中项。
 *
 * @param rule 命中的规则名。
 * @param evidence 触发命中的原文片段。
 */
internal data class StabilityFinding(val rule: String, val evidence: String)

/**
 * 前缀缓存契约审计器。
 *
 * system prompt 被视为稳定前缀：其中任何**逐轮变化**的内容都会使相邻两轮的字节不再一致，
 * 令 KV 前缀缓存失效。本审计用保守正则扫出这类疑似模式——ISO 时间戳、UUID、当前时间、
 * 随机数、毫秒时间戳、逐轮递增计数。
 *
 * 命中的 [StabilityFinding] 仅表示「疑似」；负结果**不保证**稳定。
 */
internal object PromptStabilityAuditor {

    fun audit(body: String): List<StabilityFinding> =
        RULES.mapNotNull { (rule, regex) ->
            regex.find(body)?.let { StabilityFinding(rule, it.value) }
        }

    private val RULES: List<Pair<String, Regex>> = listOf(
        "iso-timestamp" to Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}"""),
        "uuid" to Regex(
            """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""",
        ),
        "time-marker" to Regex(
            """(?i)\bcurrent[\s_-]*(time|date)\b|\bnow\b|当前时间|当前日期|今天的日期""",
        ),
        "random" to Regex(
            """(?i)\brandom\b|随机数|随机值|randomUUID|Math\.random|SecureRandom""",
        ),
        "epoch-millis" to Regex("""\b1\d{12}\b"""),
        "turn-counter" to Regex(
            """(?i)turn[\s_-]?count|iteration[\s_-]?count|第\s*\d+\s*轮|轮次""",
        ),
    )
}
