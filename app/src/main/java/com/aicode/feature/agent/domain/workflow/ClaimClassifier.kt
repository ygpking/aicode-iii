package com.aicode.feature.agent.domain.workflow

/**
 * 完工声明的判别层：判断一段收尾文本里有没有「声称做完了 / 声称验证通过」。
 *
 * 移植自 EricFinland/proof 的 `proofkit/classifier.py`（MIT），并按中文习惯扩展。
 * 与原始实现的关键差异在于**写法**：原实现的注释写明用「短语锚定」正则而非裸词表——
 * 裸词表命中的是单词，而 `done`、`fixed`、`now` 这类词在日常语句里到处都是
 * （"done button"、"not done yet"、"the deadline has passed"），必须靠短语结构
 * 把它锚定成「完成声明」才可用。本文件遵循同一原则，中文侧同理使用短语而非单字。
 *
 * 两道防线：
 *  1. [NEGATORS] 在匹配前先跑：命中即整段视为「非声明」。承认粗糙（一处否定词压制全文），
 *     但方向是漏报而非误报——与守卫「宁可漏报不可误判」的取向一致。
 *  2. 短语正则只认断言结构。
 */
internal object ClaimClassifier {

    /** 「声称验证通过」的短语结构。 */
    private val VERIFY_PATTERNS = listOf(
        // 中文：必须是「主谓完整」的通过表述，裸「通过」不算（「通过率」「经过」都会误伤）
        Regex("(?:测试|单测|用例|编译|构建|校验|验证|检查|全部)(?:都|均|已)?(?:通过|成功)"),
        Regex("(?:全部|所有)(?:测试|用例)?(?:都|均)?通过"),
        // 英文：与 proof 一致，要求 tests/test 作主语
        Regex("""(?i)\btests?\s+(?:are\s+|is\s+)?pass(?:ing|ed|es)?\b"""),
        Regex("""(?i)\ball\s+tests?\s+pass\b"""),
        Regex("""(?i)\bbuild\s+is\s+(?:clean|green|passing)\b"""),
        Regex("""(?i)\bbuilds?\s+success"""),
        Regex("""(?i)\btype-?check\b"""),
        Regex("""(?i)\blint(?:ing)?\s+(?:passes|clean)\b"""),
    )

    /**
     * 非变更的主语/动作：出现在「已完成」「已实现」**之前**时，该完成态不指改代码。
     *
     * 必须用后顾判定而非前瞻：「核查已完成」里动作词在完成词之前，前瞻看不着。
     * 这些词全部由真实语料回归暴露（「schema 现状核查已完成」「方案已实现完毕」）。
     */
    private const val NON_CHANGE_BEFORE =
        "核查|调研|分析|梅理|审计|评估|统计|确认|检查|阅读|查看|复核|评审|设计|规划|测试|编译|构建|备份|现状|进度|方案|计划|目标|需求"

