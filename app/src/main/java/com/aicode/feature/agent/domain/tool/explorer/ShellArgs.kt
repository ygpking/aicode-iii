package com.aicode.feature.agent.domain.tool.explorer

/**
 * explorer 工具（search/list）共用的 shell 参数解析与安全白名单逻辑。
 *
 * 模型常把 `| head -30` 这类 shell 管道直接写进工具 args（如 `search(args="-n \"foo\" src | head -30")`）。
 * 这里把 `|` 从 rg/ls 参数中分离出来单独解析，只放行 [READ_ONLY_PIPE_COMMANDS] 里的只读
 * 加工命令（参数受正向字符集与命令级选项黑名单双重约束），防止借管道语法在容器里执行任意命令。
 *
 * 三道防线的关系：命令名白名单挡「换命令」，选项黑名单挡「命令自己的写文件/执行/挂起能力」，
 * 正向字符集 + 逐 token [shellQuote] 挡「借参数注入」。缺任何一道都有实测可达的绕过
 * （分别对应 `| rm -rf /`、`sort -o FILE`、`grep "x|touch FILE"`）。
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
 *
 * 用 `toIntOrNull` 而非 `toInt`：超范围值（如 `-n 99999999999`）会让 `toInt` 抛
 * NumberFormatException，从 [buildSearchCommand] 一路穿到 SearchCodeTool 的兜底 catch，
 * 错误码从 INVALID_PIPE 变成 SEARCH_FAILED——同一个「参数不合法」给两种码，模型无从判断。
 */
fun headLinesOf(seg: List<String>): Int? = when {
    seg.isEmpty() -> null
    seg[0] != "head" -> null
    seg.size == 1 -> 10
    seg.size == 2 -> {
        val n = seg[1]
        if (n.length > 1 && n[0] == '-' && n.drop(1).all(Char::isDigit)) n.drop(1).toIntOrNull() else null
    }
    seg.size == 3 && seg[1] == "-n" -> {
        val n = seg[2]
        if (n.isNotEmpty() && n.all(Char::isDigit)) n.toIntOrNull() else null
    }
    else -> null
}

/**
 * 只读管道白名单：这些命令只做「读入→加工→写出 stdout」，不改文件、不发网络，
 * 故允许接在 rg/ls 之后。写操作管道（`>`、`tee`、`install`、`cp`、`mv` 等）一律拒绝。
 *
 * 放行依据：8 天日志里模型在 Bash 中自发使用 `| grep` 3,399 次、`| sort` 419 次、
 * `| wc` 432 次——习惯已成定局，而 search/list 只允许 `| head`，导致 59 次 INVALID_PIPE
 * 白失败。这些是纯只读后处理，没有借管道逃逸执行的风险。
 *
 * `rg` 在内：search 自身就依赖 rg，容器里必然存在，与 grep 同族（`rg -v` = `grep -v`）。
 *
 * **白名单只是第一道**，单靠命令名不够：`sort -o FILE` 会写文件、`tail -f` 会挂住、
 * `grep -f FILE` 会把别的文件读进来。这些见 [PIPE_OPTION_BLACKLIST]。
 */
private val READ_ONLY_PIPE_COMMANDS: Set<String> =
    setOf("head", "tail", "grep", "rg", "sort", "uniq", "wc", "cut", "tr", "nl", "rev", "tac")

/**
 * 命令级选项黑名单：命令名在只读白名单内，但这些选项会让它**不**只读。
 *
 * 逐个选项都实测过（不是理论风险）：
 *  - `sort -o/--output`：把排序结果写进指定文件（实测 `printf 'b\na\n' | sort -o FILE` 落盘）
 *  - `sort --compress-program`：把参数当程序执行
 *  - `tail -f/--follow/-F`：不退出，每次调用挂满 SEARCH_TIMEOUT_MS
 *  - `grep -f/--file`、`rg -f/--files`、`--files0-from`：从文件读 pattern，绕过本文件的字符校验
 *  - `rg --pre`：把参数当预处理器执行
 *  - `tr/cut/nl -o/--output`：写文件
 *
 * 未列入的选项按「只影响 stdout 形态」处理；新增命令时同步核对它有没有这类选项。
 */
