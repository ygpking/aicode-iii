package com.aicode.feature.agent.domain.tool.explorer

/**
 * explorer 工具（search/list）共用的 shell 参数解析与安全白名单逻辑。
 *
 * 模型常把 `| head -30` 这类 shell 管道直接写进工具 args（如 `search(args="-n \"foo\" src | head -30")`）。
 * 这里把 `|` 从 rg/ls 参数中分离出来单独解析：**只放行 head 行数截断**，其余管道命令一律拒绝，
 * 防止借管道语法在容器里执行任意命令。
 */

/** 按 shell 词法把 args 拆成 tokens；引号未闭合返回 null。`\|`/`|` 解析为独立 token `|`，引号内的 `|` 是字面量。 */
fun parseShellWords(input: String): List<String>? {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var tokenStarted = false
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            quote == '\'' -> {
                if (c == '\'') quote = null else current.append(c)
                tokenStarted = true
            }
            quote == '"' -> {
                when (c) {
                    '"' -> quote = null
                    '\\' -> {
                        if (i + 1 < input.length) {
                            i++
                            current.append(input[i])
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
                tokenStarted = true
            }
            c.isWhitespace() -> {
                if (tokenStarted) {
                    result.add(current.toString())
                    current.clear()
                    tokenStarted = false
                }
            }
            c == '\'' || c == '"' -> {
                quote = c
                tokenStarted = true
            }
            c == '\\' -> {
                if (i + 1 < input.length) {
                    i++
                    current.append(input[i])
                } else {
                    current.append(c)
                }
                tokenStarted = true
            }
            else -> {
                current.append(c)
                tokenStarted = true
            }
        }
        i++
    }
    if (quote != null) return null
    if (tokenStarted) result.add(current.toString())
    return result
}

/**
 * 把 tokens 按独立的 `|` 拆为 (管道前的 tokens, 管道段列表)。
 * 无管道返回 (tokens, 空列表)；`|` 前没有任何参数返回 null。
 * 引号内（如 `"a|b"`）的 `|` 属于 token 本身，不会触发拆分。
 */
fun splitPipes(tokens: List<String>): Pair<List<String>, List<List<String>>>? {
    val pipeIndex = tokens.indexOf("|")
    if (pipeIndex < 0) return tokens to emptyList()
    val headTokens = tokens.subList(0, pipeIndex)
    if (headTokens.isEmpty()) return null
    val segments = mutableListOf<List<String>>()
    var i = pipeIndex
    while (i < tokens.size) {
        if (tokens[i] != "|") return null
        i++
        val segStart = i
        while (i < tokens.size && tokens[i] != "|") i++
        segments += tokens.subList(segStart, i)
    }
    return headTokens to segments
}

/**
 * head 管道段白名单：仅 `head`（默认 10 行）、`head -N`、`head -n N`，N 必须纯数字。
 * 返回该段允许输出的行数；命令不是 head、或带其它参数（含 `;`、重定向、`head -c` 等）返回 null。
 */
fun headLinesOf(seg: List<String>): Int? = when {
    seg.isEmpty() -> null
    seg[0] != "head" -> null
    seg.size == 1 -> 10
    seg.size == 2 -> {
        val n = seg[1]
        if (n.length > 1 && n[0] == '-' && n.drop(1).all(Char::isDigit)) n.drop(1).toInt() else null
    }
    seg.size == 3 && seg[1] == "-n" -> {
        val n = seg[2]
        if (n.isNotEmpty() && n.all(Char::isDigit)) n.toInt() else null
    }
    else -> null
}

/**
 * 只读管道白名单：这些命令只做「读入→加工→写出 stdout」，不改文件、不发网络，
 * 故允许接在 rg/ls 之后。写操作管道（`>`、`tee`、`install`、`cp`、`mv` 等）一律拒绝。
 *
 * 放行依据：8 天日志里模型在 Bash 中自发使用 `| grep` 3,399 次、`| sort` 419 次、
 * `| wc` 432 次、`| awk` 198 次——习惯已成定局，而 search/list 只允许 `| head`，
 * 导致 59 次 INVALID_PIPE 白失败。这些是纯只读后处理，没有借管道逃逸执行的风险。
 */
private val READ_ONLY_PIPE_COMMANDS: Set<String> =
    setOf("head", "tail", "grep", "sort", "uniq", "wc", "cut", "tr", "awk", "sed", "nl", "rev", "tac")

/**
 * 管道段参数是否安全：拒绝重定向、命令分隔、以及任何「会写文件」的符号。
 * 只允许短选项（-n/-c/-v/-r/-u/-f/-k 等）与数字/普通字符串字面量。
 */
private fun pipeSegmentArgsSafe(seg: List<String>): Boolean {
    // seg[0] 是命令名，其余是参数
    for (arg in seg.drop(1)) {
        if (arg.isEmpty()) return false
        // 重定向 / 命令分隔 / 后台 / 进程替换
        if (arg.any { it in "<>`&;(){}" }) return false
        if (arg == "|" || arg == ">>" || arg == ">" || arg == "<") return false
    }
    return true
}

/**
 * 管道段是否可放行：命令在只读白名单内，且参数不含重定向/分隔符。
 * 返回该段是否需要特殊处理（当前统一返回 true/false，预留行数能力）。
 */
fun pipeSegmentAllowed(seg: List<String>): Boolean =
    seg.isNotEmpty() && seg[0] in READ_ONLY_PIPE_COMMANDS && pipeSegmentArgsSafe(seg)

/**
 * 正则括号配对预检（T1-A）。
 *
 * 模型常写 `-e '(?:a|b|c('` 这类括号不配对的正则：rg 到容器里才报 regex parse error，
 * 一次工具调用白烧（8 天 73 次，其中 40 次括号不配对；单会话连错 10 次）。
 * 这里在**调用前**用纯本地校验挡住，返回错误描述；配对正确返回 null。
 *
 * 只校验正则来源：`-e`/`--regexp` 的值与未带选项前缀的裸词。
 * `\(`/`\)` 是字面量括号，不参与配对；`(?`、`(?:`、`(` 都算开启一个组；
 * `[...]` 字符类内的括号不参与分组。
 * 带 `-F`/`--fixed-strings`（纯文本模式）时**不校验**——那时括号就是普通字符。
 * 宁可漏判（交给 rg 报错）也不误判——把合法正则挡下会让搜索静默失败。
 */
fun rgPatternParenMismatch(tokens: List<String>): String? {
    // -F/--fixed-strings：纯文本模式，括号无正则语义
    if (tokens.any { it == "-F" || it == "--fixed-strings" || it == "--fixed" }) return null

    // 这些选项的值不是正则，连同选项一起跳过
    val VALUE_OPTIONS = setOf("-g", "--glob", "-t", "--type", "-A", "-B", "-C",
        "--after-context", "--before-context", "--context", "-M", "--max-columns",
        "--max-count", "-m", "--iglob", "--type-add", "--colors", "--encoding",
        "--pre", "--hostname", "-j", "--threads", "--sort", "--sortr")

    val patterns = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        when {
            t == "-e" || t == "--regexp" -> {
                if (i + 1 < tokens.size) patterns += tokens[i + 1]
                i += 2
                continue
            }
            t.startsWith("-e") && t.length > 2 -> patterns += t.removePrefix("-e")
            t.startsWith("--regexp=") -> patterns += t.removePrefix("--regexp=")
            t in VALUE_OPTIONS -> { i += 2; continue }   // 选项 + 它的值都不是正则
            t.startsWith("-") -> { /* 布尔开关（-i/-n/-w/-v/--hidden 等）跳过 */ }
            else -> patterns += t
        }
        i++
    }
    for (p in patterns) {
        var depth = 0
        var k = 0
        var inClass = false   // [...] 字符类内的括号不参与分组
        while (k < p.length) {
            val c = p[k]
            when {
                c == '\\' -> k += 2
                inClass -> if (c == ']') inClass = false else k++
                c == '[' -> { inClass = true; k++ }
                c == '(' -> { depth++; k++ }
                c == ')' -> { depth--; k++; if (depth < 0) return "正则含未匹配的右括号 ')'：$p" }
                else -> k++
            }
            if (depth < 0) return "正则含未匹配的右括号 ')'：$p"
        }
        if (depth > 0) {
            return "正则有 $depth 个未闭合的 '('：$p——分组括号需成对；" +
                "纯文本搜索改用 -F（关闭正则）可彻底避开转义问题"
        }
    }
    return null
}

