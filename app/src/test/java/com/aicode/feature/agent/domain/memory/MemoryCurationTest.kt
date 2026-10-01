package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryCuration] 的只读诊断回归。
 *
 * 一律**只读**：本类不写任何文件，测试也不碰磁盘（直接构造 [Memory]）。
 * 阈值行为以 2026-10-01 的 20 条真实记忆为基线——真实语料近重复最高仅 0.219，
 * 故 [MemoryCuration.DEFAULT_NEAR_DUPLICATE]=0.5 下应 0 命中，这里用「相近但未过阈」的
 * 样例锁定该边界，避免日后有人调低阈值把互不重复的记忆误判成重复。
 */
class MemoryCurationTest {

    private fun memory(
        name: String,
        description: String = "d",
        content: String,
        triggers: List<String> = listOf("t"),
    ) = Memory(
        name = name,
        description = description,
        scope = MemoryScope.PROJECT,
        file = null,
        content = content,
        triggers = triggers,
    )

    @Test
    fun emptyInput_yieldsNoFindings() {
        assertTrue(MemoryCuration.evaluate(emptyList()).isEmpty())
    }

    @Test
    fun distinctMemories_yieldNoFindings() {
        val memories = listOf(
            memory("a", content = "Kotlin 协程的结构化并发与取消传播"),
            memory("b", content = "Gradle 构建缓存与配置缓存实践"),
            memory("c", content = "Docker 镜像层与多阶段构建减小体积"),
        )
        assertTrue(
            "内容互不相同的记忆不应产生任何建议，实际: ${MemoryCuration.evaluate(memories)}",
            MemoryCuration.evaluate(memories).isEmpty(),
        )
    }

    /** 正文近乎逐字重复（远超阈值）→ 应报 NEAR_DUPLICATE。 */
    @Test
    fun nearDuplicate_bodies_areReported() {
        val body = "前端请求统一走 OkHttp 拦截器注入鉴权头，刷新 token 时用单飞避免并发重复刷新。"
        val memories = listOf(
            memory("a", content = body),
            memory("b", content = body + " 补充：失败时降级为匿名请求。"),
        )
        val findings = MemoryCuration.evaluate(memories)
        val dup = findings.filter { it.kind == MemoryCuration.Kind.NEAR_DUPLICATE }
        assertEquals(1, dup.size)
        assertEquals(listOf("a", "b"), dup.first().memories)
    }

    /** 阈值边界：相似度不到阈值不得报（真实语料最高仅 0.219，绝不能误报）。 */
    @Test
    fun belowThreshold_isNotReported() {
        val memories = listOf(
            memory("a", content = "Android 构建用 arm64 SDK，build-tools 需手工修补。"),
            memory("b", content = "Rust 交叉编译到 Android 改用 rust-lld，不要用 cargo-ndk。"),
        )
        assertTrue(
            MemoryCuration.evaluate(memories).none { it.kind == MemoryCuration.Kind.NEAR_DUPLICATE },
        )
    }

    /** 正文超出召回索引上限 → 报 OVERSIZED_BODY，且指明具体字符数。 */
    @Test
    fun oversizedBody_isReported() {
        val memories = listOf(
            memory("big", content = "x".repeat(MemoryRecall.MAX_INDEX_CHARS + 500)),
            memory("small", content = "短正文"),
        )
        val findings = MemoryCuration.evaluate(memories).filter { it.kind == MemoryCuration.Kind.OVERSIZED_BODY }
        assertEquals(1, findings.size)
        assertEquals(listOf("big"), findings.first().memories)
        assertTrue("详情应含实际字符数", findings.first().detail.contains("${MemoryRecall.MAX_INDEX_CHARS + 500}"))
    }

    /** 恰好等于上限不算超（> 才是超），避免边界抖动。 */
    @Test
    fun exactlyAtIndexLimit_isNotOversized() {
        val memories = listOf(memory("edge", content = "x".repeat(MemoryRecall.MAX_INDEX_CHARS)))
        assertTrue(MemoryCuration.evaluate(memories).none { it.kind == MemoryCuration.Kind.OVERSIZED_BODY })
    }

    @Test
    fun missingTriggers_areReported() {
        val memories = listOf(
            memory("no-trg", content = "正文", triggers = emptyList()),
            memory("with-trg", content = "正文", triggers = listOf("发版")),
        )
        val findings = MemoryCuration.evaluate(memories).filter { it.kind == MemoryCuration.Kind.MISSING_TRIGGERS }
        assertEquals(1, findings.size)
        assertEquals(listOf("no-trg"), findings.first().memories)
    }

    /** 一项记忆可同时命中多个信号，互不吞并。 */
    @Test
    fun multipleKinds_onSameMemory_areBothReported() {
        val memories = listOf(
            memory("m", content = "x".repeat(MemoryRecall.MAX_INDEX_CHARS + 1), triggers = emptyList()),
        )
        val kinds = MemoryCuration.evaluate(memories).map { it.kind }.toSet()
        assertEquals(
            setOf(MemoryCuration.Kind.OVERSIZED_BODY, MemoryCuration.Kind.MISSING_TRIGGERS),
            kinds,
        )
    }

    /** 顺序稳定：同一输入必须给同一输出顺序（纯函数，可安全每轮重算）。 */
    @Test
    fun evaluationIsDeterministic() {
        val memories = listOf(
            memory("b", content = "x".repeat(MemoryRecall.MAX_INDEX_CHARS + 10)),
            memory("a", content = "y".repeat(MemoryRecall.MAX_INDEX_CHARS + 20)),
        )
        assertEquals(MemoryCuration.evaluate(memories), MemoryCuration.evaluate(memories))
    }
}