    /** 「声称改了东西」的短语结构。 */
    private val CHANGE_PATTERNS = listOf(
        // 中文①：变更动词直接跟「已」，最可靠的形式。
        // **不加宾语负前瞻**：记忆域（「已更新记忆」）交给句子级 [isMemoryDomainSentence] 判，
        // 因为宾语正则可被前置宾语（「记忆已更新」）、跨空白修饰（「已更新了 3 处记忆」）绕过，
        // 且加宽前瞻会把「已修复 memory leak」这类真实修复误排除（实测回归）。
        Regex("已(?:经)?(?:修改|改动|更新|修复|解决|改好|改完|写好)(?:了|完成|完毕)?"),
        // 中文②：「已实现/已完成」需排除非变更主语（方案落地、核查完成都不算改代码）。
        Regex("(?<!(?:$NON_CHANGE_BEFORE))已(?:经)?实现(?!方案|计划|目标|需求|思路|构想|想法)"),
        Regex("(?<!(?:$NON_CHANGE_BEFORE))已(?:经)?完成(?!分析|核实|梅理|阅读|查看|检查|调研|审计|评估|表述|总结|的)"),
        // 中文③：后置式「X 了/完毕」，仅变更动词；「核查完成了」不算。
        Regex("(?:修复|修改|改动|解决|改好|改完|写好|处理完|做完)(?:了|完毕|完成)"),
        // 英文：「现在能用了」属变更声明，需 now 锚定完成义
        Regex("""(?i)\b(?:it|everything|this)\s+works?\s+now\b"""),
        Regex("""(?i)\bworks?\s+now\b"""),
        Regex("(?i)\\ball\\s+done\\b"),
        Regex("""(?i)\b(?:we'?re|i'?m)\s+done\b"""),
        Regex("""(?i)\b(?:bug|issue|it|that|the\s+\w+)\s+is\s+(?:now\s+)?fixed\b"""),
        Regex("""(?i)\bnow\s+fixed\b"""),
        Regex("""(?i)\bi'?ve\s+fixed\b"""),
        Regex("""(?i)\bfixed\s+the\b"""),
        Regex("""(?i)\bfeature\s+is\s+complete\b"""),
        Regex("""(?i)\ball\s+set\b"""),
        // 变更动词的完成态（对应 proof `_CHANGE_EXTRA` 整组）：
        // 这两类此前缺失，使英文「Updated Foo.kt」进不了 R3（R3 挂在 claimsChange 之下）。
        Regex("""(?i)\bi(?:'ve|\s+have|)\s+(?:added|implemented|created|wrote|introduced|updated|changed|modified|edited|fixed)\b"""),
        Regex("""(?i)\bis\s+(?:now\s+)?implemented\b"""),
        Regex("""(?i)\bthis\s+is\s+(?:now\s+)?fixed\b"""),
        // 修复主语词表扩到与 proof FIX_PATTERNS 对齐（原版无需 the，Kotlin 版曾只认 the \w+）
        Regex("""(?i)\b(?:bug|issue|crash|error|problem|regression|failure|leak|typo|test|build)\s+is\s+(?:now\s+)?fixed\b"""),
        // 裸 deployed：前置的未来/被动形态由 NEGATORS 排除（not/being/will be/to be deployed）
        Regex("""(?i)\bdeployed\b"""),
        Regex("""(?i)\bsuccessfully\s+(?:deployed|installed|migrated|completed|finished|built|published|released)\b"""),
        Regex("""(?i)\bdeployed\s+successfully\b"""),
        Regex("""(?i)\breturns?\s+200\b"""),
    )

    /**
     * 否定/未发生抑制器：命中后整条消息不再判为声明。
     * 移植自原实现的 NEGATORS（并补中文），保持「先抑制再匹配」的顺序。
     */
    private val NEGATORS = listOf(
        Regex("""(?i)\blet me\b"""),
        Regex("""(?i)\bi'?m\s+working\b"""),
        Regex("""(?i)\bmight\b"""),
        Regex("""(?i)\binvestigate\b"""),
        Regex("""(?i)\bnot\s+done\b"""),
        Regex("""(?i)\bnot\s+deployed\b"""),
        Regex("""(?i)\bbeing\s+deployed\b"""),
        Regex("""(?i)\bwill\s+be\s+deployed\b"""),
        Regex("""(?i)\bto\s+be\s+deployed\b"""),
        // 中文：尚未/还没/未能 等未完成态（保留在关键词附近判定，避免全文压制）
        Regex("(?:尚未|还没|还没能|未能|没能|没有|未能完全)(?:测试|单测|编译|构建|验证|校验|通过|修复|改|完成)"),
        Regex("(?:未验证|未测试|未编译|未跑|没跑|没测)"),
    )

