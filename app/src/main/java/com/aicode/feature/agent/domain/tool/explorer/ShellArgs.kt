package com.aicode.feature.agent.domain.tool.explorer

/**
 * explorer 工具（search/list）共用的 shell 参数解析与安全白名单逻辑。
 *
 * 模型常把 `| head -30` 这类 shell 管道直接写进工具 args（如 `search(args="-n \"foo\" src | head -30")`）。
 * 这里把 `|` 从 rg/ls 参数中分离出来单独解析，只放行 [READ_ONLY_PIPE_COMMANDS] 里的只读
 * 加工命令（参数受正向字符集与逐命令选项白名单双重约束），防止借管道语法在容器里执行任意命令。
 *
 * 三道防线的关系：命令名白名单挡「换命令」，逐命令选项白名单挡「命令自己的写文件/执行/挂起能力」，
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
 * **命令名白名单只是第一道**。单靠它不够：`sort -o FILE` 会写文件、`tail -f` 会挂住、
 * `grep -f FILE` 会把别的文件读进来，而引号包裹对这些命令自身的能力一律无效。
 * 逐命令的选项约束见 [PIPE_OPTION_ALLOWLIST]。
 */
private val READ_ONLY_PIPE_COMMANDS: Set<String> =
    setOf("head", "tail", "grep", "rg", "sort", "uniq", "wc", "cut", "tr", "nl", "rev", "tac")

/**
 * 逐命令**正向**选项白名单：命令名在 [READ_ONLY_PIPE_COMMANDS] 内，但只有这些选项
 * 才允许出现，其余一律拒。
 *
 * 为什么不能只靠字符集：字符集只能挡住 shell 元字符，挡不住命令**自身**的内建能力。
 * 逐项均在本容器实跑验证，看 marker 是否落盘（命令都已贴出，可自行复现）：
 *  - `awk 'BEGIN{system("touch x")}'` → 落盘（任意命令执行）
 *  - `sed 'e touch x'` / `sed 's/a/b/w x'` → 落盘（执行 / 写文件）
 *  - `sort -o FILE` / `sort --output=FILE` → 落盘（写文件）
 *  - `sort --compress-program=PROG` → 把参数当程序执行
 *  - `tail -f FILE` → 不退出（`echo x | tail -f FILE` 实测 exit=124 挂满超时；
 *    不带文件参数时 `echo x | tail -f` 实测 exit=0，管道 stdin 读完即退）
 *  - `grep -f FILE` / `rg --pre PROG` → 从文件读 pattern（绕过字符集）/ 执行预处理器
 *  - `sort -T DIR` / `--temporary-directory=DIR` → 往指定目录写临时文件
 * 单引号包裹对上述**全部无效**：引号只保护 shell 元字符，不改变命令自己的语言语义。
 *
 * 因此 awk/sed 根本不在 [READ_ONLY_PIPE_COMMANDS] 里——它们唯一有价值的用法就是
 * 携带程序体，而程序体一旦被允许就必然带上执行能力，与只读约束不可兼得。
 * 上面两条 awk/sed 列在这里是为了给「为什么排除它们」留证据，不是它们在白名单内。
 *
 * 长选项的等号形式（`--output=FILE`）与短选项的合并形式（`-rn`）由 [pipeOptionAllowed]
 * 归一化后判定，不在这里展开。
 */
