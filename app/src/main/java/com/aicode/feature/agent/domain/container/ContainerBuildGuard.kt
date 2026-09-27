package com.aicode.feature.agent.domain.container

/**
 * 容器内编译命令的内存保护：为高内存开销的构建命令注入并行度上限。
 *
 * ## 为什么需要
 *
 * 容器（proot）进程的内存**计入宿主 App**。cargo/rustc 默认按 CPU 核数并行编译，
 * Gradle 默认按核数起 worker，在内存有限的手机上很容易把整机内存推过系统的低内存阈值，
 * 触发 LMK 回收——而被回收的往往是 App 自身进程（实测 `ApplicationExitInfo`
 * `reason=3 (LOW_MEMORY)`，进程 pid 直接消失、无任何异常堆栈）。
 *
 * 表现是「聊着聊着 App 无声重启」，用户无法理解，开发者也无从下手：进程被 kill -9 时
 * 异常处理器与 finally 都不会执行，日志里只剩一段空白。
 *
 * ## 做法与边界
 *
 * 只在**识别为高内存构建命令**时追加并行度参数，不改动其它命令。
 * 已显式指定并行度的命令**原样放行**——用户的明确选择优先于本保护。
 *
 * 纯字符串处理、不访问文件系统，便于单测。
 */
internal object ContainerBuildGuard {

    /**
     * 默认并行度。手机上 2 路已能明显降低峰值内存，同时不至于让编译慢到不可接受。
     * 该值刻意保守：宁可慢一些，也不要因内存被杀导致整个会话丢失。
     */
    const val DEFAULT_JOBS = 2

    /**
     * 高内存构建命令 → 注入方式。
     *
     * [inject] 接收原始命令与并行度，返回改写后的命令；返回 null 表示无法安全改写（保持原样）。
     */
    private data class Rule(
        /** 命中该正则即认为是此类构建命令。 */
        val detect: Regex,
        /** 判断命令中是否已显式指定并行度，是则不再注入。 */
        val hasExplicitJobs: Regex,
        val inject: (String, Int) -> String,
    )

    private val RULES: List<Rule> = listOf(
        // cargo：`cargo build` / `cargo test` / `cargo run` 等子命令支持 -j
        // 注意要排除 `cargo install` 等不支持 -j 的子命令，故只匹配构建类子命令。
        Rule(
            detect = Regex("""\bcargo\s+(build|test|run|check|clippy|bench)\b"""),
            // 环境变量形式的并行度也算已指定：单测曾在此漏过（CARGO_BUILD_JOBS=8 被重复注入）
            hasExplicitJobs = Regex("""CARGO_BUILD_JOBS=|-j\s*\d|--jobs(\s|=)"""),
            inject = { cmd, jobs -> "CARGO_BUILD_JOBS=$jobs $cmd" },
        ),
        // GNU make / make 系（含 -j 并发）
        Rule(
            detect = Regex("""(^|[;&|]\s*)(make|gmake)(\s|$)"""),
            hasExplicitJobs = Regex("""\s(-j|--jobs)\s*\d*"""),
            inject = { cmd, jobs -> "$cmd -j$jobs" },
        ),
        // Gradle：用 --max-workers 限制 worker 数（比 -Dorg.gradle.workers.max 更直观）
        Rule(
            detect = Regex("""(^|[;&|]\s*)(\./)?gradlew?\b|(^|[;&|]\s*)gradle\b"""),
            hasExplicitJobs = Regex("""--max-workers"""),
            inject = { cmd, jobs -> "$cmd --max-workers=$jobs" },
        ),
        // tsc / esbuild 等 Node 构建：限制老生代内存上限（按并行度折算，每条 worker 约 1GB）
        Rule(
            detect = Regex("""\b(tsc|webpack|vite\s+build|esbuild)\b"""),
            hasExplicitJobs = Regex("""--max-old-space-size"""),
            inject = { cmd, jobs -> "NODE_OPTIONS=--max-old-space-size=${jobs * 1024} $cmd" },
        ),
    )

    /**
     * 结果：是否改写过、改写后命令、命中说明（供日志与模型知晓）。
     */
    data class Result(
        val command: String,
        val note: String? = null,
    ) {
        val rewritten: Boolean get() = note != null
    }

    /**
     * 对命令施加内存保护。非构建命令或已显式指定并行度时原样返回。
     *
     * @param jobs 并行度上限，默认 [DEFAULT_JOBS]。
     */
    fun guard(command: String, jobs: Int = DEFAULT_JOBS): Result {
        val safeJobs = jobs.coerceAtLeast(1)
        for (rule in RULES) {
            if (!rule.detect.containsMatchIn(command)) continue
            // 已显式指定并行度：用户的明确选择优先，不覆盖
            if (rule.hasExplicitJobs.containsMatchIn(command)) return Result(command)
            val rewritten = rule.inject(command, safeJobs)
            return Result(
                rewritten,
                "已为构建命令限制并行度为 $safeJobs（容器内存计入 App，全核并行易触发系统低内存回收导致会话中断）"
            )
        }
        return Result(command)
    }
}