private val PIPE_OPTION_BLACKLIST: Map<String, Set<String>> = mapOf(
    "sort" to setOf("-o", "--output", "--compress-program", "--files0-from"),
    "tail" to setOf("-f", "--follow", "-F"),
    "grep" to setOf("-f", "--file"),
    "rg" to setOf("-f", "--files", "--files0-from", "--pre", "--hostname"),
    "tr" to setOf("-o", "--output"),
    "cut" to setOf("-o", "--output", "--only-delimited"),
    "nl" to setOf("-o", "--output"),
    "wc" to setOf("--files0-from"),
    "rev" to setOf("-o", "--output"),
    "tac" to setOf("-o", "--output"),
)

/**
 * 管道段参数的合法字符集（**正向**白名单，不是黑名单）。
 *
 * 为什么必须正向：黑名单只能列出已知危险的字符，而漏一个就等于开门。实测原黑名单
 * （只禁 `<>`&;(){}` 与 `|`）漏掉了 `$`、反斜杠、引号、方括号——`grep "x|touch /tmp/pwned"`
 * 经 parseShellWords 变成单 token `x|touch /tmp/pwned`，拼进命令后**真的在容器 shell 里
 * 执行了**（实测 marker 落盘）。token 内的换行同理（shell 的命令分隔符）。
 *
 * 这里的字符集覆盖了 1799 例真实 search 语料里出现过的全部管道段参数：字母数字、
 * `._/:=+-,`、`#%`、`~`、空格，外加 `|`。空格与 `|` 之所以安全，是因为
 * [buildSearchCommand] 对管道段逐 token 走 [shellQuote]——引号内的空格不会把参数拆成
 * 两个词，`|` 也不再构成管道，两者都只是字面量。
 */
private val SAFE_ARG_CHARS: Set<Char> = buildSet {
    addAll('a'..'z')
    addAll('A'..'Z')
    addAll('0'..'9')
    "._/:=+-,#%~| ".forEach { add(it) }
}

/**
 * 管道段参数是否安全：字符全在 [SAFE_ARG_CHARS] 内，且不触碰该命令的选项黑名单。
 *
 * 空参数一律拒（`grep ''` 在 shell 里是空 pattern，语义不明）。
 * 只含空白的参数（`" "`）同样拒：它经 shellQuote 后是 `' '`，是个合法但几乎必然非本意的
 * pattern，且真实语料里从未出现。
 */
private fun pipeSegmentArgsSafe(seg: List<String>): Boolean {
    // seg[0] 是命令名，其余是参数
    val blacklist = PIPE_OPTION_BLACKLIST[seg[0]].orEmpty()
    for (arg in seg.drop(1)) {
        if (arg.isEmpty()) return false
        if (arg.isBlank()) return false
        if (arg in blacklist) return false
        if (arg.any { it !in SAFE_ARG_CHARS }) return false
    }
    return true
}

/**
 * 管道段是否可放行：命令在只读白名单内，参数字符合法且不触碰选项黑名单。
 *
 * `head` 额外要求 [headLinesOf] 通过（行数参数必须纯数字）：head 是历史上唯一被放行的
 * 管道命令，它的严格校验是原有安全属性，不能随白名单扩大一起丢掉。
 */
fun pipeSegmentAllowed(seg: List<String>): Boolean =
    seg.isNotEmpty() && seg[0] in READ_ONLY_PIPE_COMMANDS && pipeSegmentArgsSafe(seg) &&
        (seg[0] != "head" || headLinesOf(seg) != null)

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
 * **管道段也逐 token 走 [shellQuote]**（命令名保留裸词，否则 shell 找不到命令）。这不是
 * 可选的加固：rgTokens 引号包裹后 `|`、换行都成了字面量，而管道段若裸拼，token 内的
 * `|` 会重新变成管道符——实测 `grep "x|touch /tmp/pwned"` 经 parseShellWords 合成一个
 * token，裸拼后真的在容器 shell 里执行了 touch（marker 落盘）。rg/ls 参数与管道段
 * 必须同口径，否则前一道引号等于白加。
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
        // 命令名裸词 + 参数逐个引号包裹：token 内的 `|`、空白、换行都成为字面量
        command += " | " + seg[0] + seg.drop(1).joinToString("") { " " + shellQuote(it) }
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
