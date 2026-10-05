package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个 Skill。
 *
 * 目录经 [FileAccessProvider] 以容器路径访问，本地与远程（SSH）同一套逻辑。
 */
object SkillDirectoryScanner {
    private const val TAG = "SkillDirectoryScanner"

    /** 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）。 */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * 扫描 [root] 目录下所有合法技能，按名称排序。
     * 目录不存在时返回空列表；递归列举失败（IO 错误、远程工作区未连接等）时
     * 同样降级为空列表并记日志，不向上抛——技能扫描失败不该让调用方崩溃。
     */
    fun scan(provider: FileAccessProvider, root: String): List<Skill> = runCatching {
        val dirs = provider.listFilesRecursive(root, MAX_DEPTH)
            .filter { relative ->
                val name = relative.substringAfterLast('/')
                name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
            }
            .map { it.substringBeforeLast('/', "") }
            .distinct()

        val base = root.trimEnd('/')
        dirs.mapNotNull { relative ->
            val dirPath = if (relative.isEmpty()) base else "$base/$relative"
            // 单个坏技能只跳过自己，绝不连带整表：否则一个畸形 SKILL.md 会让全部技能消失。
            runCatching { SkillParser.parse(provider, dirPath) }
                .onFailure { FileLogger.w(TAG, "解析技能失败，已跳过: $dirPath", it) }
                .getOrNull()
        }.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描技能目录失败: $root", e)
        emptyList()
    }
}
