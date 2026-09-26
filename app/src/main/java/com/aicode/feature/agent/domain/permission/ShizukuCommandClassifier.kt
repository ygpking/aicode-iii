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
 * - [Verdict.RED_LINE]：不可逆或涉及隐私的高危操作，硬拒绝，仅 `elevate` 可单次提权。
 *
 * 设计原则（务必维持）：
 * - 本表**内置、不落盘**，与 [BuiltInSafeCommands] 一致：容器内的 AI 无法改写本文件给自己扩权。
 * - 不确定一律降级为 CONFIRM（宁可多问，不误放）。
 * - 命令无法静态判定（含 `$(...)`、反引号、子 shell 等）→ 一律 CONFIRM，不做 SAFE 判定。
 * - 出现**任何**输出重定向 → 至少 CONFIRM：Shizuku 的 cwd 是根目录，重定向即写宿主文件。
 */
object ShizukuCommandClassifier {

    enum class Verdict { SAFE, CONFIRM, RED_LINE }

    data class Classification(
        val verdict: Verdict,
        val reason: String? = null
    )

    /** 只读查询类程序：任意传参都不改变系统状态。 */
    private val SAFE_PROGRAMS = setOf(
        "dumpsys", "getprop", "df", "du", "stat", "ls", "cat", "head", "tail",
        "ps", "top", "free", "uptime", "id", "whoami", "uname", "hostname",
        "getconf", "which", "readlink", "realpath", "wc"
    )

    /** 只读的子命令（程序名 + 子命令两级匹配）。 */
    private val SAFE_PREFIXES = listOf(
        "pm list", "pm path", "pm dump", "pm resolve-activity",
        "settings get", "settings list", "settings --help",
        "wm size", "wm density", "wm overscan",
        "cmd -l", "cmd help",
        "logcat -d", "logcat --dump"
    )

    /** 读敏感文件内容的程序：配合 [SENSITIVE_PATH] 判定红线。 */
    private val CONTENT_READERS = setOf(
        "cat", "head", "tail", "less", "more", "strings", "grep", "egrep", "fgrep", "rg", "od", "xxd", "base64"
    )

    /** 应用私有数据中的隐私目录：读或删都属红线。 */
    private val PRIVACY_DIRS = setOf("databases", "shared_prefs")

    /** 可再生的缓存目录：删除它们属「清垃圾」，不判红线。 */
    private val CACHE_DIRS = setOf("cache", "code_cache", "no_backup/cache")

    /** 应用私有数据根目录的三种等价写法。 */
    private val APP_DATA_ROOT = Regex("^/data/(data|user/\\d+|user_de/\\d+)(/|$)")

    /** 系统关键路径：删除即红线。 */
    private val SYSTEM_PATHS = setOf("/", "/system", "/vendor", "/boot", "/product", "/odm", "/data", "/sdcard", "/storage", "/persist", "/metadata")

    /** 破坏性磁盘/分区程序：一律红线。 */
    private val DISK_PROGRAMS = setOf("dd", "mkfs", "fdisk", "sfdisk", "wipefs", "blkdiscard", "fastboot", "mke2fs")

    /** 会改变系统状态或应用状态的程序前缀：一律红线。 */
    private val RED_LINE_PREFIXES = listOf(
        "pm uninstall", "pm disable", "pm enable", "pm clear", "pm hide", "pm suspend",
        "settings put", "settings delete", "settings reset",
        "svc ", "am force-stop", "am kill",
        "mount -o remount", "mount -o rw", "setenforce"
    )

    /** 外发数据的参数特征：-d/--data/-F/--form/-T/--upload-file 或 -X POST/PUT。 */
    private val UPLOAD_FLAGS = Regex("(^|\\s)(-d|--data|--data-raw|--data-binary|-F|--form|-T|--upload-file)(\\s|=|$)")

    /** 外发数据的程序：curl/wget/nc 带上传参数，scp/rsync 上传。 */
    private val UPLOAD_PROGRAMS = setOf("curl", "wget", "nc", "ncat", "netcat", "scp", "rsync")

    /**
     * 对一条命令给出分级。
     * 先逐段判红线（最高优先，命中即返回）；全段 SAFE 才判 SAFE；否则 CONFIRM。
     */
    fun classify(command: String): Classification {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Classification(Verdict.CONFIRM)

        // 含嵌套求值构造时无法静态判定，保守归 CONFIRM（不做 SAFE 放行）。
        val analysis = ShellCommandParser.analyze(trimmed)

        for (segment in analysis.segments) {
            redLineReason(segment)?.let { return Classification(Verdict.RED_LINE, it) }
        }

        // 任何输出重定向都是写宿主文件；无法判定目标时不给 SAFE。
        if (trimmed.contains('>')) return Classification(Verdict.CONFIRM)

        if (!analysis.analyzable) return Classification(Verdict.CONFIRM)

        val allSafe = analysis.segments.isNotEmpty() && analysis.segments.all { isSafeSegment(it) }
        return if (allSafe) Classification(Verdict.SAFE) else Classification(Verdict.CONFIRM)
    }

    /** 单段的红线判定：命中返回原因，否则 null。 */
    private fun redLineReason(tokens: List<String>): String? {
        val eff = tokens.dropWhile { ENV_ASSIGN.matches(it) }
        val program = eff.firstOrNull() ?: return null
        val args = eff.drop(1)

        if (program in DISK_PROGRAMS) return "禁止对磁盘或分区做破坏性操作（$program）"

        RED_LINE_PREFIXES.firstOrNull { prefix ->
            val p = prefix.trim().split(' ')
            eff.size >= p.size && p.indices.all { eff[it] == p[it] }
        }?.let { return "禁止执行会改变系统或应用状态的操作（${it.trim()}）" }

        if (isRm(program)) {
            val targets = rmTargets(args)
            for (raw in targets) {
                redLineDeleteReason(raw)?.let { return it }
            }
        }

        if (program in CONTENT_READERS) {
            args.filterNot { it.startsWith("-") }.forEach { path ->
                if (isPrivacyPath(path)) return "禁止读取其他应用的私有数据（$path）"
            }
        }

        if (program in UPLOAD_PROGRAMS) {
            val hasUpload = args.any { UPLOAD_FLAGS.containsMatchIn(" $it ") } ||
                args.any { it.equals("POST", true) || it.equals("PUT", true) } ||
                (program == "scp" && args.any { !it.startsWith("-") })
            if (hasUpload) return "禁止把数据外发到网络（$program）"
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

    /** 该路径是否属应用隐私数据（读即红线）。 */
    private fun isPrivacyPath(rawPath: String): Boolean {
        val path = normalize(rawPath)
        if (!APP_DATA_ROOT.containsMatchIn(path) && !path.startsWith("/data/data/")) return false
        val segments = path.split('/').filter { it.isNotEmpty() }
        return segments.any { it in PRIVACY_DIRS }
    }

    /** 单段是否属只读安全命令（严格：程序在表内，且不触碰隐私路径）。 */
    private fun isSafeSegment(tokens: List<String>): Boolean {
        val eff = tokens.dropWhile { ENV_ASSIGN.matches(it) }
        val program = eff.firstOrNull() ?: return false
        if (eff.drop(1).any { isPrivacyPath(it) }) return false
        if (SAFE_PROGRAMS.contains(program)) return true
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
