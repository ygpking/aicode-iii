package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallTest {

    // ---------- Tokenizer ----------

    @Test
    fun cjkTokenizedAsCharsPlusBigrams() {
        assertEquals(
            listOf("记", "忆", "召", "回", "记忆", "忆召", "召回"),
            tokenizeForRecall("记忆召回"),
        )
    }

    @Test
    fun latinSplitAndLowercased() {
        assertEquals(listOf("bm25", "hello", "world"), tokenizeForRecall("BM25 Hello, World!"))
    }

    @Test
    fun mixedCjkAndLatinSegmentSeparately() {
        assertEquals(
            listOf("bm25", "排", "序", "缓", "存", "排序", "序缓", "缓存"),
            tokenizeForRecall("BM25排序缓存"),
        )
    }

    // ---------- BM25 相关性 ----------

    @Test
    fun ranksRelevantDocFirst() {
        val docs = listOf(
            RecallDoc("cache", MemoryScope.GLOBAL, "前缀缓存稳定性：system 视为稳定前缀"),
            RecallDoc("bm25", MemoryScope.GLOBAL, "BM25 排序算法实现要点"),
            RecallDoc("unrelated", MemoryScope.GLOBAL, "容器发行版包管理器命令"),
        )
        val hits = MemoryRecall.select("BM25 排序怎么实现", docs)
        assertEquals("bm25", hits.first().id)
    }

    @Test
    fun sameTextProjectOutranksGlobalOnlyWhenTieBrokenByUpdatedAt() {
        // AiCode 不做 scope 加权；同分时按 updatedAtMs 降序、再按 id 稳定排序。
        val docs = listOf(
            RecallDoc("g", MemoryScope.GLOBAL, "中文 bigram 分词方案", updatedAtMs = 100),
            RecallDoc("p", MemoryScope.PROJECT, "中文 bigram 分词方案", updatedAtMs = 200),
        )
        val hits = MemoryRecall.select("中文 bigram 分词", docs)
        assertEquals("p", hits.first().id)
    }

    // ---------- 泛化查询抑制 ----------

    @Test
    fun genericQueriesSuppressed() {
        val docs = listOf(RecallDoc("bm25", MemoryScope.GLOBAL, "BM25 排序算法实现要点"))
        assertTrue(MemoryRecall.select("继续", docs).isEmpty())
        assertTrue(MemoryRecall.select("好的", docs).isEmpty())
        assertTrue(MemoryRecall.select("ok", docs).isEmpty())
        assertTrue(MemoryRecall.select("continue", docs).isEmpty())
        assertTrue(MemoryRecall.select("   ", docs).isEmpty())
    }

    // ---------- 命中上限 ----------

    @Test
    fun respectsMaxHits() {
        val docs = (1..5).map { RecallDoc("d$it", MemoryScope.GLOBAL, "缓存 prefix cache 稳定性") }
        assertEquals(2, MemoryRecall.select("缓存 cache", docs, maxHits = 2).size)
    }

    // ---------- 字数上限 ----------

    @Test
    fun truncatesPerBlockChars() {
        val doc = RecallDoc("a", MemoryScope.GLOBAL, "0123456789ABCDEFGHIJ")
        val block = MemoryRecall.renderBlock(listOf(doc), maxCharsPerBlock = 10)
        assertTrue(block.contains("0123456789"))
        assertTrue(!block.contains("ABCDEFGHIJ"))
    }

    @Test
    fun respectsMaxTotalChars() {
        val docs = listOf(
            RecallDoc("a", MemoryScope.GLOBAL, "x"),
            RecallDoc("b", MemoryScope.GLOBAL, "x"),
            RecallDoc("c", MemoryScope.GLOBAL, "x"),
        )
        val block = MemoryRecall.renderBlock(docs, maxTotalChars = 40)
        assertEquals(2, block.split("\n[").size - 1)
    }

    // ---------- renderBlock 格式 ----------

    @Test
    fun renderBlockWrapsInTags() {
        val block = MemoryRecall.renderBlock(listOf(RecallDoc("m1", MemoryScope.PROJECT, "正文")))
        assertTrue(block.startsWith("<recalled_memory>"))
        assertTrue(block.endsWith("</recalled_memory>"))
        assertTrue(block.contains("[m1 scope=project]"))
        assertTrue(block.contains("正文"))
    }

    @Test
    fun renderBlockEmptyForNoDocs() {
        assertEquals("", MemoryRecall.renderBlock(emptyList()))
    }

    @Test
    fun noMatchYieldsNoHits() {
        val docs = listOf(RecallDoc("a", MemoryScope.GLOBAL, "完全无关的容器发行版内容"))
        assertTrue(MemoryRecall.select("BM25 排序算法", docs).isEmpty())
    }

    // ---------- 相关性需「多字符 token」命中（防单字档板捞） ----------
    //
    // CJK 逐字切分会产出大量单字 token，而「器/好/不/发」这类字几乎每条记忆都有；
    // 仅凭单字重合召回等于按档板捞。实测真实 21 条记忆：「我午饭吃什么」「讲个笑话」
    // 各拉满 5 条（占满 maxHits 槽位、白烧上下文），收紧后降为 0 条，
    // 而 8 个真实查询的目标记忆全部保留。

    @Test
    fun singleCharOnlyOverlapDoesNotRecall() {
        // 查询「我午饭吃什么」的单字「饭」「吃」恰好出现在无关记忆里（「吃饭」）。
        val docs = listOf(RecallDoc("meal", MemoryScope.GLOBAL, "记得先吃饭再干活"))
        assertTrue(
            "仅单字重合不得召回（否则任何含常见字的记忆都会被拉进来）",
            MemoryRecall.select("我午饭吃什么", docs).isEmpty(),
        )
    }

    /** 反向保证：真子串（bigram，≥2 字符）命中仍必须召回，修复不得把相关性改废。 */
    @Test
    fun bigramOverlapStillRecalls() {
        val docs = listOf(RecallDoc("build", MemoryScope.GLOBAL, "Gradle 构建缓存与 SDK 路径"))
        assertTrue(MemoryRecall.select("构建", docs).isNotEmpty())
    }

    /** 拉丁词天然 ≥2 字符，不受该收紧影响。 */
    @Test
    fun latinTokenStillRecalls() {
        val docs = listOf(RecallDoc("gradle", MemoryScope.GLOBAL, "Gradle 构建缓存"))
        assertTrue(MemoryRecall.select("gradle", docs).isNotEmpty())
    }

    /**
     * 与门控对称：门控命中时不得再施加「多字符」要求。
     * 门控本身就是「triggers 与查询有实质交集」的判据，若再要求字面多字符命中，
     * 会把「字面不重叠但语义明确」的桥接能力废掉（正是门控存在的原因）。
     */
    @Test
    fun gateHitBypassesDistinctiveRequirement() {
        // 正文与查询无任何字面重叠，仅 triggers 标出场景。
        val docs = listOf(
            RecallDoc("env", MemoryScope.GLOBAL, "工具链与 SDK 位置", triggers = listOf("发版")),
            RecallDoc("noise", MemoryScope.GLOBAL, "无关内容", updatedAtMs = 1),
        )
        assertEquals("env", MemoryRecall.select("帮我发个正式版", docs).first().id)
    }

    // ---------- pinned 置顶 ----------

    @Test
    fun pinnedDocAlwaysOnTop() {
        val docs = listOf(
            RecallDoc("relevant", MemoryScope.GLOBAL, "BM25 排序算法实现要点"),
            RecallDoc("pin", MemoryScope.GLOBAL, "全局偏好：始终用中文回复", pinned = true),
        )
        val hits = MemoryRecall.select("BM25 排序", docs)
        assertEquals("pin", hits.first().id)
    }

    @Test
    fun pinnedDocIsAlsoSuppressedOnGenericQuery() {
        // 泛化查询（「继续」）整体不召回，pinned 亦不例外——与 REWRITE 原语义一致。
        val docs = listOf(RecallDoc("pin", MemoryScope.GLOBAL, "始终用中文回复", pinned = true))
        assertTrue(MemoryRecall.select("继续", docs).isEmpty())
    }

    @Test
    fun pinnedMarkerAppearsInRenderedBlock() {
        val block = MemoryRecall.renderBlock(listOf(RecallDoc("p", MemoryScope.GLOBAL, "正文", pinned = true)))
        assertTrue(block.contains("[p scope=global pinned]"))
    }

    @Test
    fun pinnedRespectsMaxHits() {
        val docs = (1..5).map { RecallDoc("p$it", MemoryScope.GLOBAL, "正文", pinned = true) }
        assertEquals(2, MemoryRecall.select("任意查询词", docs, maxHits = 2).size)
    }

    // ---------- renderIndexBlock（只用摘要，不内联正文） ----------

    @Test
    fun renderIndexBlockOmitsBodyBeyondPerBlockLimit() {
        val long = "A".repeat(300)
        val doc = RecallDoc("m", MemoryScope.GLOBAL, long)
        val block = MemoryRecall.renderIndexBlock(listOf(doc))
        // 默认每块 120 字符，正文不应被完整内联
        assertTrue(block.contains("A".repeat(120)))
        assertTrue(!block.contains("A".repeat(121)))
    }

    @Test
    fun renderIndexBlockIsMuchSmallerThanFullBlock() {
        val body = "正文内容".repeat(400) // 1600 字符
        val docs = (1..5).map { RecallDoc("d$it", MemoryScope.GLOBAL, body) }
        val full = MemoryRecall.renderBlock(docs)
        val index = MemoryRecall.renderIndexBlock(docs)
        // 同一批 docs，索引块应显著小于全量块（预期 ~1/10 以下）
        assertTrue(index.length * 5 < full.length)
    }

    @Test
    fun renderIndexBlockKeepsSameIdsAndMarkers() {
        val docs = listOf(
            RecallDoc("a", MemoryScope.GLOBAL, "x".repeat(500)),
            RecallDoc("b", MemoryScope.PROJECT, "y".repeat(500), pinned = true),
        )
        val index = MemoryRecall.renderIndexBlock(docs)
        // 标签、id、scope、pinned 标记与全量块一致——只有正文宽度不同
        assertTrue(index.startsWith("<recalled_memory>"))
        assertTrue(index.endsWith("\n</recalled_memory>"))
        assertTrue(index.contains("[a scope=global]"))
        assertTrue(index.contains("[b scope=project pinned]"))
        assertEquals(
            MemoryRecall.renderBlock(docs).split("\n[").size,
            index.split("\n[").size,
        )
    }

    @Test
    fun renderIndexBlockRespectsTotalBudget() {
        val docs = (1..5).map { RecallDoc("d$it", MemoryScope.GLOBAL, "z".repeat(500)) }
        val index = MemoryRecall.renderIndexBlock(docs, maxTotalChars = 300)
        assertTrue(index.length <= 300 + "\n</recalled_memory>".length)
    }

    // ---------- triggers 门控（让「字面不重叠」的查询也能命中） ----------

    @Test
    fun triggerGateRescuesSemanticallyRelatedButLexicallyDisjointDoc() {
        // 复现真实缺口：用户说「发个正式版」，而记忆正文只有「构建环境/SDK」。
        val docs = listOf(
            RecallDoc("build-env", MemoryScope.GLOBAL, "本机 Android 构建环境与 SDK 路径", triggers = listOf("发版", "正式版", "打包")),
            RecallDoc("unrelated", MemoryScope.GLOBAL, "完全无关的容器发行版内容", updatedAtMs = 999),
        )
        val hits = MemoryRecall.select("帮我发个正式版", docs)
        assertEquals("build-env", hits.first().id)
    }

    @Test
    fun triggerGateFallsBackWhenTooWide() {
        // 门控命中超过半数候选时回退全集，避免误杀：三条都命中「测试」时不该只剩这三条。
        val docs = (1..3).map { RecallDoc("d$it", MemoryScope.GLOBAL, "缓存 稳定性 内容", triggers = listOf("测试")) }
        // 「测试缓存」→ 门控命中 3/3（>半数），回退全集，仍应正常返回
        assertTrue(MemoryRecall.select("测试缓存", docs).isNotEmpty())
    }

    @Test
    fun triggerGateSkippedWhenNoTriggersDeclared() {
        // 存量 16 条记忆全无 triggers，行为必须与改造前一致。
        val docs = listOf(RecallDoc("a", MemoryScope.GLOBAL, "BM25 排序算法实现要点"))
        assertEquals("a", MemoryRecall.select("BM25 排序", docs).first().id)
    }

    @Test
    fun triggerHitIsCaseInsensitiveForLatin() {
        val docs = listOf(
            RecallDoc("rel", MemoryScope.GLOBAL, "构建环境说明", triggers = listOf("Release")),
            RecallDoc("other", MemoryScope.GLOBAL, "无关内容", updatedAtMs = 500),
        )
        assertEquals("rel", MemoryRecall.select("release 怎么做", docs).first().id)
    }

    // ---------- 停用词（功能词不喧宾夺主） ----------

    @Test
    fun stopTermsDoNotCrowdOutRelevantDoc() {
        // 「怎么/问题/解决」是提问功能词，不应把真正相关的构建记忆挤下去。
        val docs = listOf(
            RecallDoc("build", MemoryScope.GLOBAL, "Gradle 构建失败与 SDK 路径", updatedAtMs = 1),
            RecallDoc("noise", MemoryScope.GLOBAL, "怎么解决什么问题的方法", updatedAtMs = 999),
        )
        assertEquals("build", MemoryRecall.select("这个构建问题怎么解决", docs).first().id)
    }

    @Test
    fun stopTermsDoNotMakeValidQueryGeneric() {
        // 停用词与 GENERIC_TERMS 分开：含功能词的有效查询不得被判定为泛化而不召回。
        val docs = listOf(RecallDoc("build", MemoryScope.GLOBAL, "Gradle 构建缓存", triggers = listOf("构建")))
        assertTrue(MemoryRecall.select("怎么构建", docs).isNotEmpty())
    }

    // ---------- 门控不得被「单个 CJK 字偶然重合」触发（回归） ----------
    //
    // 这三例都构造了「无关记忆的正文刚好含查询里的某个单字」——正是线上 16 条真实记忆
    // 实测中招的情形（「发」「构」「不」「器」等）。旧实现按 token .any 判命中，
    // 单字重合即放行，实测 16 条记忆里 10 条查询被无关联想污染，甚至把无关项排到第 1 位。

    @Test
    fun gateIgnoresSingleCjkCharCoincidence() {
        // triggers「并发」与「帮我发个正式版」共享单字「发」。
        val docs = listOf(
            RecallDoc("build-env", MemoryScope.GLOBAL, "本机 Android 构建环境与 SDK", triggers = listOf("发版", "正式版")),
            RecallDoc("patterns", MemoryScope.GLOBAL, "并发写 map 与重复代码陷阱", triggers = listOf("并发")),
        )
        val hits = MemoryRecall.select("帮我发个正式版", docs).map { it.id }
        assertEquals("build-env", hits.first())
        assertFalse("单字「发」重合不得把 patterns 拉进门控候选", hits.contains("patterns"))
    }

    @Test
    fun gateIgnoresSingleCharOverlapWithBigramTrigger() {
        // triggers「架构」与查询「构建」共享单字「构」，不是真命中。
        val docs = listOf(
            RecallDoc("build", MemoryScope.GLOBAL, "构建与 SDK 路径", triggers = listOf("构建")),
            RecallDoc("arch", MemoryScope.GLOBAL, "架构说明与分层", triggers = listOf("架构")),
        )
        val hits = MemoryRecall.select("这个构建问题怎么解决", docs).map { it.id }
        assertEquals("build", hits.first())
        assertFalse("单字「构」重合不得把 arch 拉进门控候选", hits.contains("arch"))
    }

    @Test
    fun gateIgnoresSharedNegationChar() {
        // triggers「连不上」与查询「起不来」共享单字「不」。
        val docs = listOf(
            RecallDoc("sandbox", MemoryScope.GLOBAL, "chroot 沙箱隔离与逃逸", triggers = listOf("沙箱")),
            RecallDoc("net", MemoryScope.GLOBAL, "怎么连不上 github 网络", triggers = listOf("连不上")),
        )
        val hits = MemoryRecall.select("沙箱起不来", docs).map { it.id }
        assertEquals(listOf("sandbox"), hits)
    }

    @Test
    fun gateStillFiresOnRealSubstringOverlap() {
        // 反向保证：修复不能把门控改废——真子串命中（「发版」→「发个正式版」共享 发+版）仍须生效。
        val docs = listOf(
            RecallDoc("build-env", MemoryScope.GLOBAL, "构建环境", triggers = listOf("发版")),
            RecallDoc("noise", MemoryScope.GLOBAL, "无关内容", updatedAtMs = 1),
        )
        assertEquals("build-env", MemoryRecall.select("帮我发个正式版", docs).first().id)
    }

    // ---------- 时间衰减（默认关闭） ----------
    //
    // 移植自 OpenSquilla `memory/retrieval.py::MemoryRetriever.search` 的时间衰减（Apache-2.0）。
    // 默认 DISABLED：不传参时排序与加本功能前逐位相同。

    private val dayMs = 86_400_000L

    @Test
    fun temporalDecay_flipsOrderSoNewerMemoryOutranksStaleHighlyRelevantOne() {
        val now = 10_000L * dayMs
        val docs = listOf(
            // 旧记忆：正文重复查询词三次，BM25 明显更高。
            RecallDoc("old", MemoryScope.GLOBAL, "缓存 稳定性 缓存 稳定性 缓存 稳定性", updatedAtMs = now - 400 * dayMs),
            // 新记忆：只用一次，BM25 更低。
            RecallDoc("new", MemoryScope.GLOBAL, "缓存 稳定性 补充说明", updatedAtMs = now),
        )
        // 关闭衰减（默认）：旧的靠 BM25 胜出。
        assertEquals("old", MemoryRecall.select("缓存 稳定性", docs, nowMs = now).first().id)
        // 月级衰减：400 天前 ≈ 2^-13.3，几乎归零，新的胜出。
        assertEquals(
            "new",
            MemoryRecall.select("缓存 稳定性", docs, temporalDecay = TemporalDecay.MONTH, nowMs = now).first().id,
        )
    }

    @Test
    fun temporalDecayDisabled_isIdenticalToDefaultBehaviour() {
        val now = 10_000L * dayMs
        val docs = listOf(
            RecallDoc("a", MemoryScope.GLOBAL, "缓存 稳定性 方案", updatedAtMs = now - 300 * dayMs),
            RecallDoc("b", MemoryScope.GLOBAL, "缓存 稳定性 方案", updatedAtMs = now),
        )
        assertEquals(
            MemoryRecall.select("缓存 稳定性", docs).map { it.id },
            MemoryRecall.select("缓存 稳定性", docs, temporalDecay = TemporalDecay.DISABLED).map { it.id },
        )
    }

    @Test
    fun temporalDecay_leavesDocsWithUnknownTimeUntouched() {
        // updatedAtMs <= 0 表示时间未知（如文件已删），不得被当作「极旧」而衰减掉。
        val now = 10_000L * dayMs
        val docs = listOf(
            RecallDoc("unknown", MemoryScope.GLOBAL, "缓存 稳定性 缓存 稳定性", updatedAtMs = 0),
            RecallDoc("fresh", MemoryScope.GLOBAL, "缓存 稳定性", updatedAtMs = now),
        )
        assertEquals(
            "unknown",
            MemoryRecall.select("缓存 稳定性", docs, temporalDecay = TemporalDecay.MONTH, nowMs = now).first().id,
        )
    }

    @Test
    fun temporalDecay_doesNotRewardFutureTimestamps() {
        // 时钟回拨/文件时间在未来：age 负数按 0 计，不得凭空加成。
        val now = 1_000L * dayMs
        val docs = listOf(
            RecallDoc("hijack", MemoryScope.GLOBAL, "缓存 稳定性", updatedAtMs = now + 500 * dayMs),
            RecallDoc("normal", MemoryScope.GLOBAL, "缓存 稳定性", updatedAtMs = now),
        )
        // 两者衰减因子均为 1，回到同分“时间新者优先”的既有规则。
        val hits = MemoryRecall.select("缓存 稳定性", docs, temporalDecay = TemporalDecay.DAY, nowMs = now)
        assertEquals(2, hits.size)
    }

    // ---------- MMR 多样性（默认关闭） ----------

    @Test
    fun mmr_breaksUpNearDuplicateHits() {
        val now = 1_000L * dayMs
        val dup = "缓存 稳定性 前缀 方案 说明"
        val docs = listOf(
            RecallDoc("dup1", MemoryScope.GLOBAL, dup, updatedAtMs = now),
            RecallDoc("dup2", MemoryScope.GLOBAL, dup, updatedAtMs = now),
            RecallDoc("dup3", MemoryScope.GLOBAL, dup, updatedAtMs = now),
            RecallDoc("other", MemoryScope.GLOBAL, "缓存 稳定性 与容器发行版的不同面", updatedAtMs = now),
        )
        // 默认（关闭）：三条几乎相同的记忆霸占前两名。
        val plain = MemoryRecall.select("缓存 稳定性", docs, maxHits = 2, nowMs = now).map { it.id }
        assertEquals(listOf("dup1", "dup2"), plain)
        // 开启 MMR：第二条换成内容不同的那条，避免“同一件事说三遍”。
        val diverse = MemoryRecall.select("缓存 稳定性", docs, maxHits = 2, mmrLambda = 0.5, nowMs = now).map { it.id }
        assertTrue("MMR 应把重复项换成不同内容：$diverse", diverse.contains("other"))
        assertEquals("相关性最高者仍应保留", "dup1", diverse.first())
    }

    @Test
    fun mmrDisabled_isIdenticalToDefaultBehaviour() {
        val docs = listOf(
            RecallDoc("a", MemoryScope.GLOBAL, "缓存 稳定性 方案"),
            RecallDoc("b", MemoryScope.GLOBAL, "缓存 稳定性 补充"),
        )
        assertEquals(
            MemoryRecall.select("缓存 稳定性", docs).map { it.id },
            MemoryRecall.select("缓存 稳定性", docs, mmrLambda = -1.0).map { it.id },
        )
    }

    @Test
    fun mmrLambdaOne_equivalentToRelevanceOnlyOrder() {
        // lambda=1.0 时 `rel - 0*sim`，等价于关闭，用于验证实现没引入额外扰动。
        val now = 1_000L * dayMs
        val docs = listOf(
            RecallDoc("a", MemoryScope.GLOBAL, "缓存 稳定性 方案", updatedAtMs = now),
            RecallDoc("b", MemoryScope.GLOBAL, "缓存 稳定性", updatedAtMs = now),   
            RecallDoc("c", MemoryScope.GLOBAL, "缓存", updatedAtMs = now),
        )
        assertEquals(
            MemoryRecall.select("缓存 稳定性", docs, nowMs = now).map { it.id },
            MemoryRecall.select("缓存 稳定性", docs, mmrLambda = 1.0, nowMs = now).map { it.id },
        )
    }

    @Test
    fun mmr_keepsAllDocsWhenLimitExceedsCandidates() {
        val docs = (1..3).map { RecallDoc("d$it", MemoryScope.GLOBAL, "缓存 稳定性 方案 $it") }
        assertEquals(3, MemoryRecall.select("缓存 稳定性", docs, maxHits = 10, mmrLambda = 0.4).size)
    }
}
