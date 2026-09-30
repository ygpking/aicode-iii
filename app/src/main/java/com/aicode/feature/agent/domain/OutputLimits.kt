package com.aicode.feature.agent.domain

/**
 * 工具/命令输出限额的**单一事实源**（单位：字符）。
 *
 * 为什么要有这个文件（2026-09 事故，见 commit `d59a876`）：
 * `BoundedOutput` 的头尾累积窗口与 `ToolOutputStore` 的内联上限曾是**各自独立的字面量**
 * （前者在 `container` 层、后者在 `tool` 层，两份 `20_000`）。头尾各 2 万**恰好等于**
 * `MAX_INLINE_CHARS`，于是命令链路送进入库环节的文本必然 ≤ 内联上限，`truncated` 结构性
 * 恒为 false，去噪结果被整段丢弃（"算对了却没用上"）。那次修复新增了 `denoised` 判据绕开
 * 这个巧合，但两份 `20_000` 仍在——改动任一边就会以同样的方式静默复发。
 *
 * 本对象把这条**关系**提升为唯一事实源：命令累积窗口由本处定义，内联上限**派生自**它，
 * 两层不再各持一份可独立漂移的字面量。不变量由 [OutputLimitsTest] 在 CI 锁定。
 *
 * 注意两种语义不同、不可互相替代：
 * - `COMMAND_*`：命令输出**累积**时保留头/尾的字符数（`BoundedOutput`）；
 * - `INLINE_*`：工具输出**落盘预览**的内联上限（`ToolOutputStore`）。
 * 它们当前取值相等（因为"上游截断 ⇒ 长度超上限"这一推理依赖该关系），但含义不同。
 */
object OutputLimits {
    /** 命令输出累积时保留的开头字符数（`BoundedOutput` 默认 head）。 */
    const val COMMAND_HEAD_CHARS = 20_000

    /** 命令输出累积时保留的结尾字符数（`BoundedOutput` 默认 tail）。 */
    const val COMMAND_TAIL_CHARS = 20_000

    /**
     * 工具输出内联上限：超过即落盘、只留预览。**派生自上游命令窗口**，不是独立字面量。
     *
     * 二者必须相等：命令链路的内容到达落盘环节前已被 [COMMAND_HEAD_CHARS] +
     * [COMMAND_TAIL_CHARS] 限幅，若内联上限更小则"上游已丢内容、下游判据却认为没超限"，
     * `truncated` 会误判为 false。
     */
    const val INLINE_MAX_CHARS = COMMAND_HEAD_CHARS + COMMAND_TAIL_CHARS
}
