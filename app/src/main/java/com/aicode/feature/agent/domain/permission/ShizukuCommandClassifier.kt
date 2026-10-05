package com.aicode.feature.agent.domain.permission

/**
 * Shizuku 命令分级器：把「直接操作宿主 Android 系统」的命令分成三档，供授权引擎决定放行策略。
 *
 * 为什么需要单独分级：Shizuku 作用于宿主系统而非容器，能力远超容器内命令（本机实测为 root，
 * 可读写所有应用私有数据与系统分区）。沿用容器白名单会两头不讨好——AUTO 下要么全部逐次弹窗，
 * 要么放行过宽（可读光所有应用数据）。故按危害分档：
 *
 * - [Verdict.SAFE]：只读查询类，AUTO 模式下自动放行，不弹窗。
 * - [Verdict.CONFIRM]：一般写操作或无法确定危害，逐次确认。
 * - [Verdict.RED_LINE]：不可逆或涉及隐私的高危操作，硬拒绝；仅 [Classification.elevatable] 为 true
 *   时可由 `elevate` 降级为单次提权确认，私人数据目录一律不可提权。
 *
 * 设计原则（务必维持）：
 * - 本表**内置、不落盘**，与 [BuiltInSafeCommands] 一致：容器内的 AI 无法改写本文件给自己扩权。
 * - **真实执行体不可静态确定时不给 CONFIRM，直接 RED_LINE**：用户面对弹窗分辨不出 `ls` 与
 *   `busybox rm -rf /system`，把危险命令混进普通确认框等于逼用户盲点「允许」。
 * - 命令无法静态判定（`$(...)`、反引号、子 shell）→ 抽出内层命令递归判定；内层仍不可判 → RED_LINE。
 * - 出现**任何**输出重定向 → 至少 CONFIRM：Shizuku 的 cwd 是根目录，重定向即写宿主文件。
 *
 * 判定发生在执行链最上游，而执行层 [com.aicode.feature.agent.domain.tool.shizuku.ShizukuTool]
 * 最终经 `ProcessBuilder("sh", "-c", command)` 让宿主 shell **二次解析**命令，
 * 故包装器 / 命令替换 / 子 shell / 管道必须在这里就拆开判定，不能只看首 token。
 */
object ShizukuCommandClassifier {

    enum class Verdict { SAFE, CONFIRM, RED_LINE }

    /**
     * @param reason 判定原因，供日志与 UI 展示。
     * @param elevatable 是否允许凭 `elevate: true` 单次提权执行。私人数据（照片等）为 false——
     *   这类数据看一眼就泄露，弹窗给不出「这次为什么安全」的判据，用户无从判断。
     */
    data class Classification(
        val verdict: Verdict,
        val reason: String? = null,
        val elevatable: Boolean = true
    )

    /**
     * 只读查询类程序：查询系统元数据、不读宿主上的文件内容。
     *
     * 刻意不含 `dumpsys`、`cat`、`head`、`tail`：前者可 dump 他应用内存/通话状态，
     * 后者读文件内容，都由各自的细则降级（见 [SAFE_PREFIXES] 与 [isSafeSegment]）。
     */
    private val SAFE_PROGRAMS = setOf(
        "getprop", "df", "du", "stat", "ls",
        "ps", "top", "free", "uptime", "id", "whoami", "uname", "hostname",
        "getconf", "which", "readlink", "realpath", "wc"
    )

    /** 只读的子命令（程序名 + 子命令两级匹配）。 */
    private val SAFE_PREFIXES = listOf(
        "pm list", "pm path", "pm resolve-activity",
        "settings get", "settings --help",
        "wm size", "wm density", "wm overscan",
        "cmd -l", "cmd help",
        // 仅电池状态：`dumpsys` 本身可 dump 他应用内存（meminfo）、通话状态（telephony.registry）等，
        // 不整体放行，只白名单这一项无隐私含义的子系统。
        "dumpsys battery"
    )

    /** 应用私有数据中的隐私目录：读到即红线。 */
    private val PRIVACY_DIRS = setOf("databases", "shared_prefs")

    /** 可再生的缓存目录：删除它们属「清垃圾」，不判红线。 */
    private val CACHE_DIRS = setOf("cache", "code_cache", "no_backup/cache")