/**
 * 组装传给容器的 rg 搜索命令：基础参数 + 全部参数 token（单引号转义、`~/` 展开为 [home]），
 * 若带管道则原样追加白名单校验过的管道段。管道段含非只读命令/含重定向时返回 null。
 *
 * [home] 为命令执行环境的 home（本地 PRoot 容器为 /root，远程 SSH 为远程用户 home），
 * 用于展开 `~/` 参数——quote 后 shell 不会自动展开 `~`，故在构造命令时显式展开。
 */
fun buildSearchCommand(tokens: List<String>, home: String = "/root"): String? {
    val (rgTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val args = mutableListOf(
        "rg",
        "--line-number",
        "--no-heading",
        "--with-filename",
        "--color",
        "never"
    )
    args.addAll(rgTokens.map { shellQuote(expandTilde(it, home)) })
    var command = args.joinToString(" ")
    for (seg in pipeSegments) {
        if (!pipeSegmentAllowed(seg)) return null
        command += " | " + seg.joinToString(" ")
    }
    return command
}

/** 把 `~`/`~/` 开头的路径参数展开为 [home] 路径，避免被单引号包裹后 shell 不展开 `~`。 */
fun expandTilde(arg: String, home: String = "/root"): String =
    when {
        arg == "~" -> home
        arg.startsWith("~/") -> home.trimEnd('/') + arg.removePrefix("~")
        else -> arg
    }

/** 单引号包裹，shell 命令安全。 */
fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
