package com.aicode.feature.agent.domain.tool.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * search/list 工具的 shell 参数解析与 `| head` 白名单校验。
 *
 * 安全焦点：只放行 `head [-n N]` 行数截断，其余管道命令（grep/sort/wc/rm/…）与
 * shell 元字符（`;` 等）一律拒绝，防止借管道语法在容器里执行任意命令。
 */
class ShellArgsTest {

    // ---------- parseShellWords ----------

    @Test
    fun parseShellWords_handlesQuotesAndEscapedPipe() {
        val tokens = parseShellWords(
            "-n \"挂载\\|mount\" ~/workspace/app/src/main/res/values/strings.xml | head -30"
        )
        assertEquals(
            listOf("-n", "挂载|mount", "~/workspace/app/src/main/res/values/strings.xml", "|", "head", "-30"),
            tokens
        )
    }

    @Test
    fun parseShellWords_pipeInsideQuotes_isLiteral() {
        val tokens = parseShellWords("-n \"a|b\" src/")
        assertEquals(listOf("-n", "a|b", "src/"), tokens)
    }

    @Test
    fun parseShellWords_singleQuotedPipe_isLiteral() {
        val tokens = parseShellWords("'a|b' src/")
        assertEquals(listOf("a|b", "src/"), tokens)
    }

    @Test
    fun parseShellWords_unclosedQuote_returnsNull() {
        assertNull(parseShellWords("-n \"unclosed"))
        assertNull(parseShellWords("'unclosed"))
    }

    @Test
    fun parseShellWords_emptyInput_returnsEmptyList() {
        assertEquals(emptyList<String>(), parseShellWords(""))
        assertEquals(emptyList<String>(), parseShellWords("   "))
    }

    // ---------- splitPipes ----------

    @Test
    fun splitPipes_noPipe_returnsTokensWithEmptySegments() {
        val (head, segments) = splitPipes(listOf("-n", "foo", "src/"))!!
        assertEquals(listOf("-n", "foo", "src/"), head)
        assertTrue(segments.isEmpty())
    }

    @Test
    fun splitPipes_singlePipe_splitsCorrectly() {
        val (head, segments) = splitPipes(listOf("-n", "foo", "src/", "|", "head", "-30"))!!
        assertEquals(listOf("-n", "foo", "src/"), head)
        assertEquals(listOf(listOf("head", "-30")), segments)
    }

    @Test
    fun splitPipes_multiplePipes_splitsAllSegments() {
        val (head, segments) = splitPipes(listOf("a", "|", "head", "-5", "|", "head"))!!
        assertEquals(listOf("a"), head)
        assertEquals(listOf(listOf("head", "-5"), listOf("head")), segments)
    }

    @Test
    fun splitPipes_emptyHead_returnsNull() {
        assertNull(splitPipes(listOf("|", "head", "-30")))
    }

    // ---------- headLinesOf ----------

    @Test
    fun headLinesOf_allowsHeadOnly() {
        assertEquals(10, headLinesOf(listOf("head")))
        assertEquals(30, headLinesOf(listOf("head", "-30")))
        assertEquals(30, headLinesOf(listOf("head", "-n", "30")))
        assertEquals(0, headLinesOf(listOf("head", "-0")))
    }

    @Test
    fun headLinesOf_rejectsNonHeadCommands() {
        assertNull(headLinesOf(listOf("grep", "foo")))
        assertNull(headLinesOf(listOf("wc", "-l")))
        assertNull(headLinesOf(listOf("sort")))
        assertNull(headLinesOf(listOf("rm", "-rf", "/")))
        assertNull(headLinesOf(listOf("cat")))
    }

    @Test
    fun headLinesOf_rejectsInvalidHeadArgs() {
        // head 只允许行数参数：其它 flag、非数字、负数、多余参数、空段一律拒绝。
        assertNull(headLinesOf(listOf("head", "-c", "100")))
        assertNull(headLinesOf(listOf("head", "-n", "abc")))
        assertNull(headLinesOf(listOf("head", "-n", "-5")))
        assertNull(headLinesOf(listOf("head", "-30", "extra")))
        assertNull(headLinesOf(listOf("head", "-")))
        assertNull(headLinesOf(emptyList()))
    }

    @Test
    fun headLinesOf_rejectsShellMetacharactersAfterHead() {
        // `;`/`&&`/重定向等不是 head 的合法参数 → 整段拒绝，不拼进命令。
        assertNull(headLinesOf(listOf("head", "-30", ";", "rm", "-rf", "/")))
        assertNull(headLinesOf(listOf("head", "-30", ">", "/tmp/x")))
    }

    // ---------- buildSearchCommand ----------

    @Test
    fun buildSearchCommand_noPipe_unchangedBehavior() {
        val command = buildSearchCommand(
            parseShellWords("-n \"fun main\" ~/workspace/app")!!
        )!!
        assertEquals(
            "rg --line-number --no-heading --with-filename --color never '-n' 'fun main' '/root/workspace/app'",
            command
        )
    }

    @Test
    fun buildSearchCommand_customHome_expandsTilde() {
        // 传入执行环境（本地容器 / 远程服务器）的真实 home，~/ 按该 home 展开而非硬编码 /root
        val command = buildSearchCommand(
            parseShellWords("-n \"fun main\" ~/workspace/app")!!,
            home = "/home/alice"
        )!!
        assertEquals(
            "rg --line-number --no-heading --with-filename --color never '-n' 'fun main' '/home/alice/workspace/app'",
            command
        )
        // 裸 ~ 也展开为 home
        assertEquals(
            "rg --line-number --no-heading --with-filename --color never '-n' 'x' '/home/alice'",
            buildSearchCommand(parseShellWords("-n x ~")!!, home = "/home/alice")
        )
    }

