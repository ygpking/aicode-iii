package com.aicode.feature.agent.domain.tool.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // 管道段参数同样被引号包裹：`-30` 变成 '-30'
        assertTrue(command.endsWith(" | head '-30'"))
        assertTrue(command.contains("'挂载|mount'"))
    }

    @Test
    fun buildSearchCommand_headVariants_areAllowed() {
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head")!!)!!.endsWith(" | head"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -n 30")!!)!!.endsWith(" | head '-n' '30'"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -30")!!)!!.endsWith(" | head '-30'"))
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
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | grep bar")!!)!!.endsWith(" | grep 'bar'"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | sort | uniq")!!)!!.endsWith(" | sort | uniq"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | wc -l")!!)!!.endsWith(" | wc '-l'"))
        // awk/sed 需要程序体（含 `{}`/`$`），与管道参数禁令不可兼得 → 不在白名单内
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | awk '{print \\$1}'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sed 's/a/b/'")!!))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -30 | wc -l")!!)!!.endsWith(" | head '-30' | wc '-l'"))
    }

    /**
     * 管道段注入：token 内的 `|` / 换行 / 写文件选项必须挡在容器 shell 之外。
     *
     * 这三条是**实测可达**的绕过（不是理论风险）：pr-56 放宽白名单后管道段裸拼进命令，
     * `grep "x|touch /tmp/pwned"` 经 parseShellWords 合成单 token，拼出来的命令交给
     * bash -c 真的执行了 touch（marker 落盘）。修法是管道段也逐 token shellQuote，
     * 使引号内的 `|` 与换行退化成字面量。
     *
     * 断言口径：能安全放行的（字符全合法）必须**被引号包住**，让 `|` 与换行失去语义；
     * 字符本身非法的（重定向、命令替换、`$()`）仍走 assertNull。
     */
    @Test
    fun buildSearchCommand_rejectsPipeInjectionPayloads() {
        // `|`：放行，但必须被引号包成字面量（否则会真的执行 touch）
        val pipe = buildSearchCommand(parseShellWords("-n foo src/ | grep \"x|touch /tmp/pwned\"")!!)!!
        assertTrue("payload 须被引号包裹: $pipe", pipe.endsWith("grep 'x|touch /tmp/pwned'"))
        assertEquals("整条命令只应有一个裸管道符", 2, pipe.split(" | ").size)
        // 换行：一律拒。它是 shell 的命令分隔符，且作为 pattern 毫无意义
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep \"x\ntouch /tmp/pwned\"")!!))
        // 字符非法的（命令替换 / 变量展开 / 重定向 / 后台）一律拒
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep 'x\$(id)'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep 'x`id`'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep bar > out.txt")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort &")!!))
        // 命令自己的写文件 / 执行 / 挂起能力
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort -o /tmp/pwned")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tail -f")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep -f /etc/passwd")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | rg --pre 'id'")!!))
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
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -n 30")!!)!!.endsWith(" | head '-n' '30'"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | tail -5")!!)!!.endsWith(" | tail '-5'"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | cut -f1")!!)!!.endsWith(" | cut '-f1'"))
        // rg 在白名单内：容器必有（search 自身依赖它），与 grep 同族
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | rg -v bar")!!)!!.endsWith(" | rg '-v' 'bar'"))
    }

    /** 管道段拼进命令前逐 token 引号包裹：token 内的 `|` 不得重新变成管道符。 */
    @Test
    fun buildSearchCommand_quotesPipeSegmentArgs() {
        // 含 `|` 的 pattern 作为单 token 传入，拼出来的命令里它必须被引号包住
        val command = buildSearchCommand(parseShellWords("-n foo src/ | grep 'a|b'")!!)
        assertNotNull(command)
        assertTrue("管道段参数须被引号包裹: $command", command!!.contains("grep 'a|b'"))
        assertTrue("整条命令只应有一个裸管道符", command.split(" | ").size == 2)
        // 参数含空格同样被引号包住，不会被拆成两个词
        val spaced = buildSearchCommand(parseShellWords("-n foo src/ | grep -v 'LinuxContainerEngine|rg --line-number'")!!)!!
        assertTrue("含空格的 pattern 须整体引号包裹: $spaced", spaced.endsWith("grep '-v' 'LinuxContainerEngine|rg --line-number'"))
    }

    /**
     * 命令自身的内建能力必须被选项白名单挡住——引号包裹对这些**无效**。
     *
     * 每条都实测过（真跑 + 看 marker 是否落盘）：awk `system()` 与 sed `e` 能执行任意命令，
     * sed `w` 与 `sort -o` 能写文件，`tail -f` 会挂住，`grep -f` 会从文件读 pattern。
     * 覆盖长选项等号形式与合并短选项两种易漏写法。
     */
    @Test
    fun buildSearchCommand_rejectsCommandBuiltinCapabilities() {
        // 需要程序体的命令：唯一有价值的用法就携带执行能力，与只读约束不可兼得
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | awk 'BEGIN{system(\"id\")}'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sed 'e id'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sed 's/a/b/w /tmp/x'")!!))
        // 写文件：短选项、长选项、长选项等号形式三种写法都要挡
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort -o /tmp/x")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort --output /tmp/x")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort --output=/tmp/x")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort -T /tmp")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tr -o /tmp/x")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | cut -o /tmp/x")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | nl -o /tmp/x")!!))
        // 执行外部程序
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort --compress-program='id'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | sort --compress-program id")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | rg --pre 'id'")!!))
        // 挂起 / 等进程
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tail -f")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tail --follow")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tail --retry")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | tail --pid=1")!!))
        // 从文件读 pattern / 文件名清单（绕过字符集）
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep -f /etc/hostname")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep --file=/etc/hostname")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | rg -f /etc/hostname")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | wc --files0-from=/etc/hostname")!!))
        // 合并短选项拆开后含危险项：grep 的 -f 是「从文件读 pattern」
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep -of /etc/hostname")!!))
        // 命令名不在只读白名单内
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | xargs rm")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | bash -c 'id'")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | perl -e 'system(\"id\")'")!!))
        // 反向确认：只读语义的同名短选项仍放行（-o 是 only-matching，-F 是 fixed-strings）
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | grep -o bar")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | grep -F 'a.b'")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | sort -f")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | sort -rn")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | uniq -cd")!!))
        // 合并短选项 + 数字值：`-f1` = `-f 1`，数字是选项值不是选项
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | cut -f1")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | cut -d: -f1,3")!!))
        // 反例：合并短选项里混入危险项仍须拒
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep -of /etc/hostname")!!))
        // 长选项等号形式：合法选项须放行（判据取 `=` 前的选项名，不是整个 `--opt=val`）
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | sort --field-separator=: -k2")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | grep --color=never bar")!!))
        assertNotNull(buildSearchCommand(parseShellWords("-n foo src/ | rg --max-count=5 bar")!!))
    }

    /** 超范围行数不得抛异常（toIntOrNull），应走 INVALID_PIPE 而非兜底的 SEARCH_FAILED。 */
    @Test
    fun headLinesOf_oversizedLineCount_returnsNullNotException() {
        assertNull(headLinesOf(listOf("head", "-n", "99999999999")))
        assertNull(headLinesOf(listOf("head", "-99999999999")))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -n 99999999999")!!))
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
