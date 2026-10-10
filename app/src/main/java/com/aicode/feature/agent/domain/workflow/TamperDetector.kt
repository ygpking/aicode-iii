package com.aicode.feature.agent.domain.workflow

/**
 * 测试作弊检测：识别「为了让测试通过而篡改测试」的行为。
 *
 * 移植自 EricFinland/proof 的 `proofkit/tamper.py`（MIT）。原实现有 6 条规则，
 * 本文件保留同样 6 条并按 Kotlin/Android 项目习惯调整文件识别：
 * 删测试文件、删测试用例、加 skip、加 only/focus、断言被削弱、测试命令被改坏。
 *
 * ## 为什么需要
 *
 * 证据守卫只看「有没有跑过测试」，查不出「测试被改到必过」。这类作弊有客观特征
 * （删掉断言、加 skip、把测试命令改成恒真），在 diff 上判得出来，故不必理解语义。
 *
 * ## 判据来源
 *
 * 只做**同一次请求内**的前后对比：调用方传入本回合写文件前后的内容，逐行比对。
 * 拿不到旧内容时（新文件、非本回合编辑）不判——宁可漏报，不可误报。
 *
 * ## 已知边界（如实声明，勿当成缺陷重提）
 *
 * - **经 shell `rm` / 删除工具整文件删除测试不在本管道内**：本检测只挂在
 *   `editFile`/`writeFile` 与构建配置写入上（见调用方 `StatefulAgentWorkflow`
 *   的证据记录处），`ToolRecord` 不挂删除类工具。文件整删只能由别的手段发现（如后续
 *   跑测试时用例数减少）。
 * - **同批多个工具写同一测试文件**：若后一个调用删了文件，写后回读为 null，
 *   该次不判（漏报方向）。
 */
internal object TamperDetector {

    /** 测试文件识别：Android/Kotlin 项目 + 通用约定。 */
    private val TEST_PATH_RE = Regex(
        "(^|/)(?:test|tests|androidTest|__tests__|spec|specs)/" +
            "|(^|/)test_[^/]*\\.\\w+$" +
            "|_test\\.\\w+$" +
            "|\\.(?:test|spec)\\.[cm]?[jt]sx?$" +
            "|(^|/)conftest\\.py$",
        RegexOption.IGNORE_CASE,
    )

    /** 测试用例定义（去掉后即为「删测试」）。 */
    private val TEST_DEF_RE = Regex(
        "^\\s*(?:@Test\\b.*|@ParameterizedTest\\b.*" +
            "|fun\\s+`[^`]+`\\s*\\(|fun\\s+\\w+\\s*\\(\\s*\\)\\s*\\{" +
            "|(?:async\\s+)?def\\s+test\\w*\\s*\\(|(?:it|test)\\s*\\(\\s*['\"`]" +
            "|func\\s+Test\\w*\\s*\\(|#\\[(?:tokio::)?test\\])",
        RegexOption.MULTILINE,
    )

    /** 跳过测试的写法。 */
    private val SKIP_RE = Regex(
        "@Ignore\\b|@Disabled\\b|pytest\\.mark\\.(?:skip|xfail)\\b|pytest\\.(?:skip|xfail)\\s*\\(" +
            "|@unittest\\.(?:skip|expectedFailure)\\b|self\\.skipTest\\s*\\(" +
            "|\\b(?:it|describe|test|context)\\.skip(?:\\.\\w+)?\\s*\\(|\\bx(?:it|describe|test)\\s*\\(" +
            "|\\bthis\\.skip\\s*\\(|\\btest\\.fixme\\s*\\(|\\bt\\.Skip(?:f|Now)?\\s*\\(|#\\[ignore\\b",
    )

    /** 只跑某个用例（其余被静默跳过）。 */
    private val ONLY_RE = Regex(
        "\\b(?:it|describe|test|context)\\.only\\s*\\(|(?<![\\w.])f(?:it|describe)\\s*\\(\\s*['\"`]",
    )

    /** 断言语句。 */
    private val ASSERT_RE = Regex(
        "^\\s*assert\\b|\\bassert\\w*\\s*\\(|\\bexpect\\s*\\(|\\bself\\.assert\\w+\\s*\\(" +
            "|\\b(?:t|require|assert)\\.(?:Error|Fatal|Equal|NotEqual|True|False|Nil|NotNil|NoError|Contains)\\w*\\s*\\(" +
            "|\\bassert(?:_eq|_ne)?!\\s*\\(|\\braises\\s*\\(|\\.toThrow\\w*\\s*\\(|\\bassert\\.throws\\s*\\(" +
            // Kotlin 侧：断言（含 Truth/AssertJ/JUnit）
            "|\\b(?:assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertThrows|assertThat|assertFailsWith)\\s*\\(" +
            "|\\bexpectThat\\s*\\(|\\bTruth\\.assertThat\\s*\\(",
        RegexOption.MULTILINE,
    )

