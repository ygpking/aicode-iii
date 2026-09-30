package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
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
}
