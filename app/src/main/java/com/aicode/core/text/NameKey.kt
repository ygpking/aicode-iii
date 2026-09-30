package com.aicode.core.text

/**
 * 「名称即逻辑主键」的**唯一**归一约定。
 *
 * 为什么存在（根因 R2「归一化不对称」）：当字符串（技能名 / 子代理名 / MCP 名）作为
 * 主键使用时，写入侧与读取侧若做了**不一致**的归一（一个 `trim()` 了另一个没有），
 * 就会出现「写进去了、但按同样的名字删不掉/查不到」的**静默失效**——不报错、不崩溃，
 * 功能就是「没反应」。历史事故（commit `e5be8bc`）：禁用名单「写入按原样、启用按小写
 * remove」→ 技能永远无法重新启用。
 *
 * 此前各仓储各自实现：`SkillConfigRepository` / `AgentDefinitionConfigRepository` /
 * `McpConfigRepository` 都写了 `name.trim().lowercase()`，而若干**读取侧**却只有
 * `name.lowercase()`（漏了 trim）——两处口径本已不一致。统一收敛到本对象后，
 * 改归一规则只需改这一处。
 *
 * 注意：归一仅用于**匹配/索引**；展示、落盘与日志仍应使用原始名（见各调用点）。
 */
object NameKey {
    /** 归一键：大小写与首尾空白均无关。 */
    fun of(name: String): String = name.trim().lowercase()
}
