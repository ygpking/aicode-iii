package com.aicode.core.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SafeCall] 的契约（根因 R5 的回归护栏）。
 *
 * 核心不变量：`runCatchingCancellable` 必须让 [CancellationException] **逃逸**，
 * 而标准库 `runCatching` 会把它吞成 `Result.failure` —— 后者会导致协程不被标记为已取消
 * （本地探针实测），任务清理逻辑被静默跳过。
 */
class SafeCallTest {

    @Test
    fun runCatchingCancellable_returnsSuccess() {
        val r = runCatchingCancellable { 42 }
        assertEquals(42, r.getOrNull())
    }

    @Test
    fun runCatchingCancellable_catchesOrdinaryException() {
        val r = runCatchingCancellable { throw IllegalStateException("boom") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    /** 关键：取消必须原样抛出，不得被降级成 Result.failure。 */
    @Test
    fun runCatchingCancellable_propagatesCancellation() {
        assertThrows(CancellationException::class.java) {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }
    }

    /** 对照：标准库 runCatching 会吞掉取消（这正是本工具存在的理由）。 */
    @Test
    fun plainRunCatching_swallowsCancellation_contrast() {
        val r = kotlin.runCatching { throw CancellationException("cancelled") }
        assertTrue("标准 runCatching 会把取消吞成 failure（本工具要修的就是它）", r.isFailure)
    }

    @Test
    fun catchingNonCancellation_mapsOrdinaryException() {
        val mapped = assertThrows(IllegalArgumentException::class.java) {
            catchingNonCancellation({ e -> IllegalArgumentException("wrapped: ${e.message}", e) }) {
                throw IllegalStateException("inner")
            }
        }
        assertTrue(mapped.message!!.contains("wrapped: inner"))
    }

    @Test
    fun catchingNonCancellation_propagatesCancellationUnmapped() {
        assertThrows(CancellationException::class.java) {
            catchingNonCancellation({ e -> IllegalArgumentException("should not be used", e) }) {
                throw CancellationException("cancel")
            }
        }
    }

    @Test
    fun catchingNonCancellation_passesThroughSuccess() {
        assertEquals("ok", catchingNonCancellation({ e -> e }) { "ok" })
    }
}
