package com.aicode.feature.agent.domain.mcp

/**
 * 响应大小的处理裁决。
 */
internal sealed interface SizeVerdict {
    /** 直接内联进上下文。 */
    data object Inline : SizeVerdict

    /** 落盘后按需读取；[oversized] 标记已越过常规阈值，需告警。 */
    data class SpillToDisk(val oversized: Boolean = false) : SizeVerdict

    /** 超出硬上限，拒绝读取。 */
    data object Reject : SizeVerdict
}

/**
 * 响应大小三段式熔断，避免把超大响应一次性读进内存（`body.string()` 可 OOM）。
 *
 * - `≤ [INLINE_MAX_BYTES]`：内联
 * - `(INLINE_MAX_BYTES, SPILL_MAX_BYTES]`：落盘
 * - `(SPILL_MAX_BYTES, HARD_MAX_BYTES]`：落盘并标记 oversized
 * - `> [HARD_MAX_BYTES]`：拒绝
 */
internal object ResponseSizeGovernor {

    /** 可直接内联的最大字节数（512KB）。 */
    const val INLINE_MAX_BYTES = 512L * 1024

    /** 常规落盘的最大字节数（4MB）。 */
    const val SPILL_MAX_BYTES = 4L * 1024 * 1024

    /** 硬上限，超过即拒绝（8MB）。 */
    const val HARD_MAX_BYTES = 8L * 1024 * 1024

    fun decide(bytes: Long): SizeVerdict = when {
        bytes <= INLINE_MAX_BYTES -> SizeVerdict.Inline
        bytes <= SPILL_MAX_BYTES -> SizeVerdict.SpillToDisk(oversized = false)
        bytes <= HARD_MAX_BYTES -> SizeVerdict.SpillToDisk(oversized = true)
        else -> SizeVerdict.Reject
    }
}