    /**
     * 用户的私人数据目录（`/sdcard` 及其等价挂载点下）：**读和写都判红线，且不可提权**。
     *
     * 这是用户原话里唯一被点名保护的对象（「我的私人数据比如照片不可读不可写」），
     * 故它的判定不参与任何「降级 / 提权」权衡：命中即拦死。
     */
    private val PRIVATE_MEDIA_DIRS = setOf("DCIM", "Pictures", "Download", "Movies", "Music", "Documents")

    /** `/sdcard` 的等价挂载点写法。 */
    private val MEDIA_ROOTS = listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary", "/mnt/sdcard")

    /** 应用私有数据根目录的三种等价写法。 */
    private val APP_DATA_ROOT = Regex("^/data/(data|user/\\d+|user_de/\\d+)(/|$)")

    /** 系统关键路径：删除即红线。 */
    private val SYSTEM_PATHS = setOf("/", "/system", "/vendor", "/boot", "/product", "/odm", "/data", "/sdcard", "/storage", "/persist", "/metadata")

    /** 破坏性磁盘/分区程序：一律红线。 */
    private val DISK_PROGRAMS = setOf("dd", "mkfs", "fdisk", "sfdisk", "wipefs", "blkdiscard", "fastboot", "mke2fs")

    /**
     * 外壳包装程序：真正的执行体在它们的参数里，必须剥壳后递归判定。
     * `sh -c "rm -rf /system"`、`busybox rm -rf /system`、`timeout 5 rm -rf /` 都靠它识破。
     */
    private val WRAPPER_PROGRAMS = setOf(
        "sh", "bash", "ash", "dash", "zsh", "busybox", "env", "nice", "nohup", "xargs", "timeout"
    )

    /** 无条件红线：提权入口与网络外发（`su`/`sudo` 见 [redLineReason] 单独处理）。 */
    private val NETWORK_ALWAYS_RED = setOf("nc", "ncat", "netcat", "socat", "ssh")

    /** 语句块关键字：出现在段首时不含可执行程序，跳过它才能看到真正的命令（`... ; do rm -rf $p ; done`）。 */
    private val SHELL_KEYWORDS = setOf("do", "then", "else", "elif")

    /** 会改变系统状态或应用状态的程序前缀：一律红线。 */
    private val RED_LINE_PREFIXES = listOf(
        "pm uninstall", "pm disable", "pm enable", "pm clear", "pm hide", "pm suspend",
        "cmd package uninstall",
        "settings put", "settings delete", "settings reset",
        "svc ", "am force-stop", "am kill",
        "mount -o remount", "mount -o rw", "setenforce"
    )

    /** 外发数据的参数特征：-d/--data/-F/--form/-T/--upload-file 或 wget 的 --post-file/--post-data。 */
    private val UPLOAD_FLAGS = Regex("(^|\\s)(-d|--data|--data-raw|--data-binary|-F|--form|-T|--upload-file|--post-file|--post-data)(\\s|=|$)")

    /** 有条件外发的程序：带上传参数，或出现在管道的下游。 */
    private val UPLOAD_PROGRAMS = setOf("curl", "wget", "scp", "rsync")

    /** `content` 会读写任意应用的 provider（短信、联系人、通话记录）。 */
    private val CONTENT_ACTIONS = setOf("query", "read", "insert", "update", "delete", "call")

    private const val MAX_DEPTH = 5

    /**
     * 对一条命令给出分级。
     * 先递归拆解并逐段判红线（最高优先，命中即返回）；全段 SAFE 才判 SAFE；否则 CONFIRM。
     */
    fun classify(command: String): Classification {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Classification(Verdict.CONFIRM)

        redLineOf(trimmed)?.let { return it }

        // 任何输出重定向都是写宿主文件；无法判定目标时不给 SAFE。
        if (trimmed.contains('>')) return Classification(Verdict.CONFIRM)

        val analysis = ShellCommandParser.analyze(trimmed)
        // 含命令替换 / 子 shell 等构造时不做 SAFE 判定（内层已由 redLineOf 判过红线）。
        if (!analysis.analyzable) return Classification(Verdict.CONFIRM)

        val allSafe = analysis.segments.isNotEmpty() && analysis.segments.all { isSafeSegment(it) }
        return if (allSafe) Classification(Verdict.SAFE) else Classification(Verdict.CONFIRM)
    }