    /**
     * 段落级「未完成」信号：出现在同一句里才抑制，
     * 避免全文压制导致「A 没做完，B 已完成」这类混合汇报被整段放过。
     */
    private val LOCAL_NEGATORS = listOf(
        Regex("(?:尚未|还没|还没能|未能|没能|没有|不需要|无需|无须)"),
        Regex("""(?i)\b(?:not|no|never|without|n't)\b"""),
    )

    /**
     * 英文变更动词。单独列出供 [claimsChange] 与路径声明共用——
     * 此前 `CLAIMED_PATH_RE` 认 `updated/changed/edited` 而 CHANGE_PATTERNS 不认，
     * 导致英文「Updated Foo.kt」永远进不了 R3。
     */
    private val EN_CHANGE_VERBS = Regex(
        """(?i)\b(?:added|implemented|created|wrote|introduced|updated|changed|modified|edited|fixed|rewrote)\b""",
    )

    /** 是否含英文变更动词（供调用方判断「是否声称改了东西」）。 */
    fun hasEnChangeVerb(message: String): Boolean = EN_CHANGE_VERBS.containsMatchIn(message)

    /** 是否声称「验证通过」。 */
    fun claimsVerification(message: String): Boolean {
        val text = stripFencedBlocks(message)
        return !isSuppressed(text) && splitSentences(text).any { s -> VERIFY_PATTERNS.any { it.containsMatchIn(s) } }
    }

    /** 是否声称「改了东西」。 */
    fun claimsChange(message: String): Boolean {
        val text = stripFencedBlocks(message)
        return !isSuppressed(text) && splitSentences(text)
            .any { s -> !isMemoryDomainSentence(s) && CHANGE_PATTERNS.any { it.containsMatchIn(s) } }
    }

    /**
     * 该句是否只是在说「记忆/备忘/待办」域的东西（memory/todo 工具的操作）。
     *
     * 这类操作不是文件写入，在 R2 里永远拿不到凭证，故不作变更声明。
     * 用**句级判断**而非正则枚举宾语：宾语正则对词序 / 修饰长度 / 空白全都无能为力
     * （「记忆已更新」「已更新了 3 处记忆」都饶得过），而句级判断天然免疫这些。
     * 句里带文件路径时不算记忆域——「更新长期记忆和 Foo.kt」这种混合句要拦。
     * 词表只留中文三词：`memory` 不进，「已修复 memory leak」是真实修复。
     */
    private fun isMemoryDomainSentence(sentence: String): Boolean =
        MEMORY_DOMAIN_RE.containsMatchIn(sentence) && !PATH_LIKE_RE.containsMatchIn(sentence)

    /** 提取文本里点名的文件路径（声明改了某个具体文件时用）。 */
    fun claimedPaths(message: String): List<String> =
        CLAIMED_PATH_RE.findAll(stripFencedBlocks(message))
            .map { it.groupValues[1] }.filter { it.isNotBlank() }.toList().distinct()

