package com.aicode.feature.agent.domain.skill

/** 技能内容审计结论级别。 */
enum class SkillAuditLevel {
    /** 危险内容：拒绝导入。 */
    BLOCKED,

    /** 可疑内容：放行但提示。 */
    WARN,
}

/**
 * 一条审计命中。
 *
 * @param level 结论级别。
 * @param rule 命中的规则名（技术标识，供展示与定位）。
 * @param path 命中所在文件（相对技能根）。
 * @param evidence 触发命中的文本片段（截断后）。
 */
data class SkillAuditFinding(
    val level: SkillAuditLevel,
    val rule: String,
    val path: String,
    val evidence: String,
)

internal data class SkillAuditReport(val findings: List<SkillAuditFinding>) {
    val blocked: List<SkillAuditFinding> get() = findings.filter { it.level == SkillAuditLevel.BLOCKED }
    val warnings: List<SkillAuditFinding> get() = findings.filter { it.level == SkillAuditLevel.WARN }
}

/**
 * 技能包内容的静态审计：纯正则、零依赖，用于导入时拦截危险脚本与注入话术。
 *
 * 分级取舍（宁少勿滥，避免误伤合法技能）：
 * - 破坏性命令、反弹 shell、读取私钥/影子口令 → [SkillAuditLevel.BLOCKED]（拒绝导入）；
 * - 读取 `.env`（开发脚本常见）、prompt 注入话术（仅是文本、未被执行） → [SkillAuditLevel.WARN]。
 *
 * 规则保守：`rm -rf` 必须指向根路径才算数，`bash -i` 必须与 `/dev/tcp/` 同行才算反弹 shell，
 * 普通提及 `.env` 而无读取动作不报警。每个文件每条规则最多产出一条记录。
 */
internal object SkillContentAuditor {

    private const val EVIDENCE_LIMIT = 120

    private class Rule(val name: String, val level: SkillAuditLevel, val detect: (String) -> String?)

    private val RM_RF_ROOT =
        Regex("""\brm\s+-\w*(?:rf|fr)\w*\s+/(?:\*)?(?:\s|$)""", RegexOption.IGNORE_CASE)
    private val MKFS = Regex("""\bmkfs(?:\.\w+)?\b""")
    private val FORK_BOMB = Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:""")
    private val BASH_INTERACTIVE = Regex("""\bbash\s+-i\b""")
    private val DEV_TCP = Regex("""/dev/tcp/""")
    private val NETCAT_EXEC = Regex("""\b(?:nc|ncat)\b[^\n]*?\s-e(?:\s|/|$)""")
    private val SSH_PRIVATE_KEY =
        Regex("""\.ssh/id_(?:rsa|ed25519|ecdsa|dsa)\b(?!\.pub)""")
    private val DOTENV_READ =
        Regex("""\b(?:cat|less|more|head|tail|grep|strings|awk|sed)\b[^\n]*?\.env\b""")
    private val ETC_SHADOW = Regex("""/etc/shadow\b""")
    private val INJECTION_EN =
        Regex("""\b(?:ignore|disregard|forget|override)\b[^\n]{0,40}instructions?\b""", RegexOption.IGNORE_CASE)
    private val INJECTION_ZH = Regex("""忽略[^。\n]{0,12}?指令""")

    /**
     * 审计一批文本文件（path → 文本内容）。非文本文件应在调用前被过滤掉。
     */
    fun audit(files: Map<String, String>): SkillAuditReport {
        val findings = ArrayList<SkillAuditFinding>()
        for ((path, content) in files) {
            for (rule in RULES) {
                val evidence = rule.detect(content) ?: continue
                findings += SkillAuditFinding(rule.level, rule.name, path, evidence)
            }
        }
        return SkillAuditReport(findings)
    }

    private fun Regex.evidenceIn(text: String): String? =
        find(text)?.value?.trim()?.take(EVIDENCE_LIMIT)

    private fun String.lineWith(vararg needles: Regex): String? = lineSequence()
        .firstOrNull { line -> needles.all { it.containsMatchIn(line) } }
        ?.trim()
        ?.take(EVIDENCE_LIMIT)

    private val RULES: List<Rule> = listOf(
        Rule("destructive.rm-rf-root", SkillAuditLevel.BLOCKED) { RM_RF_ROOT.evidenceIn(it) },
        Rule("destructive.mkfs", SkillAuditLevel.BLOCKED) { MKFS.evidenceIn(it) },
        Rule("destructive.fork-bomb", SkillAuditLevel.BLOCKED) { FORK_BOMB.evidenceIn(it) },
        Rule("reverse-shell.bash-dev-tcp", SkillAuditLevel.BLOCKED) { text ->
            text.lineWith(BASH_INTERACTIVE, DEV_TCP)
        },
        Rule("reverse-shell.netcat-exec", SkillAuditLevel.BLOCKED) { NETCAT_EXEC.evidenceIn(it) },
        Rule("sensitive.ssh-private-key", SkillAuditLevel.BLOCKED) { SSH_PRIVATE_KEY.evidenceIn(it) },
        Rule("sensitive.etc-shadow", SkillAuditLevel.BLOCKED) { ETC_SHADOW.evidenceIn(it) },
        Rule("sensitive.dotenv-read", SkillAuditLevel.WARN) { DOTENV_READ.evidenceIn(it) },
        Rule("prompt-injection.override-instructions", SkillAuditLevel.WARN) { INJECTION_EN.evidenceIn(it) },
        Rule("prompt-injection.zh-ignore", SkillAuditLevel.WARN) { INJECTION_ZH.evidenceIn(it) },
    )
}