    /** 递归判定一条完整命令是否命中红线；命中返回带原因的裁决，否则 null。 */
    private fun redLineOf(command: String, depth: Int = 0): Classification? {
        if (depth > MAX_DEPTH) {
            return Classification(Verdict.RED_LINE, "命令嵌套层数过深，无法安全判定", elevatable = false)
        }
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null

        val analysis = ShellCommandParser.analyze(trimmed)
        for (segment in analysis.segments) {
            redLineReason(segment, analysis, depth)?.let { return it }
        }

        // 命令替换 / 反引号 / 子 shell：宿主 shell 会执行内层，必须拆出来一并判。
        if (trimmed.contains("$(") || trimmed.contains('`') || (!analysis.analyzable && trimmed.contains('('))) {
            val inner = extractNestedCommands(trimmed)
                ?: return Classification(
                    Verdict.RED_LINE,
                    "命令含无法拆解的嵌套求值（子 shell / 命令替换），真实执行体不可判定",
                    elevatable = false
                )
            for (cmd in inner) redLineOf(cmd, depth + 1)?.let { return it }
        }
        return null
    }

    /** 单段的红线判定：命中返回裁决，否则 null。 */
    private fun redLineReason(
        tokens: List<String>,
        analysis: ShellCommandParser.Analysis,
        depth: Int
    ): Classification? {
        val eff = tokens.dropWhile { ENV_ASSIGN.matches(it) || it in SHELL_KEYWORDS }
        val program = eff.firstOrNull() ?: return null
        val base = program.substringAfterLast('/')
        val args = eff.drop(1)

        // 设备已 root 时 `su` 是提权到 root 的最短路径，比 `pm uninstall` 更危险，却曾被漏管。
        if (base == "su" || base == "sudo") {
            return Classification(Verdict.RED_LINE, "禁止提权到 root（$base）")
        }

        if (base in DISK_PROGRAMS) {
            return Classification(Verdict.RED_LINE, "禁止对磁盘或分区做破坏性操作（$base）")
        }

        if (base in NETWORK_ALWAYS_RED) {
            return Classification(Verdict.RED_LINE, "禁止通过宿主网络外发或转发数据（$base）")
        }

        // 外壳包装器：剥壳后递归判定真实命令。
        if (base in WRAPPER_PROGRAMS) {
            val inner = wrapperInnerCommand(base, args)
            if (inner.isNotEmpty()) {
                val text = inner.joinToString(" ")
                for (seg in ShellCommandParser.analyze(text).segments) {
                    redLineReason(seg, analysis, depth + 1)?.let { return it }
                }
            }
        }

        RED_LINE_PREFIXES.firstOrNull { prefix ->
            val p = prefix.trim().split(' ')
            eff.size >= p.size && p.indices.all { eff[it] == p[it] }
        }?.let { return Classification(Verdict.RED_LINE, "禁止执行会改变系统或应用状态的操作（${it.trim()}）") }

        if (base == "content") {
            val action = args.firstOrNull { !it.startsWith("-") }?.lowercase()
            if (action in CONTENT_ACTIONS || args.any { it.startsWith("content://") }) {
                return Classification(Verdict.RED_LINE, "禁止经 content provider 访问应用数据（content $action）")
            }
        }

        if (isRm(base)) {
            for (raw in rmTargets(args)) {
                // 私人数据先判：否则 `/sdcard/DCIM/x` 会被笼统的「系统路径」命中，
                // 从而错失不可提权语义（两者都是红线，但可提权性不同）。
                privateMediaReason(raw)?.let { return Classification(Verdict.RED_LINE, it, elevatable = false) }
                redLineDeleteReason(raw)?.let { return Classification(Verdict.RED_LINE, it) }
                // 目标不可确定即红线：变量解析不出，相对路径的 cwd 是宿主根目录。
                if (raw.contains('$')) {
                    return Classification(Verdict.RED_LINE, "删除目标含变量，无法判定实际路径（$raw）")
                }
                if (!raw.startsWith("/") && (raw.contains('*') || raw.contains('?'))) {
                    return Classification(Verdict.RED_LINE, "删除目标为相对通配，实际路径取决于宿主 cwd（$raw）")
                }
            }
        }

        // 任何程序的非选项参数落在隐私目录或私人数据目录 → 红线。
        // 按「程序」而非「读文件清单」判定：cp / tar / zip / 任意工具只要碰到这些路径，危害相同。
        for (raw in args.filterNot { it.startsWith("-") }) {
            privateMediaReason(raw)?.let { return Classification(Verdict.RED_LINE, it, elevatable = false) }
            if (isPrivacyPath(raw)) {
                return Classification(Verdict.RED_LINE, "禁止访问其他应用的私有数据（$raw）")
            }
        }

        if (base in UPLOAD_PROGRAMS) {
            val hasUpload = args.any { UPLOAD_FLAGS.containsMatchIn(" $it ") } ||
                args.any { it.equals("POST", true) || it.equals("PUT", true) }
            if (hasUpload) return Classification(Verdict.RED_LINE, "禁止把数据外发到网络（$base）")
            // 管道的下游是网络程序：上游数据被送给远端。
            if (analysis.hasUnquotedPipe) {
                return Classification(Verdict.RED_LINE, "禁止把管道数据外发到网络（$base）")
            }
        }

        return null
    }