    /** 恒真的「断言」（凑数用，不算真断言）。 */
    private val TRIVIAL_ASSERT_RE = Regex(
        "^\\s*assert\\s+(?:True|1)\\s*(?:#.*)?$|expect\\(\\s*true\\s*\\)\\.toBe\\(\\s*true\\s*\\)" +
            "|assert\\.ok\\(\\s*true\\s*\\)|self\\.assertTrue\\(\\s*True\\s*\\)|^\\s*assert!\\(\\s*true\\s*\\)" +
            "|assertEquals\\(\\s*true\\s*,\\s*true\\s*\\)|assertTrue\\(\\s*true\\s*\\)",
        RegexOption.MULTILINE,
    )

    /** 把测试命令改成必过：`|| true`、`exit 0`、`--passWithNoTests`。 */
    private val NEUTER_RE = Regex(
        "\\|\\|\\s*true\\b|(?:^|[;&|\\s])exit\\s+0\\b|--passWithNoTests",
    )

    /**
     * 构建/测试**配置文件**里的禁用测试写法（对照 proof `tamper.py` 的 `_neuter_hit`，
     * 它查 package.json / Makefile / pytest 配置；Android 侧对应 build.gradle.kts）。
     *
     * 这类改动让测试恒过而不动任何测试代码，是仅看测试文件抓不到的作弊路径。
     */
    private val GRADLE_TEST_DISABLE_RE = Regex(
        """isIgnoreFailures\s*=\s*true""" +
            """|withType<Test>\s*\{[^}]{0,200}enabled\s*=\s*false""" +
            """|tasks\.withType<Test>[^\n]*enabled\s*=\s*false""" +
            """|exclude\s*\(\s*"\*/\*"\s*\)""" +
            """|ignoreFailures\s*=\s*true""",
        RegexOption.MULTILINE,
    )

    /** 哪些文件属于「构建/测试配置」（其内容被改坏即视为作弊）。 */
    private val BUILD_CONFIG_RE = Regex(
        "(^|/)(?:build\\.gradle\\.kts|build\\.gradle|settings\\.gradle\\.kts|package\\.json|Makefile|pytest\\.ini|tox\\.ini|setup\\.cfg|pyproject\\.toml)$",
    )

    /** 该路径是否看起来是构建/测试配置。 */
    fun looksLikeBuildConfig(path: String): Boolean = BUILD_CONFIG_RE.containsMatchIn(path)

    private fun isTestPath(path: String): Boolean = TEST_PATH_RE.containsMatchIn(path)

    /** 该路径是否看起来是测试文件（供调用方决定要不要抓旧内容做比对）。 */
    fun looksLikeTestPath(path: String): Boolean = isTestPath(path)

    private fun codeLines(text: String): List<String> =
        stripStringLiterals(text).lines().filter { !isCommentLine(it) }