internal val PIPE_OPTION_ALLOWLIST: Map<String, Set<String>> = mapOf(
    "head" to setOf("-n", "-q", "-v", "-z"),
    "tail" to setOf("-n", "-q", "-v", "-z"),
    "grep" to setOf(
        "-i", "-v", "-c", "-l", "-L", "-n", "-o", "-h", "-H", "-w", "-x",
        "-e", "--regexp", "-E", "-F", "-G", "-P", "-a", "-I", "-U", "-b",
        "-m", "--max-count", "-A", "-B", "-C",
        "--after-context", "--before-context", "--context",
        "--color", "--include", "--exclude", "--include-dir", "--exclude-dir",
    ),
    "rg" to setOf(
        "-i", "-v", "-c", "-l", "-n", "-o", "-w", "-x", "-e", "--regexp",
        "-F", "--fixed-strings", "-E", "--encoding", "-g", "--glob", "--iglob",
        "-t", "--type", "-A", "-B", "-C", "--after-context", "--before-context",
        "--context", "-M", "--max-columns", "--max-columns-preview", "-m",
        "--max-count", "--no-filename", "-N", "--no-line-number", "--heading",
        "-S", "--smart-case", "--sort", "--sortr", "-j", "--threads", "-a",
        "--text", "--binary", "-u", "--unrestricted", "--stats", "--count-matches",
        "--only-matching", "--byte-offset", "--field-match-separator", "--path-separator",
        "--type-add", "--type-not", "-p", "--pretty", "--no-messages",
    ),
    "sort" to setOf(
        "-b", "-d", "-f", "-g", "-h", "-i", "-M", "-n", "-R", "-r", "-V",
        "-k", "--key", "-t", "--field-separator", "-u", "--unique", "-c",
        "--check", "-C", "-m", "--merge", "-s", "--stable", "-z",
        "--zero-terminated", "--parallel", "-S", "--buffer-size",
    ),
    "uniq" to setOf(
        "-c", "--count", "-d", "--repeated", "-D", "--all-repeated", "-f",
        "--skip-fields", "-i", "--ignore-case", "-s", "--skip-chars", "-u",
        "--unique", "-w", "--check-chars", "-z", "--zero-terminated", "--group",
    ),
    "wc" to setOf(
        "-c", "--bytes", "-m", "--chars", "-l", "--lines", "-L",
        "--max-line-length", "-w", "--words",
    ),
    "cut" to setOf(
        "-b", "--bytes", "-c", "--characters", "-d", "--delimiter", "-f",
        "--fields", "-n", "-s", "--complement", "-z", "--zero-terminated",
        "--output-delimiter",
    ),
    "tr" to setOf("-c", "-C", "--complement", "-d", "--delete", "-s", "--squeeze-repeats", "-t", "--truncate-set1"),
    "nl" to setOf(
        "-b", "--body-numbering", "-d", "--section-delimiter", "-f",
        "--footer-numbering", "-h", "--header-numbering", "-i", "--line-increment",
        "-l", "--join-blank-lines", "-n", "--number-format", "-p", "--no-renumber",
        "-s", "--number-separator", "-v", "--starting-line-number", "-w", "--number-width",
    ),
    "rev" to emptySet(),
    "tac" to setOf("-b", "--before", "-r", "--regex", "-s", "--separator"),
)

/**
 * 需要**取值**的短选项：带值短选项把其后的字符当作值，不当选项名。
 *
 * 用途是正确拆合并写法。[pipeOptionAllowed] 逐字符拆合并短选项时，必须知道
 * `-S1G` 里的 `1G` 是 `-S` 的值（不是 `-1`/`-G` 两个选项）、`nl -ba` 里的 `a`
 * 是 `-b` 的值（`nl` 根本没有 `-a`）。缺这张表就只好靠「把值里的字母也塞进允许集」
 * 来蒙混，结果既是错的（`sort -S1G` 反被拒）、又把允许集搅浑。
 *
 * **不变量：本表每一项必须同时出现在 [PIPE_OPTION_ALLOWLIST] 对应命令的允许集里。**
 * 否则该选项会被当成「不在白名单」而整段拒绝（实测 `grep -m 5 x` 就是这么被误拒的）。
 * 同一个选项的短长形式（`-m` 与 `--max-count`）要么都列、要么都不列，保持对称。
 */
internal val PIPE_VALUE_OPTIONS: Map<String, Set<String>> = mapOf(
    "head" to setOf("-n"),
    "tail" to setOf("-n"),
    "grep" to setOf("-e", "-m", "-A", "-B", "-C"),
    "rg" to setOf("-e", "-g", "-t", "-A", "-B", "-C", "-M", "-m", "-j", "-E"),
    "sort" to setOf("-k", "-t", "-S"),
    "uniq" to setOf("-f", "-s", "-w"),
    "cut" to setOf("-b", "-c", "-d", "-f"),
    "nl" to setOf("-b", "-d", "-f", "-h", "-i", "-l", "-n", "-s", "-v", "-w"),
    "tac" to setOf("-s"),
)

