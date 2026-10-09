package com.aicode.feature.agent.domain.workflow

    /**
     * 命令执行结果的成败判定。
     *
     * 移植自 spyrae/truthguard 的 `scripts/check-exit-code.sh`（MIT）。原实现的核心贡献在于
     * **把「退出码非零」与「命令失败」区分开**：`grep` 没匹配返回 1、`diff` 有差异返回 1、
     * `test` 条件为假返回 1，这些都是正常语义，不是失败。守卫若一律按非零判失败，
     * 会把这些正常调用算成「没有凭证」，反而误伤。
     *
     * 另补一层：**工具缺失（127）判为「无法判定」而不是失败**——与 proof 的
     * `verdict_for()` 一致（`code == 127` → INCONCLUSIVE，绝不给假 PASS、也不误判为 FAIL）。
     *
     * ## 为什么还要 [hasFailureSignal]
     *
     * AiCode 的工具层把非零退出码包成 `ToolResult.Success`（只往输出文本追加错误摘要，
     * 见 `ExecuteCommandTool.execute`），故 workflow 层拿到的 `isError` 对「命令跑了但失败」
     * **恒为 false**。仅靠退出码无法判失败，必须同时看输出文本——这也是 truthguard 原实现
     * 的真正主力（其文件头自述 "Catches cases where command fails but AI might claim success"）。
     */
internal object CommandOutcome {

    /** 退出码语义：该次调用能否作为「成功执行」的凭证。 */
    enum class Outcome {
        /** 命令确实成功执行。 */
        SUCCESS,

        /** 命令执行了但失败——不能作为凭证。 */
        FAILURE,

        /** 命令没跑成（工具缺失、环境缺依赖）——不能算失败，但也不构成凭证。 */
        UNRUNNABLE,

        /** 命令超时被杀——不算失败，但同样不构成凭证。 */
        TIMED_OUT,
    }

    /**
     * 非零退出码里，属于「正常语义」的那一类（对照 truthguard 的例外表）。
     *
     * 判定顺序与原实现一致：先看是否命中例外，再看是否落在 `||` / `&&` / `if` 里，
     * 都排除后才判失败。
     */
    fun classify(command: String, exitCode: Int, stderr: String? = null): Outcome {
        if (exitCode == 0) return Outcome.SUCCESS
        if (exitCode == 127) return Outcome.UNRUNNABLE
        if (exitCode == -1 || exitCode == 124) return Outcome.TIMED_OUT

        // grep/rg 无匹配返回 1 —— 正常，不是错误
        if (GREP_LIKE.containsMatchIn(command) && exitCode == 1) return Outcome.SUCCESS
        // diff 发现差异返回 1 —— 正常
        if (DIFF_LIKE.containsMatchIn(command) && exitCode == 1) return Outcome.SUCCESS
        // test / [ 条件为假返回 1 —— 正常
        if (TEST_LIKE.containsMatchIn(command) && exitCode == 1) return Outcome.SUCCESS
        // 命令串在条件位（|| && if）里，非致命退出码是设计如此
        if (IN_CONDITION.containsMatchIn(command) && exitCode in 1..127) return Outcome.SUCCESS

        // 环境缺失类错误：判为「没跑成」而非失败
        if (stderr != null && ENV_FAILURE.containsMatchIn(stderr)) return Outcome.UNRUNNABLE

        return Outcome.FAILURE
    }

    private val GREP_LIKE = Regex("(^|\\||\\s)(grep|rg|egrep|fgrep)\\s")
    private val DIFF_LIKE = Regex("(^|\\||\\s)diff\\s")
    private val TEST_LIKE = Regex("(^|\\s)(test|\\[)\\s")
    private val IN_CONDITION = Regex("\\|\\||&&|^\\s*if\\s")

    private val ENV_FAILURE = Regex(
        "command not found|No module named|Cannot find module|is not recognized as an internal" +
            "|ENOENT|no tests ran|collected 0 items|error: no such command",
        RegexOption.IGNORE_CASE,
    )

    /** 该命令是否属于「会真跑验证」的形态（供凭证判定与作弊检测共用）。 */
    private val VERIFY_COMMAND_RE = Regex(
        "(?i)\\bgradlew\\b|\\bassemble\\w*|\\bcheck_migrations\\b|\\btest|\\blint\\b" +
            "|\\bpytest\\b|\\bgo test\\b|\\bcargo test\\b|\\bnpm test\\b|\\bunittest\\b|\\bvitest\\b",
    )

    /**
     * 构建/测试失败的输出特征。搬 truthguard 的 `BUILD_FAIL_PATTERNS` 并补 gradle 与
     * python/node 变体（后两类是非构建型验证脚本的主要失败形式，原版两表都没有）。
     */
    private val FAILURE_SIGNAL_RE = Regex(
        "BUILD FAILED|FAILURE: Build failed|build failed|compilation error|compile error" +
            "|SyntaxError|Cannot find module|Module not found" +
            "|failures?:\\s*[1-9]\\d*|Tests?:\\s*\\d+ failed|\\d+ (?:test|tests) failed" +
            "|AssertionError|COMPILATION ERROR|Execution failed for task" +
            // 非构建型验证脚本（python3 check.py 等）的失败形式；(?m) 让 ^ 按行首匹配。
            // (?!\s*0\b) 排除「Error: 0 warnings」这类零计数成功输出。
            "|Traceback \\(most recent call last\\)|(?m)^Error: (?!\\s*0\\b)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 输出文本里是否有明确的失败信号。
     *
     * 必要性见类 KDoc：工具层把非零退出包成 Success，故 `isError` 不可信，
     * 必须从这里补判。只看输出尾部（调用方传尾部切片即可），避免全文扫描开销。
     */
    fun hasFailureSignal(output: String?): Boolean =
        !output.isNullOrBlank() && FAILURE_SIGNAL_RE.containsMatchIn(output)

    /**
     * 验证类命令识别。
     *
     * `\btest` 只加**前**边界：真实 gradle 任务名是 `:app:testUniversalDebugUnitTest` 这种
     * `:test…`，加后边界反而会漏掉最该认的那条命令。
     */
    fun isVerifyCommand(command: String): Boolean = VERIFY_COMMAND_RE.containsMatchIn(command)

    /**
     * 验证命令的**类别**。
     *
     * 账本要判「同一验证任务先失败后成功」，不能比完整命令串——模型重跑时几乎必改参数
     * （换个 `--tests` 类名、加个 `--rerun-tasks`），串比对召回率趋零。归到类别即可。
     *
     * @return 类别名；非验证命令返回 null
     */
    fun categoryOf(command: String): String? {
        val c = command.lowercase()
        if (!isVerifyCommand(command)) return null
        return when {
            c.contains("gradlew") && c.contains("test") -> "gradle-test"
            c.contains("gradlew") && c.contains("assemble") -> "gradle-assemble"
            c.contains("gradlew") -> "gradle-other"
            c.contains("check_migrations") -> "check-migrations"
            c.contains("pytest") || c.contains("unittest") -> "pytest"
            c.contains("go test") -> "go-test"
            c.contains("cargo test") -> "cargo-test"
            c.contains("npm test") || c.contains("yarn test") || c.contains("pnpm test") -> "npm-test"
            c.contains("vitest") -> "vitest"
            c.contains("lint") -> "lint"
            else -> "verify-other"
        }
    }
}
