package com.aicode.core.util

import kotlinx.coroutines.CancellationException

/**
 * 与 `kotlin.runCatching` 等价，但**显式放行 [CancellationException]**。
 *
 * 为什么存在（根因 R5「异常语义混淆」）：标准库 `runCatching` 会把 `CancellationException`
 * 当普通异常捕获，后果有两层（见 commit `f381272`）：
 * 1. 本应交由取消传播的链被降级成一次「失败」——只写一行 WARN，任务记录残留未清理；
 * 2. 协程**连 cancelled 状态都不会被标记**（本地探针实测：包装挂起调用的 `runCatching`
 *    吞掉取消后，外层 job 的 `isCancelled` 仍为 false）。
 *
 * 使用判据很简单：**只要 block 内可能挂起（suspend DAO / Repository / 网络 / Mutex），
 * 就用本函数，不要用 `kotlin.runCatching`**。纯内存计算（如 `Json.parseToJsonElement`）
 * 可继续用 `runCatching`——它们不会被取消。
 *
 * 此前该实现只以私有 `guarded()` 形式存在于 `DurableTaskRepository` 一个文件里，
 * 另有 `RemoteSftpFileAccess` 复制了一份。收敛到此处后全仓只有一个答案。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * 需要保留原异常类型时的「先放行取消、再交给 [mapper] 转译」包装。
 *
 * 典型用法：把底层异常统一翻成对用户友好的类型（如 SFTP 失败 → 友好文案的 IOException），
 * 但**不得**把取消也一并翻掉——取消不是「失败」。
 */
inline fun <T> catchingNonCancellation(mapper: (Throwable) -> Throwable, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw mapper(e)
    }