/**
 * 单个选项是否在该命令的白名单内。归一化三种写法：
 *  - 长选项等号形式 `--output=FILE` → 取 `=` 前的 `--output` 判定
 *  - 合并短选项 `-rn` → 逐字符拆；遇到 [PIPE_VALUE_OPTIONS] 里的选项则其后全是它的值
 *  - 纯数字 `-30` / `-12` → 放行（head/tail 的行数形式，语义由 [headLinesOf] 复核）
 *
 * 白名单外的选项一律拒，不做「看起来无害」的推断——黑名单逐个枚举必然漏变体
 * （实测漏掉 `--output=`、`--compress-program=`、`sort -T`、`tail --retry` 四种）。
 */
internal fun pipeOptionAllowed(command: String, arg: String): Boolean {
    val allowed = PIPE_OPTION_ALLOWLIST[command] ?: return false
    if (!arg.startsWith("-") || arg == "-") return true
    if (arg.startsWith("--")) return arg.substringBefore('=') in allowed
    // 整串都是数字：head/tail 的 -N，行数合法性由 headLinesOf 单独校验。
    // 故意不用「首字符是数字就早退」——那会让 `sort -9o/tmp/x` 整串免检。
    if (arg[1].isDigit()) return arg.drop(1).all(Char::isDigit)
    val valueOptions = PIPE_VALUE_OPTIONS[command].orEmpty()
    var i = 1
    while (i < arg.length) {
        val option = "-" + arg[i]
        if (option !in allowed) return false
        // 该选项取值：其后字符是它的值，不再当选项拆
        if (option in valueOptions) return true
        i++
    }
    return true
}

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
 *
 * **正则元字符（`^$()[]\`）也在内**：正向字符集的代价是误拒合法写法，而这几个是 grep/rg
 * 最常用的正则语法（`grep '^import'`、`grep 'x$'`、`grep -E 'a(b|c)'`、`grep 'a[0-9]b'`、
 * `grep '\d+\.'`），原来全被拒。纳入前对每个字符实测了 shellQuote 后能否逃逸，
 * 含与单引号、换行组合的极端用例（`it's$(touch F)`、`x\ntouch F`）——marker 全部零落盘，
 * 故引号这一道确实能独立兜住它们。
 *
 * 注意字符集只管「不借 shell 语法逃逸」，命令自身的内建能力由 [PIPE_OPTION_ALLOWLIST] 管。
 */
private val SAFE_ARG_CHARS: Set<Char> = buildSet {
    addAll('a'..'z')
    addAll('A'..'Z')
    addAll('0'..'9')
    "._/:=+-,#%~| ".forEach { add(it) }
    // 正则元字符：逐个实测过 shellQuote 后不可逃逸（见上方 KDoc）
    "^\$()[]\\".forEach { add(it) }
}

/**
 * 各命令允许的**位置参数个数上限**。超过即拒。
 *
 * 比「按路径形态猜」可靠：形态法只能枚举已知写法（绝对路径/`~/`/`..`），漏一种就破。
 * 位置参数上限是一次性的、可计算的不变量，不靠穷举——只要该命令不接受文件参数，
 * 任何形态的路径都进不来。
 *
 * 数字依据：
 *  - 0 个：这些命令全部以 stdin 为唯一输入源，位置参数就是文件名
 *    （`sort secret.txt`、`nl /etc/passwd`、`wc -l /etc/passwd`、`cut -d: -f1 /etc/passwd`）
 *  - `grep`/`rg` = 1：唯一位置参数是 pattern（`rg -n foo src/` 的 `foo`）
 *  - `tr` = 2：SET1/SET2（`tr a b`），两个都不是文件名
 *
 * 语料核对：848 个通过段里，位置参数非零的只有 `grep` 的 2 例合法 pattern，
 * 其余 846 段位置参数均为 0 → 本规则在真实用法上零损失。
 *
 * 取代了早先的「按路径形态猜」（拦 `/`、`~`、`..` 开头）。形态法有两个漏：
 *  - 漏判：`| grep x secret.txt` 这类同目录相对路径本就在 READ_WORKSPACE 授权内
 *  - 真漏洞：远程模式下 `RemoteSshEngine.buildCdCommand` 在 `projectPath == null`
 *    时根本不 cd（两个 cd 都失败也照执行命令），此时 `| grep root .ssh/id_rsa`
 *    读的是远端家目录，「cwd 恒等于 workspace」这个前提不成立
 * 个数上限一次关掉绝对路径、`..` 上跳、当前目录相对路径、软链、远程 cwd 五种形态。
 */
