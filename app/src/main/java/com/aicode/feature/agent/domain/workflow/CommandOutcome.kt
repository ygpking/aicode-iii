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
     * 必须从这里补判。调用方应传入**头尾两端**的切片（见 [outputEdges]）而非只取尾部——
     * 失败信号既可能在尾部（Gradle），也可能在头部（Traceback、行首 `Error:`）。
     */
    fun hasFailureSignal(output: String?): Boolean =
        !output.isNullOrBlank() && FAILURE_SIGNAL_RE.containsMatchIn(output)

    /**
     * 命令**没跑成**的输出特征（超时被杀、环境缺工具、测试框架零收集）。
     *
     * 与 [FAILURE_SIGNAL_RE] 分开成两张表：「跑了但失败」与「根本没跑成」对凭证的处置
     * 相同（都不算凭证），但语义不同——合表会让只读 [hasFailureSignal] 的调用方
     * 把「超时」理解成「命令执行过且失败」。
     *
     * 超时文案由容器引擎在终止进程后追加（见 `LinuxContainerEngine` 的
     * 「[命令执行超时：超过 …ms 已被强制终止。…]」），它既不在失败特征表里、
     * 也不在环境缺失表（`ENV_FAILURE` 只在 [classify] 里用，而 [classify] 需要退出码）——
     * 故超时被杀的命令此前会被当成「验证成功」的凭证（真机 2026-10-13 实测：
     * 两天 12 次命令超时，其中 5 次是编译/验证类）。
     */
    private val UNRUNNABLE_SIGNAL_RE = Regex(
        "命令执行超时|已被强制终止|command not found|no tests ran|collected 0 items",
        RegexOption.IGNORE_CASE,
    )

    /** 输出文本是否表明命令**没跑成**（超时被杀、命令不存在、测试零收集）。 */
    fun hasUnrunnableSignal(output: String?): Boolean =
        !output.isNullOrBlank() && UNRUNNABLE_SIGNAL_RE.containsMatchIn(output)

    /**
     * 该次命令输出能否作为「跑过且成功」的凭证。**凭证判定的唯一出口**。
     *
     * 四处共用（R1 验证、R5 检索、R6 git 对账、账本的命令成败），集中一处防口径漂移：
     * 只判 [hasFailureSignal] 会把「超时被杀 / 没跑成」算成成功凭证（假 PASS）。
     */
    fun isUsableCredentialOutput(output: String?): Boolean =
        !hasFailureSignal(output) && !hasUnrunnableSignal(output)

    /** [outputEdges] 头、尾各保留的字符数。 */
    const val OUTPUT_EDGE_CHARS = 2000

    /**
     * 命令输出的**头尾两端**切片，供 [hasFailureSignal] 判定真实失败。
     *
     * 为何是"两端"而非只取尾部：失败信号在输出里的位置**不固定**——
     *  - 尾部：[FAILURE_SIGNAL_RE] 里的 `BUILD FAILED`、`Execution failed for task`（Gradle 风格）；
     *  - **头部**：同表里的 `Traceback (most recent call last)`、`COMPILATION ERROR`，
     *    以及用**行首锚点** `^Error:`（等价 Kotlin 里的 `(?m)^Error:`）匹配的条目。
     *
     * 只取尾部时，长输出（构建日志、大测试套件）的头部信号会被切掉，使**失败的验证命令
     * 被判为成功凭证**——直接产生 `hasFailureSignal` 声称要防的假 PASS。本函数修此缺口。
     *
     * 输出不超限时**原样返回**（不切片、不加分隔符）；超限时头尾各取 [OUTPUT_EDGE_CHARS]，
     * 中间以换行分隔——保证头部段的 `^Error:` 行首锚点仍成立，也避免头尾直接粘连
     * 造出跨段的虚假匹配。
     */
    fun outputEdges(output: String): String {
        if (output.length <= OUTPUT_EDGE_CHARS * 2) return output
        return output.take(OUTPUT_EDGE_CHARS) + "\n" + output.takeLast(OUTPUT_EDGE_CHARS)
    }

    /**
     * 验证类命令识别。
     *
     * 判据是「**分段 + 看段首可执行体**」，而不是在整条命令串里搜子串。
     * 早期实现用子串表（含裸 `\btest`），两个方向都错，均有真机实测：
     *
     *  - **假凭证**：`grep -n '^func Test' foo_test.go`、`cd x && echo "=== test 文件行数 ==="`
     *    都会被当成验证命令（六天日志里这类纯字面量命中 1400+ 条），可给「测试已通过」背书；
     *  - **漏认**：`bash scripts/verify-all.sh`、`python3 scripts/check_migrations.py` 这类
     *    真实门禁不在子串表里，跑了也不算凭证（六天里 800+ 条）。
     *
     * 后者是子串表路线的固有缺陷——每新增一个门禁脚本就要补一次名单。改按结构判定后
     * 两个方向同时收敛：真执行的验证命令里命中率 29% → 98%。
     *
     * 分段按 `\n` / `;` / `&&` 切，另有 `|`（它同时吞掉 `||`）——切分符一并丢掉，故后续无需再处理
     * 管道。命令替换、重定向与引号内的 `;` / `|` 不作特殊处理——它们在真实语料里占比极低，
     * 而误判的代价只是「多一条/少一条凭证」，不是错判作弊。
     *
     * 注意 [BuildCommandDetector] 有一套同构的「切段 + 剥壳」逻辑（服务于「要不要串行等待构建」）。
     * 两者**用途不同、判据表本就不同**，故不合并；但改动本文件的分段/包装器规则时，
     * 请一并对照那边，防止两边对同一条命令的判断无声分叉。
     */
    fun isVerifyCommand(command: String): Boolean =
        command.split(*SEGMENT_SEPARATORS).any { segmentIsVerify(it) }

    /** 命令分段符；`|` 泛匹配单个管道与 `||`，`&&` 单列。 */
    private val SEGMENT_SEPARATORS = arrayOf("\n", ";", "|", "&&")

    /** 前导环境变量赋值（`CGO_ENABLED=1`）——剥掉后才是真正的可执行体。 */
    private val LEADING_ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=\\S*$")

    /** 包装器：它们本身不是被验证的对象，剥掉看后面真正跑了什么。 */
    private val SHELL_WRAPPERS = setOf("sh", "bash", "zsh", "dash", "env", "sudo", "nohup", "time", "exec")

    /** `timeout` 的时长参数形态（`900`、`30s`、`5m`）。 */
    private val TIMEOUT_DURATION = Regex("^-?\\d+[smh]?$")

    /**
     * 项目门禁脚本形态（两分支）：
     *
     *  - `scripts/` 下以 check / verify / test 开头：`scripts/check_migrations.py`、`scripts/verify-all.sh`；
     *  - 无目录前缀时要求 `check|verify|test` 后跟 `-` 或 `_`：`check_migrations.py`、`verify-all.sh`。
     *
     * 第二分支的 `[-_]` 不是装饰——去掉它，`tests_failed.py`、`latest.py` 这类无关文件
     * 也会命中（前綴就是 `test`），变成「拿任意脚本给『测试通过』背书」。
     * 不列具体文件名：每新增一个门禁脚本都要补名单，是子串表路线的固有缺陷。
     */
    private val GATE_SCRIPT = Regex(
        "(?:^|/)(?:scripts/(?:check|verify|test)[\\w.-]*|(?:check|verify|test)[-_][\\w.-]*)\\.(?:py|sh|bash)$",
        RegexOption.IGNORE_CASE,
    )

    /** 开箱即用的测试/检查类可执行体（带子命令的另算）。 */
    private val BARE_VERIFY_EXECUTABLES = setOf(
        "gradlew", "gradle", "pytest", "vitest", "eslint", "ruff", "mypy", "tslint", "tsc", "lint",
    )

    /**
     * 一段命令是否属于「会真跑验证」——只看该段**段首的可执行体**与它的参数形态。
     */
    private fun segmentIsVerify(segment: String): Boolean {
        val tokens = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val (exe, rest) = stripWrappers(tokens) ?: return false
        val args = rest.drop(1)
        // 显式保留可空类型：它要与字面量集合比对，而 `Set<String>.contains` 不接受可空入参，
        // 故比对的集合一律写成 `setOf<String?>(…)`，或用字符串字面量走 `when` 的 equals 语义。
        val sub: String? = args.firstOrNull()
        return when {
            exe in BARE_VERIFY_EXECUTABLES -> if (exe == "gradlew" || exe == "gradle") !isGradleQuery(args) else true
            exe == "go" -> sub in setOf<String?>("test", "vet", "build")
            exe == "cargo" -> sub in setOf<String?>("test", "check", "build", "clippy")
            exe in setOf("npm", "yarn", "pnpm") -> when (sub) {
                "test", "build" -> true
                "run" -> args.getOrNull(1) in setOf<String?>("test", "build", "lint")
                else -> false
            }
            exe == "make" -> args.any { it in setOf("test", "check", "verify") }
            exe in setOf("python", "python3", "py") -> {
                // 只在 `-m` 紧跟解释器时才算模块调用：`python3 scripts/check.py -m 5` 里的
                // `-m` 是脚本自己的参数，按「任意位置找 -m」会走模块分支、误判为不是验证。
                if (sub == "-m") args.getOrNull(1) in setOf<String?>("pytest", "unittest")
                else args.any { GATE_SCRIPT.containsMatchIn(it) }
            }
            // 脚本已在段首且包装器已剥（`bash scripts/verify-all.sh` 的 bash 已剥掉，
            // 此处 exe 就是脚本名本身）——按脚本名形态判，不能只查参数，否则会漏掉它。
            else -> (exe.endsWith(".py") || exe.endsWith(".sh") || exe.endsWith(".bash")) &&
                GATE_SCRIPT.containsMatchIn(exe)
        }
    }

    /**
     * gradle 的**纯查询任务**：`tasks`、`--version`、`help` 这些只打印信息、不跑测试也不编译，
     * 不产生验证结论。命令串里搜 `\bgradlew\b` 的旧实现会把它们一并当成验证凭证。
     */
    private val GRADLE_QUERY_ARGS = setOf(
        "tasks", "projects", "dependencies", "properties", "model", "help", "--version", "-v",
    )

    private fun isGradleQuery(args: List<String>): Boolean =
        args.isEmpty() || args.all { it.startsWith("-") || it in GRADLE_QUERY_ARGS }

    /**
     * 剥掉段首的环境变量赋值、`timeout`（含时长参数）、shell 包装器。
     *
     * @return (可执行体 basename, 该段的 token 列表)；剥空了返回 null
     */
    private fun stripWrappers(tokens: List<String>): Pair<String, List<String>>? {
        var rest = tokens
        while (rest.isNotEmpty()) {
            val head = rest.first()
            if (LEADING_ENV_ASSIGN.matches(head)) {
                rest = rest.drop(1)
                continue
            }
            if (head == "timeout") {
                // `timeout 900 cmd` / `timeout -k 5 900 cmd`：连时长与短选项一起去掉。
                var i = 1
                while (i < rest.size && (rest[i].startsWith("-") || TIMEOUT_DURATION.matches(rest[i]))) i++
                rest = rest.drop(i)
                continue
            }
            val base = head.substringAfterLast('/')
            if (base in SHELL_WRAPPERS) {
                rest = rest.drop(1)
                continue
            }
            return base to rest
        }
        return null
    }

    /**
     * 只读检索类命令识别（R5「声称查过」的凭证判定）。
     *
     * 白名单而非黑名单：管道组合里只要有一段是真检索（`rg x && do-something`），
     * 该次调用确实产生过查证动作，就算凭证（漏报方向，与守卫取向一致）。
     * python/node 等脚本语言不进表——它们能干任何事，从命令行无法认定「只是查」。
     */
    private val READONLY_SEARCH_RE = Regex(
        "(^|[|;&]\\s*)(rg|grep|egrep|fgrep|ls|find|cat|head|tail|wc|which|stat|du|diff|file|tree|whereis|locate)\\b" +
            "|\\bgit\\s+(?:log|show|status|diff|branch|tag|ls-files|ls-remote|rev-parse|blame)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isReadonlySearchCommand(command: String): Boolean = READONLY_SEARCH_RE.containsMatchIn(command)

    /**
     * 验证命令的**类别**。
     *
     * 账本要判「同一验证任务先失败后成功」，不能比完整命令串——模型重跑时几乎必改参数
     * （换个 `--tests` 类名、加个 `--rerun-tasks`），串比对召回率趋零。归到类别即可。
     *
     * 分类同样看**段首可执行体**：早期用 `contains("test")` 这类子串判，会把
     * `python3 scripts/check_doc_tables.py --self-test` 归到 `gradle-test`（因为含 "test"），
     * 使「先红后绿」判定把两个不相干的验证任务当成同一类。
     *
     * 门禁脚本**逐个归类**（`gate:<脚本名>`）而非归为一个大类：账本用它判「同一任务先红后绿」，
     * 合并成 `gate-script` 会把「check_architecture 失败 → 改跑 check_migrations 成功」当成
     * 同一任务的先红后绿，反而是放松。
     *
     * @return 类别名；非验证命令返回 null
     */
    fun categoryOf(command: String): String? {
        if (!isVerifyCommand(command)) return null
        for (segment in command.split(*SEGMENT_SEPARATORS)) {
            if (!segmentIsVerify(segment)) continue
            val tokens = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val (exe, rest) = stripWrappers(tokens) ?: continue
            val args = rest.drop(1)
            val gate = (args + exe).firstOrNull { GATE_SCRIPT.containsMatchIn(it) }
            when {
                gate != null -> return "gate:" + gate.substringAfterLast('/').substringBeforeLast('.')
                exe == "gradlew" || exe == "gradle" -> return when {
                    args.any { it.contains("test", ignoreCase = true) } -> "gradle-test"
                    args.any { it.contains("assemble", ignoreCase = true) } -> "gradle-assemble"
                    else -> "gradle-other"
                }
                exe in setOf("pytest", "vitest") -> return exe
                exe in setOf("go", "cargo") -> return "$exe-${args.firstOrNull() ?: "verify"}"
                exe in setOf("npm", "yarn", "pnpm") -> {
                    val script = if (args.firstOrNull() == "run") args.getOrNull(1) else args.firstOrNull()
                    return "npm-" + (script ?: "script")
                }
                exe == "make" -> return "make-test"
                exe in setOf("eslint", "ruff", "mypy", "tslint", "tsc", "lint") -> return "lint"
                // 此处只剩 `python -m pytest|unittest`（带门禁脚本的已被 gate 分支拦下）。
                exe in setOf("python", "python3", "py") ->
                    return args.getOrNull(1) ?: "pytest"
                else -> return "verify-other"
            }
        }
        return "verify-other"
    }
}