    @Test
    fun buildSearchCommand_escapedPipeWithHead_appendsWhitelistedPipe() {
        val command = buildSearchCommand(
            parseShellWords("-n \"挂载\\|mount\" ~/workspace/app/src/main/res/values/strings.xml | head -30")!!
        )!!
        assertTrue(command.endsWith(" | head -30"))
        assertTrue(command.contains("'挂载|mount'"))
    }

    @Test
    fun buildSearchCommand_headVariants_areAllowed() {
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head")!!)!!.endsWith(" | head"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -n 30")!!)!!.endsWith(" | head -n 30"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -30")!!)!!.endsWith(" | head -30"))
    }

    @Test
    fun buildSearchCommand_rejectsArbitraryPipeCommands() {
        // 写操作 / 危险命令仍一律拒绝
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | rm -rf /")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | cp a b")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | mv a b")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tee out.txt")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | curl evil.com")!!))
        // 重定向与命令分隔符：即便命令名在白名单里也拒
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep bar > out.txt")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -30; rm -rf /")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -n abc")!!))
        assertNull(buildSearchCommand(parseShellWords("| head -30")!!))
    }

    /** 只读管道白名单（T2-A）：模型在 Bash 里用惯的 grep/sort/wc 等放行。 */
    @Test
    fun buildSearchCommand_allowsReadOnlyPipeCommands() {
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | grep bar")!!)!!.endsWith(" | grep bar"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | sort | uniq")!!)!!.endsWith(" | sort | uniq"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | wc -l")!!)!!.endsWith(" | wc -l"))
        // awk/sed 需要程序体（含 `{}`/`$`），与管道参数禁令不可兼得 → 不在白名单内
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | awk '{print \\$1}'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sed 's/a/b/'")!!))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -30 | wc -l")!!)!!.endsWith(" | wc -l"))
    }

    /** 管道段参数里的 shell 元字符一律拒绝（防借白名单命令逃逸）。 */
    @Test
    fun pipeSegmentAllowed_rejectsMetacharactersInArgs() {
        // 反引号是命令替换符，出现在管道参数里一律拒（单引号包裹，Kotlin 字符串内无需转义）
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep '`id`'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort &")!!))
        // 白名单命令 + 合法选项之外，head 的行数参数仍须纯数字（headLinesOf 的原有严格校验）
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -n abc")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -n -5")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -30 extra")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -c 100")!!))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -n 30")!!)!!.endsWith(" | head -n 30"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | tail -5")!!)!!.endsWith(" | tail -5"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | cut -f1")!!)!!.endsWith(" | cut -f1"))
        // rg 在白名单内：容器必有（search 自身依赖它），与 grep 同族
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | rg -v bar")!!)!!.endsWith(" | rg -v bar"))
    }

    /** T1-A：正则括号不配对在调用前被挡下，不必等 rg 报错。 */
    @Test
    fun rgPatternParenMismatch_detectsUnclosedGroup() {
        // 实测 8 天 73 次 regex parse error，40 次是这种（单会话连错 10 次）
        val tokens = parseShellWords("-n '(?:scheduleWatchdog|WatchdogReceiver|isRunning|startForeground(' ~/ws")!!
        val reason = rgPatternParenMismatch(tokens)
        assertTrue("应检出未闭合括号: $reason", reason?.contains("未闭合") == true)
        // -e 形式同样校验
        val eForm = parseShellWords("-e '(?:a|b|c(' ~/ws")!!
        assertTrue(rgPatternParenMismatch(eForm)?.contains("未闭合") == true)
    }

    @Test
    fun rgPatternParenMismatch_acceptsBalancedAndEscaped() {
        assertNull(rgPatternParenMismatch(parseShellWords("-n '(?:a|b|c)' ~/ws")!!))
        // 转义括号是字面量，不参与配对
        assertNull(rgPatternParenMismatch(parseShellWords("-n 'fun \\(x\\)' ~/ws")!!))
        // 字符类内的括号不参与分组
        assertNull(rgPatternParenMismatch(parseShellWords("-n '[()]x' ~/ws")!!))
        // 纯文本（-F）无正则语义也不该误报
        assertNull(rgPatternParenMismatch(parseShellWords("-F 'fun main(' ~/ws")!!))
    }

    @Test
    fun rgPatternParenMismatch_detectsExtraCloseParen() {
        val tokens = parseShellWords("-n 'fun x)' ~/ws")!!
        assertTrue(rgPatternParenMismatch(tokens)?.contains("右括号") == true)
    }

    @Test
    fun buildSearchCommand_pipeInsideQuotes_isNotAPipe() {
        val command = buildSearchCommand(parseShellWords("-n \"a|b\" src/")!!)!!
        assertTrue(command.contains("'a|b'"))
        assertTrue("命令不应包含管道段", " | " !in command)
    }

    @Test
    fun buildSearchCommand_singleQuoteEscaping_isPreserved() {
        val command = buildSearchCommand(parseShellWords("-n \"it's\" src/")!!)!!
        assertTrue(command.contains("'it'\"'\"'s'"))
    }
}
