package com.aicode.feature.agent.domain.tool.browser

/**
 * 一次浏览器操作记录（AI 或用户触发）。
 *
 * @param action 原始 action 名（navigate/click/…），供定位。
 * @param label 中文动作标签（如「访问网页」），供 UI 直接展示。
 * @param target 操作目标摘要（URL/选择器/按键等），可能为空。
 * @param timestamp 发生时刻（epoch 毫秒）。
 * @param isError 该次操作是否失败。
 * @param fromAi 是否由 AI（browser 工具）触发；false 表示用户在界面上操作。
 */
data class BrowserOperation(
    val action: String,
    val label: String,
    val target: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val fromAi: Boolean = false,
)

/**
 * 浏览器 action → 中文动作标签。
 *
 * 刻意与 [BrowserTool] 的 actionEnum 对齐；未识别的一律回落到「操作」，不抛错。
 */
object BrowserActionLabels {

    private val LABELS: Map<String, String> = mapOf(
        "navigate" to "访问网页",
        "reload" to "刷新页面",
        "back" to "后退",
        "forward" to "前进",
        "evaluate" to "执行脚本",
        "click" to "点击元素",
        "fill" to "填写表单",
        "select" to "选择选项",
        "hover" to "悬停元素",
        "press" to "按键",
        "scroll" to "滚动页面",
        "wait" to "等待条件",
        "dialog" to "处理对话框",
        "getText" to "读取文本",
        "getHtml" to "读取页面",
        "getBackbone" to "分析页面结构",
        "screenshot" to "截图",
        "console" to "读取控制台",
        "newTab" to "新建标签页",
        "closeTab" to "关闭标签页",
        "selectTab" to "切换标签页",
        "listTabs" to "列出标签页",
    )

    fun labelOf(action: String): String = LABELS[action] ?: "操作"
}

/**
 * 浏览器操作时间线（内存态，容量有上限）。
 *
 * 由 [BrowserManager] 持有：AI 通过 browser 工具、用户在界面上的一切操作都记入此处，
 * 作为「AI 正在操作浏览器」实时操作栏的唯一真相源。**不持久化**（重启即清空）。
 *
 * 纯逻辑、零 Android 依赖，便于单测。线程安全由调用方通过"整体替换 + 不可变列表"保证。
 */
class BrowserOperationTimeline(private val maxEntries: Int = 100) {

    init {
        require(maxEntries > 0) { "maxEntries 必须为正" }
    }

    /** 追加一条操作，超出上限时丢弃最旧。返回新列表（不可变）。 */
    fun append(current: List<BrowserOperation>, operation: BrowserOperation): List<BrowserOperation> {
        val next = current + operation
        return if (next.size <= maxEntries) next else next.takeLast(maxEntries)
    }

    fun clear(): List<BrowserOperation> = emptyList()
}
