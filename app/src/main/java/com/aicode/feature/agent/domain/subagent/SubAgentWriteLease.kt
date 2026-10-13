package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理写租约：登记每个子代理声明要写的路径，避免多个子代理并发改同一批文件。
 *
 * 为什么需要：多个子代理并行跑时，若无隔离，两个子代理可能同时读到同一文件的旧内容、
 * 各自改一处、后写的把先写的覆盖掉（经典的丢失更新）。租约在 `task(create)` 时按声明的
 * `write_paths` 做一次冲突检查，冲突则拒绝创建并提示等待，把「并发写同一个文件」变成
 * 一个显式的、可在调用方处理的错误。
 *
 * **不登记即不受限**：只读任务（未声明 write_paths）不占租约、也不受任何限制，保持现有行为。
 *
 * **租约不需要显式释放**：判断冲突时只考虑「当前仍活跃」的子代理（活跃集合以
 * [SubAgentEventBus.activeSubSessionIds] 为唯一事实源）。子代理结束/被停/被删时活跃集合自动
 * 更新，其租约随即失效——这样就不存在「忘记释放导致路径被永久占用」的泄漏路径，
 * 也不需要侵入事件总线去挂钩子。已失效的条目在下次检查时顺手清理。
 */
@Singleton
class SubAgentWriteLease @Inject constructor(
    private val eventBus: SubAgentEventBus
) {
    private companion object {
        const val TAG = "SubAgentWriteLease"
        /**
         * 不受限声明：该子代理的写路径**不受任何限制**，工作区内外均可写。
         * 创建期仍会与其它在跑的子代理判冲突（防两个子代理互相覆盖，与写路径无关）。
         * 空串永远进不到集合判断：登记与查冲突两侧都先 `filter { it.isNotEmpty() }`（空串等价于不声明）。
         */
        val WHOLE_WORKSPACE = setOf("*", ".", "")
        /** 容器内 `~` 恒为 /root（proot 根里的家目录）。 */
        const val CONTAINER_HOME = "/root"
        /** 容器内工作区根（`~/workspace`）。相对路径声明的基准。 */
        const val CONTAINER_WORKSPACE = "$CONTAINER_HOME/workspace"
    }

    /** 子代理会话 id → 其声明的写路径（原样保存，比较时归一化）。 */
    private val leasedPaths = HashMap<String, List<String>>()

    /**
     * 登记租约。调用方应已在 [findConflict] 确认无冲突。
     * 空列表表示只读任务，不登记（[pathsFor] 随后返回 null，写工具据此不做限制）。
     */
    @Synchronized
    fun acquire(subSessionId: String, writePaths: List<String>) {
        val cleaned = writePaths.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        leasedPaths[subSessionId] = cleaned
        FileLogger.i(TAG, "登记写租约: $subSessionId -> $cleaned")
    }

    /** 主动释放（删除子代理时调用；正常运行结束时无需调用，靠活跃集合自动失效）。 */
    @Synchronized
    fun release(subSessionId: String) {
        if (leasedPaths.remove(subSessionId) != null) {
            FileLogger.i(TAG, "释放写租约: $subSessionId")
        }
    }

    /**
     * 该会话声明的写路径；返回 null 表示**未声明**（只读任务，写工具不应做任何租约限制）。
     */
    @Synchronized
    fun pathsFor(sessionId: String): List<String>? = leasedPaths[sessionId]

    /**
     * 找出声明里那些「归一后越出工作区、因而不可能覆盖任何文件」的相对路径（如 `..`、`../x`）。
     *
     * 为何要显式拒绝：这类声明会被登记成一条覆盖不了任何东西的哑租约，其子代理连工作区内的
     * 合法写入也全被拒，且日志里只有一条「登记写租约」、看不出所以然。创建期拦下并告知
     * 「要不受限请传 `*`」，比让它跑到写时才失败可查得多。
     *
     * 只针对**相对**声明：绝对路径（`/root/..`）与 `~` 展开后的越界是调用方显式指定，保持原行为。
     */
    fun impossibleRelativePaths(writePaths: List<String>): List<String> = writePaths
        .map { it.trim() }
        .filter { it.isNotEmpty() && !isWholeWorkspace(it) && !it.startsWith("~") && !it.startsWith("/") }
        .filter { raw -> !isAnchoredInWorkspace(collapse("$CONTAINER_WORKSPACE/$raw")) }

    /**
     * 找出与新任务 [writePaths] 冲突的、仍活跃的子代理会话 id。
     * 返回空集表示可安全创建；非空表示调用方应拒绝创建并提示等待。
     */
    @Synchronized
    fun findConflict(writePaths: List<String>): Set<String> {
        val cleaned = writePaths.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return emptySet()
        pruneInactive()

        val conflicts = mutableSetOf<String>()
        leasedPaths.forEach { (subSessionId, existing) ->
            if (existing.any { a -> cleaned.any { b -> pathsConflict(a, b) } }) {
                conflicts += subSessionId
            }
        }
        return conflicts
    }

    /**
     * 目标路径是否落在本会话的租约内。未声明租约（null）时返回 true（不做限制）。
     * 供写类工具在落盘前校验，作为提示词之外的第二道闸门。
     */
    @Synchronized
    fun isWithinLease(sessionId: String, targetPath: String): Boolean {
        val declared = leasedPaths[sessionId] ?: return true
        return declared.any { covered(it, targetPath) }
    }

    /** 清理已不活跃（已结束/被停/被删）子代理的租约条目。 */
    private fun pruneInactive() {
        val active = eventBus.activeSubSessionIds.value
        val stale = leasedPaths.keys.filter { it !in active }
        stale.forEach { leasedPaths.remove(it) }
        if (stale.isNotEmpty()) FileLogger.i(TAG, "清理失效租约 ${stale.size} 个")
    }

    /** 两条写路径是否冲突：任一为整工作区即冲突，否则互为前缀包含即冲突。 */
    private fun pathsConflict(a: String, b: String): Boolean {
        if (isWholeWorkspace(a) || isWholeWorkspace(b)) return true
        val na = normalizeWritePath(a)
        val nb = normalizeWritePath(b)
        return na == nb || na.startsWith("$nb/") || nb.startsWith("$na/")
    }

    /**
     * 声明的租约路径是否覆盖目标路径（用于写工具校验）。
     *
     * 整工作区声明（`*` / `.`）表示该子代理的写路径不受限制，此时一律判覆盖（工作区内外均可写）。
     */
    private fun covered(declared: String, target: String): Boolean {
        if (isWholeWorkspace(declared)) return true
        val nd = normalizeWritePath(declared)
        val nt = normalizeWritePath(target)
        return nt == nd || nt.startsWith("$nd/")
    }

    private fun isWholeWorkspace(path: String): Boolean = path.trim() in WHOLE_WORKSPACE

    /**
     * 按段消解 `.` 与 `..`，去掉尾部斜杠，让「同一路径的不同写法」得到同一结果。
     * 纯字符串处理、不访问文件系统，故无法覆盖运行期才可知的路径（如含变量展开的路径）。
     *
     * 先展开 `~` 再归一化：子代理的 readFile/editFile 返回路径是容器绝对路径（/root/...），
     * 而 write_paths 声明多为 `~/...`——若不展开，`~/workspace/app` 与 `/root/workspace/app`
     * 会被视为不同路径，导致声明正确仍 WRITE_LEASE_DENIED（已实测三连）。
     *
     * 相对路径也统一挂到工作区根下：`task(create)` 的 write_paths 常被写成
     * `app/src/...`（相对工作区），而工具落盘时用的是绝对路径，不补齐前缀同样会误拒
     * （2026-10-13 实测复现）。
     *
     * 但挂前缀前先做归一后的边界检查：像 `..`、`../.aicode` 这类声明，挂到工作区根后
     * 归一的结果会**跳出**工作区（`/root`、`/root/.aicode`），等于把家目录整棵树纳入租约
     * （凭据目录在内）。此类声明退回不挂前缀，保持相对形态——它不与任何**绝对**目标互为
     * 前缀，也就不会因此误放行（相对—相对之间仍可能互配，但越界路径在文件层即被
     * `WorkspacePathMapper.confineTo` / `confineRemote` 拦住）。
     *
     * 注意：这条「退回」只是防御性的兜底——此类声明会在 `task(create)` 时被
     * [impossibleRelativePaths] 提前拒掉，不会真的落到写工具那一步。
     */
    private fun normalizeWritePath(raw: String): String = when {
        raw == "~" -> collapse(CONTAINER_HOME)
        raw.startsWith("~/") -> collapse(CONTAINER_HOME + raw.substring(1))
        raw.startsWith("/") -> collapse(raw)
        else -> {
            val anchored = collapse("$CONTAINER_WORKSPACE/$raw")
            if (isAnchoredInWorkspace(anchored)) anchored else collapse(raw)
        }
    }

    /** 归一后的绝对路径是否落在工作区根之内（含根本身）。 */
    private fun isAnchoredInWorkspace(normalized: String): Boolean =
        normalized == CONTAINER_WORKSPACE || normalized.startsWith("$CONTAINER_WORKSPACE/")

    /** 纯字符串展开：按段消解 `.` 与 `..`，去重复斜杠，让同一路径的不同写法得到同一结果。 */
    private fun collapse(path: String): String {
        val absolute = path.startsWith("/")
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(segment)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }
}