private val PIPE_MAX_POSITIONAL: Map<String, Int> = mapOf(
    "head" to 0, "tail" to 0, "sort" to 0, "uniq" to 0, "wc" to 0,
    "cut" to 0, "nl" to 0, "rev" to 0, "tac" to 0,
    "grep" to 1, "rg" to 1,
    "tr" to 2,
)

/**
 * 管道段参数是否安全：字符全在 [SAFE_ARG_CHARS] 内，选项在该命令白名单内，
 * 且**不含读文件的位置参数**。
 *
 * 三道判定依次为：选项白名单 → 字符集 → 位置参数上限。
 * **字符集检查必须在「选项值位」之前做**：值位只跳过「选项白名单」与「位置参数上限」
 * （因为它是选项的值、不是位置参数），但仍受字符集约束——否则 `grep -m 'a;touch x'`
 * 这类把危险字符藏在选项值里的写法就只剩 shellQuote 一道了（实测引号能兜住，
 * 但「双重约束」的声明就不成立，且少一道就少一分探错空间）。
 *
 * 空参数与纯空白参数一律拒（`grep ''` 语义不明，`" "` 几乎必然非本意）。
 */
private fun pipeSegmentArgsSafe(seg: List<String>): Boolean {
    val command = seg[0]
    val valueOptions = PIPE_VALUE_OPTIONS[command].orEmpty()
    // 先标出「哪些 token 是前一个取值选项的值」，避开 `grep -m 5 x` 里把 5 当成位置参数
    val args = seg.drop(1)
    val isOptionValue = BooleanArray(args.size)
    for (i in args.indices) {
        val a = args[i]
        if (!a.startsWith("-") || a.startsWith("--")) continue
        // 附着形式（-m5）：值在同一 token 内，不需标下一个 token
        if (a.length > 2 && ("-" + a[1]) in valueOptions) continue
        if (a in valueOptions && i + 1 < args.size) isOptionValue[i + 1] = true
    }
    var positionalCount = 0
    for ((index, arg) in args.withIndex()) {
        if (arg.isEmpty()) return false
        if (arg.isBlank()) return false
        // 值位：豁免选项白名单与位置参数计数（它是选项的值，不是位置参数），
        // 但**仍受字符集约束**——否则危险字符藏进选项值就只剩 shellQuote 一道。
        if (isOptionValue[index]) {
            if (arg.any { it !in SAFE_ARG_CHARS }) return false
            continue
        }
        if (arg.startsWith("-")) {
            if (!pipeOptionAllowed(command, arg)) return false
        } else {
            positionalCount++
            if (positionalCount > (PIPE_MAX_POSITIONAL[command] ?: 0)) return false
        }
        if (arg.any { it !in SAFE_ARG_CHARS }) return false
    }
    return true
}

/**
 * 位置参数是否会读取 workspace 之外的文件。
 *
 * 三条判定，缺一不可（实测）：
 *  - 绝对路径 `/etc/passwd`
 *  - home 路径 `~/.ssh/id_rsa`
 *  - 含 `..` 段的相对路径 `../../etc/passwd`——proot 以 `/root/workspace` 为 cwd，
 *    上跳两次同样能读到 workspace 之外，只挡绝对路径会被它绕过
 *
 * 不含 `/` 的短词（`a.b`、`secret`）不拦：它们是 pattern 不是路径，且无法上跳。
 * 这是「按能力判」而非「按形状判」的最小形式：只要这个参数能让命令去读别的文件，就拦。
 */
/**
 * 管道段是否可放行：命令在只读白名单内，参数字符合法、选项在该命令白名单内。
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
 * **已知既有面（非本函数引入，此处仅记录以免被当成新洞）**：rg 段自身的参数不限制路径，
 * `search(args="-n x /etc/passwd")` 同样能读到 workspace 之外。rg 是主命令，限制它等于
 * 限制 search 的基本能力，与「管道段只能加工上游输出」不同，不属本 PR 的修复范围。
 * 同理，管道段的位置参数按每命令上限约束（见 [PIPE_MAX_POSITIONAL]），
 * 而**主命令段（rg 段）不受任何路径限制**。
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