    /** 剥掉外壳程序，取出它真正要执行的命令 token。 */
    private fun wrapperInnerCommand(base: String, args: List<String>): List<String> = when (base) {
        "sh", "bash", "ash", "dash", "zsh" -> {
            val idx = args.indexOfFirst { it == "-c" }
            if (idx >= 0 && idx + 1 < args.size) listOf(args[idx + 1])
            else args.filterNot { it.startsWith("-") }
        }
        "busybox" -> args.filterNot { it.startsWith("-") }
        "env" -> args.dropWhile { ENV_ASSIGN.matches(it) || it.startsWith("-") }
        // nice/timeout/xargs 的自身参数是数字或短选项，跳过后剩下的即为真实命令。
        else -> args.dropWhile { it.startsWith("-") || it.startsWith("+") || it.toLongOrNull() != null }
    }

    /**
     * 抽出命令替换 `$(...)`、反引号 `` `...` ``、子 shell `(...)` 的内层命令。
     * 单引号内的这些构造按 shell 语义不生效，双引号内的命令替换仍生效。
     * 括号不配对时返回 null——调用方据此判红线（内容拆不开就不能说它安全）。
     */
    private fun extractNestedCommands(command: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        var single = false
        while (i < command.length) {
            val c = command[i]
            when {
                single -> { if (c == '\'') single = false; i++ }
                c == '\'' -> { single = true; i++ }
                c == '\\' -> i += 2
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    val end = matchParen(command, i + 1) ?: return null
                    out.add(command.substring(i + 2, end))
                    i = end + 1
                }
                c == '`' -> {
                    val end = command.indexOf('`', i + 1)
                    if (end < 0) return null
                    out.add(command.substring(i + 1, end))
                    i = end + 1
                }
                c == '(' -> {
                    val end = matchParen(command, i) ?: return null
                    out.add(command.substring(i + 1, end))
                    i = end + 1
                }
                else -> i++
            }
        }
        return out
    }

    /** 从 [open] 处的 `(` 起找配对的 `)`（跳过引号内字符）；找不到返回 null。 */
    private fun matchParen(s: String, open: Int): Int? {
        var depth = 0
        var i = open
        var single = false
        var double = false
        while (i < s.length) {
            val c = s[i]
            when {
                single -> if (c == '\'') single = false
                double -> if (c == '"') double = false else if (c == '\\') i++
                c == '\'' -> single = true
                c == '"' -> double = true
                c == '\\' -> i++
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** 删除目标的红线判定：系统路径、整个应用目录、隐私目录、通配全部应用。 */
    private fun redLineDeleteReason(rawPath: String): String? {
        val path = normalize(rawPath)
        if (path.isEmpty()) return null

        if (path in SYSTEM_PATHS || path == "/*") return "禁止删除系统关键路径（$rawPath）"
        if (SYSTEM_PATHS.any { path.startsWith("$it/") && it != "/data" }) return "禁止删除系统关键路径（$rawPath）"

        // 所有应用的数据目录通配（/data/data/*）——影响面不可控。
        if (path == "/data/data/*" || path == "/data/data" || path.startsWith("/data/data/*")) {
            return "禁止批量删除所有应用的数据（$rawPath）"
        }
        if (APP_DATA_ROOT.containsMatchIn(path)) {
            val rest = path.substringAfter("/").let { it.substringAfter("/") }
            val afterRoot = if (rest.startsWith("data/")) rest.removePrefix("data/") else rest.substringAfter("0/")
            val segments = afterRoot.split('/').filter { it.isNotEmpty() }
            val subDirs = segments.drop(1)
            val hitsWildcard = subDirs.any { it == "*" || it == ".*" }
            val hitsPrivacy = subDirs.any { it in PRIVACY_DIRS }
            val hitsCache = subDirs.any { it in CACHE_DIRS }
            // 删整个应用目录 → 红线。
            if (segments.size == 1) return "禁止删除整个应用的数据目录（$rawPath）"
            // 通配删除且不属清缓存 → 红线（会连带删掉 databases/shared_prefs 等）。
            if (hitsWildcard && !hitsCache) return "禁止通配删除整个应用的数据（$rawPath）"
            // 命中隐私目录（databases/shared_prefs）且不属清缓存 → 红线。
            if (hitsPrivacy && !hitsCache) return "禁止删除应用私有数据（$rawPath）"
            // 删整个 files 目录（用户数据）→ 红线。
            if (subDirs.size == 1 && subDirs.first() == "files") {
                return "禁止删除整个应用数据文件目录（$rawPath）"
            }
        }
        return null
    }

    /** 该路径是否落在用户的私人数据目录（照片/相册/下载等）。 */
    private fun privateMediaReason(rawPath: String): String? {
        val path = normalize(rawPath)
        for (root in MEDIA_ROOTS) {
            if (path == root || !path.startsWith("$root/")) continue
            val first = path.removePrefix("$root/").substringBefore('/')
            if (first in PRIVATE_MEDIA_DIRS) return "禁止访问用户的私人数据目录（$rawPath）"
        }
        return null
    }

    /** 该路径是否属应用隐私数据（读到即红线）。 */
    private fun isPrivacyPath(rawPath: String): Boolean {
        val path = normalize(rawPath)
        if (!APP_DATA_ROOT.containsMatchIn(path) && !path.startsWith("/data/data/")) return false
        val segments = path.split('/').filter { it.isNotEmpty() }
        return segments.any { it in PRIVACY_DIRS }
    }

    /**
     * 单段是否属只读安全命令。
     *
     * SAFE 的语义是「查询系统元数据、不读宿主上的文件」，故除白名单外必须再满足两条：
     * 不带绝对路径参数（`ls /data/data`、`du -sh /data` 会枚举应用清单，虽只读但泄露面大），
     * 且不触碰隐私目录。判错的代价是**无提示执行**，高于 CONFIRM 判错，故从严。
     */
    private fun isSafeSegment(tokens: List<String>): Boolean {
        val eff = tokens.dropWhile { ENV_ASSIGN.matches(it) || it in SHELL_KEYWORDS }
        val program = eff.firstOrNull() ?: return false
        val args = eff.drop(1)
        val base = program.substringAfterLast('/')

        if (base in WRAPPER_PROGRAMS || base in NETWORK_ALWAYS_RED) return false
        val operands = args.filterNot { it.startsWith("-") }
        if (operands.any { it.startsWith("/") }) return false
        if (operands.any { isPrivacyPath(it) || privateMediaReason(it) != null }) return false

        if (base in SAFE_PROGRAMS) return true
        val joined = eff.joinToString(" ")
        return SAFE_PREFIXES.any { joined == it || joined.startsWith("$it ") }
    }

    private fun isRm(program: String): Boolean = program == "rm" || program.endsWith("/rm")

    /** 抽取 rm 的目标路径（跳过选项与 `--`）。 */
    private fun rmTargets(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var afterDashDash = false
        for (a in args) {
            if (!afterDashDash && a == "--") { afterDashDash = true; continue }
            if (!afterDashDash && a.startsWith("-")) continue
            out.add(a)
        }
        return out
    }

    /** 折叠重复斜杠与 `.`/`..`，便于前缀比较。 */
    private fun normalize(raw: String): String {
        val s = raw.trim().trimEnd('/').ifEmpty { "/" }
        if (!s.startsWith("/")) return s
        val parts = ArrayDeque<String>()
        for (p in s.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(p)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
}
