package com.aicode.feature.agent.domain.workflow

/**
 * 构建类命令识别：判断一条 shell 命令是否会启动高开销的构建/测试进程。
 *
 * 用途与 [com.aicode.feature.agent.domain.container.ContainerBuildGuard] **不同源**，不可合并：
 * 后者的目的是「给高内存构建注入并行度上限」，其规则表只收录**能安全注入** `-j` /
 * `--max-workers` 的工具（cargo/make/gradle/tsc），不含 Go、npm 这类注入方式不同的命令。
 * 本函数只回答「要不要串行」，不参与命令改写，故覆盖面更宽。
 *
 * 判定基于**命令段的起点**而非整串 contains：
 * `grep -rn gradle app/` 里出现过 `gradle` 却不是构建，只有把命令按 `&&`/`;`/`|`
 * 切开、看每段的实际程序名，才能把「提及」与「执行」分开。
 *
 * 误判方向安全：把非构建误判为构建只会多排一次队（变慢）；
 * 把真实构建漏判才会让两个会话争抢同一构建守护进程（互相破坏，实测踩过）。
 */
internal object BuildCommandDetector {

    /** 命令分隔符：shell 的串接与管道。 */
    private val SEGMENT_SPLIT = Regex("""(?:&&|\|\||;|\||\n)""")

    /** 段首可出现的包装命令，剥掉后看真正的程序名。 */
    private val WRAPPER = Regex("""^(?:sudo|env|nohup|time|command|exec|sh|bash|zsh)\s+""")

    /** 环境变量赋值前缀：`FOO=bar cmd`。 */
    private val ENV_ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*\s+""")

    /** 段首的 `cd <path>`，其后往往没有真正的命令，一并剥掉。 */
    private val CD_PREFIX = Regex("""^cd\s+\S+\s*""")

    /** 本身就是构建/测试的工具：出现即判定为构建，无需再看动作词。 */
    private val INHERENT_TOOLS = setOf(
        "gradlew", "gradle", "mvn", "mvnw", "bazel", "ninja", "cmake", "make", "gmake",
        "cargo", "rustc", "tsc", "webpack", "pytest", "tox",
    )

    /** 视子命令而定的工具：`npm install` 是构建，`npm --version` 不是。 */
    private val CONDITIONAL_TOOLS = setOf("go", "npm", "pnpm", "yarn", "npx", "vite", "next")

    /** 与 [CONDITIONAL_TOOLS] 同时成立才算构建的动作词。 */
    private val BUILD_ACTION = Regex("""\b(?:build|assemble|compile|package|bundle|test|install|dist|release|lint|run|ci)\b""")

    /** make 系的干跑标志：`make -n` 只打印命令不执行，不启动构建。 */
    private val MAKE_DRY_RUN = Regex("""(?:^|\s)(?:-n|--dry-run|--just-print|--recon)\b""")

    /** 干跑不产生构建的 make 系工具。 */
    private val MAKE_TOOLS = setOf("make", "gmake")

    /** 内部会调构建脚本的入口（workbuddy 项目实测教训：直接跑脚本也会争抢构建）。 */
    private val BUILD_SCRIPT = Regex("""(?:^|[\s/])(?:[\w-]*build-all|[\w-]*build-apk|[\w-]*gradle-build)\.[a-z]+""")

    private fun programOf(segment: String): String {
        var s = segment.trim()
        // 反复剥离包装与环境变量前缀：`sudo env FOO=1 sh gradlew` 这类叠加也成立
        var changed = true
        while (changed) {
            changed = false
            for (prefix in listOf(WRAPPER, ENV_ASSIGN)) {
                val m = prefix.find(s)
                if (m != null && m.range.first == 0) {
                    s = s.substring(m.range.last + 1).trim()
                    changed = true
                }
            }
        }
        val cd = CD_PREFIX.find(s)
        if (cd != null && cd.range.first == 0) s = s.substring(cd.range.last + 1).trim()
        if (s.isEmpty()) return ""
        return s.substringBefore(' ').removePrefix("./").substringAfterLast('/')
    }

    /**
     * 该命令是否应按构建类串行。
     */
    fun isBuildCommand(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        if (BUILD_SCRIPT.containsMatchIn(command)) return true
        for (segment in SEGMENT_SPLIT.split(command)) {
            val program = programOf(segment)
            if (program.isEmpty()) continue
            if (program in MAKE_TOOLS && MAKE_DRY_RUN.containsMatchIn(segment)) continue
            if (program in INHERENT_TOOLS) return true
            if (program in CONDITIONAL_TOOLS && BUILD_ACTION.containsMatchIn(segment)) return true
        }
        return false
    }
}