    /**
     * 剥离 fenced code block（``` 围栏）内容。
     *
     * 贴演示话术、贴 git log/diff、贴历史输出都在围栏里——它们不是本回合的完工声明，
     * 但纯字符串匹配会把里面的「已修复 / 全部通过」当成自己的话（真机已实证两次误伤）。
     *
     * 只处理 ``` 围栏：未闭合时按 markdown 语义剥到文末（漏报方向，可接受）。
     * **行内 code 不剥**——[CLAIMED_PATH_RE] 依赖反引号包裹的路径（``已修改 `Foo.kt` ``），
     * 剥掉会让路径声明整体失配。
     */
    fun stripFencedBlocks(message: String): String {
        if (!message.contains("```")) return message
        val out = StringBuilder()
        var inFence = false
        message.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                return@forEach
            }
            if (!inFence) out.appendLine(line)
        }
        return out.toString()
    }

    /** 记忆域宾语（memory / todo 工具的操作对象）。 */
    private val MEMORY_DOMAIN_RE = Regex("记忆|备忘|待办")

    /** 像文件路径的串：句里出现它就不是单纯的记忆域句子。 */
    private val PATH_LIKE_RE = Regex("[\\w./\\-]+\\.\\w+")

    /**
     * 整段抑制：命中任一 NEGATOR 即视为非声明。
     *
     * 例外与上游一致：`tests pass` 是强声明，即便同段有否定词也不放过
     * （否则「其实没跑，但 tests pass 了」这类会漏）。
     */
    private fun isSuppressed(message: String): Boolean {
        if (!NEGATORS.any { it.containsMatchIn(message) }) return false
        return !Regex("""(?i)\btests?\s+pass""").containsMatchIn(message)
    }

    /** 声明「改了某文件」时点名的路径。 */
    private val CLAIMED_PATH_RE = Regex(
        "(?:已(?:经)?(?:修改|改动|更新|修复|写好|改好)|修改了|改好了|changed|modified|updated|edited|rewrote)" +
            "\\s*[：:]?\\s*`?([\\w./\\-]+\\.\\w+)`?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 按句切分并剔除**非断言句**。
     *
     * 三重防护（与旧版同居，不可省）：
     *  1. 疑问句——不只认问号：「是否已修复」「测试通过了没有」这类提问无问号也是提问；
     *  2. 条件句——「如果/的话/要是」在句里即视为假设而非断言；
     *  3. 引用行——以 `>` / `|` 开头的引用块不是自己的声明。
     *
     * 逗号也切开：让「A 完成了，B 还没做」这类混合句的否定词只作用于自身小句。
     */
    private fun splitSentences(message: String): List<String> =
        message.split(Regex("[。！!；;，,\n]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.endsWith("?") || it.endsWith("？") }
            .filterNot { it.endsWith("没有") || it.endsWith("了吗") || it.endsWith("了没") }
            .filterNot { it.contains("有没有") || it.contains("是不是") }
            .filterNot { it.startsWith("是否") || it.contains("是否已") }
            .filterNot { CONDITION_MARKERS.any { m -> it.contains(m) } }
            .filterNot { QUOTE_MARKERS.any { m -> m.containsMatchIn(it) } }
            .filterNot { it.startsWith(">") || it.startsWith("|") }

    /** 条件标记：出现在句里即整句视为假设而非断言（宁可漏判，不可误判）。 */
    private val CONDITION_MARKERS = listOf("如果", "若", "一旦", "假如", "倘若", "要是", "的话")

    /**
     * 引文标记：句里出现即视为「在引述/转述他人或自己的话」，不作声明。
     *
     * 必须**句子级**（不能进整段级 [NEGATORS]）——否则一句引文会压掉全篇真声明。
     * 词表刻意取窄：「例如/比如/示例/假设」不进表，它们与声明短语同小句的概率不低
     * （「例如已修复了三处」连写是真声明）；「声明」排除「声明式」（Compose 高频术语）。
     * 「声称/声明」是切断拉回循环的关键：拉回注入文案与 escalate 会带上次声明原文，
     * 模型下轮复述「守卫说我『声称修改了 Foo.kt』」时若不复述判引文，会被再拉回一次。
     */
    private val QUOTE_MARKERS = listOf(
        Regex("引用"),
        Regex("原文"),
        Regex("话术"),
        Regex("复述"),
        Regex("转述"),
        Regex("演示"),
        Regex("声称"),
        Regex("声明(?!式)"),
        // 回顾锚词：跨回合汇报的标志（「上一轮改了什么」）。
        // 砍掉「刚才/前面」：「刚才检查发现真修了」是本回合真声明、「前面几行」是位置指代。
        Regex("上一轮"),
        Regex("上一回合"),
        Regex("上一步"),
        Regex("此前"),
    )

    /** 逐句判定某个局部否定是否抑制该句（供调用方做句级核对）。 */
    fun sentenceIsNegative(sentence: String): Boolean =
        LOCAL_NEGATORS.any { it.containsMatchIn(sentence) }
}