    /**
     * 把字符串字面量的内容替换为空格（保留换行与其余结构）。
     *
     * 断言计数必须看出字符串之外的东西：本守卫自己的测试文件就用
     * `"... assertTrue(true) ..."` 这种字符串当语料（测作弊检测用），
     * 按行正则会把语料算成真断言而误报（真机已实咬：改该测试文件必被拉回一轮）。
     * 对普通字符串与 `"""` 多行字符串都处理，转义不出界。
     */
    private fun stripStringLiterals(text: String): String {
        if (!text.contains('"')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    sb.append("   ")
                    i += 3
                    while (i < text.length) {
                        if (text.startsWith("\"\"\"", i)) {
                            sb.append("   ")
                            i += 3
                            break
                        }
                        sb.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                text[i] == '"' -> {
                    sb.append(' ')
                    i++
                    while (i < text.length && text[i] != '"') {
                        // 反斜杠转义：跳过后一个字符，避免 `\"` 被当成字符串结束
                        if (text[i] == '\\' && i + 1 < text.length) i++
                        sb.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        sb.append(' ')
                        i++
                    }
                }
                else -> {
                    sb.append(text[i])
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** 整行注释与空行不计入比对（注释掉测试不算删除测试，但也不算保留）。 */
    private fun isCommentLine(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return true
        if (s.startsWith("//") || s.startsWith("/*") || s.startsWith("*") || s.startsWith("<!--")) return true
        return s.startsWith("#") && !s.startsWith("#[")
    }

    /**
     * 核对一次文件写入是否构成作弊。
     *
     * @param path 被写的文件路径
     * @param before 写入前的内容；新文件传 null（不判，避免误报）
     * @param after 写入后的内容
     * @return 违规说明；空表示未发现作弊特征
     */
    fun inspect(path: String, before: String?, after: String): List<String> {
        val findings = mutableListOf<String>()

        // 删测试文件：原先是测试文件，现在为空或只剩注释
        if (isTestPath(path) && before != null) {
            if (codeLines(after).isEmpty() && codeLines(before).isNotEmpty()) {
                findings += "测试文件 `$path` 的全部用例被清空"
                return findings
            }
        }

        val oldLines = codeLines(before ?: "")
        val newLines = codeLines(after)

        if (isTestPath(path) && before != null) {
            // 删测试用例
            val removedDefs = oldLines.count { TEST_DEF_RE.containsMatchIn(it) } -
                newLines.count { TEST_DEF_RE.containsMatchIn(it) }
            if (removedDefs > 0) findings += "测试文件 `$path` 删除了 $removedDefs 个用例"

            // 加 skip
            val addedSkips = newLines.count { SKIP_RE.containsMatchIn(it) } -
                oldLines.count { SKIP_RE.containsMatchIn(it) }
            if (addedSkips > 0) findings += "测试文件 `$path` 新增 $addedSkips 处跳过标记（@Ignore/@Disabled/skip）"

            // 加 only
            val addedOnly = newLines.count { ONLY_RE.containsMatchIn(it) } -
                oldLines.count { ONLY_RE.containsMatchIn(it) }
            if (addedOnly > 0) findings += "测试文件 `$path` 新增 $addedOnly 处独占运行标记（.only）"
        }

        // 断言被削弱。两条分支（对照原版 `_gutted`）：
        //   ① 新增行里出现恒真断言——向原本零断言的测试文件插入 `assertTrue(true)` 也检出；
        //   ② 有效断言数减少。
        // 用计数差而非「是否存在」：旧文件已有同文本行时，新增同文本行同样要报（旧 1 新 2）。
        val addedTrivialCount = newLines.count { TRIVIAL_ASSERT_RE.containsMatchIn(it) } -
            oldLines.count { TRIVIAL_ASSERT_RE.containsMatchIn(it) }
        if (addedTrivialCount > 0 && isTestPath(path)) {
            findings += "`$path` 新增了 $addedTrivialCount 处恒真断言（不能验证任何行为）"
        }

        val oldAsserts = oldLines.count { ASSERT_RE.containsMatchIn(it) }
        val newAsserts = newLines.count { ASSERT_RE.containsMatchIn(it) }
        val oldTrivial = oldLines.count { TRIVIAL_ASSERT_RE.containsMatchIn(it) }
        val newTrivial = newLines.count { TRIVIAL_ASSERT_RE.containsMatchIn(it) }
        if (oldAsserts > 0 && oldTrivial < oldAsserts) {
            val realOld = oldAsserts - oldTrivial
            val realNew = newAsserts - newTrivial
            if (realNew == 0 && realOld > 0) {
                findings += "`$path` 的真实断言全部被移除（只剩恒真断言或无断言）"
            } else if (realNew < realOld) {
                findings += "`$path` 的有效断言从 $realOld 条减到 $realNew 条"
            }
        }

        return findings
    }

    /**
     * 核对一次命令执行是否把测试命令改坏（如 `... || true`、`exit 0`、`--passWithNoTests`）。
     * 只在命令本身是测试/构建类命令时判。
     */
    fun inspectCommand(command: String, isVerifyCommand: Boolean): List<String> {
        if (!isVerifyCommand) return emptyList()
        return if (NEUTER_RE.containsMatchIn(command)) {
            listOf("验证命令被改成恒真（`|| true` / `exit 0` / `--passWithNoTests`），其结果不构成凭证")
        } else {
            emptyList()
        }
    }

    /**
     * 核对一次对**构建配置**的写入是否禁用了测试（`isIgnoreFailures = true`、
     * `enabled = false` 等）。这类改动让测试恒过却不碰测试代码。
     */
    fun inspectBuildConfig(path: String, after: String): List<String> {
        if (!looksLikeBuildConfig(path)) return emptyList()
        // 先剔字符串字面量：配置里也可能内嵌含有禁用写法的字符串语料（本仓库测试中就有）。
        val body = stripStringLiterals(after)
        return if (GRADLE_TEST_DISABLE_RE.containsMatchIn(body)) {
            listOf("构建配置 `$path` 里出现禁用/忽略测试失败的设置（让测试恒过而不修问题）")
        } else {
            emptyList()
        }
    }
}
