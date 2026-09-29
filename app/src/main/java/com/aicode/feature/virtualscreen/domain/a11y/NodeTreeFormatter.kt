package com.aicode.feature.virtualscreen.domain.a11y

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点树 → 供模型阅读的结构化文本。
 *
 * ## 为什么要裁剪
 *
 * 实测一块 1080x2400 的设置页有 **676 个节点**，其中绝大多数是纯布局容器
 * （无文本、无 id、不可点）。原样喂给模型既超长又无信息量。
 * 故只保留「模型据此能定位并操作」的字段，并给每个节点编号，让模型用序号引用，
 * **避免它复述坐标**（坐标一错，操作就打偏）。
 *
 * ## 裁剪规则
 *
 * - 保留：`className` / `text` / `contentDescription` / `viewIdResourceName` / `bounds` /
 *   `clickable` / `editable` / `scrollable` / `checkable`+`checked`
 * - 丢弃：不可见节点、无任何可读信息且不可交互的容器、系统内部类名（`android.widget.` 等前缀可简化）
 * - 去重：相邻重复的祖先链（`text` 完全相同的中间层）
 */
object NodeTreeFormatter {

    /** 单次 dump 的节点上限：避免超长界面（如长列表）把上下文撑爆。 */
    const val MAX_NODES = 400

    /** 单条文本字段上限，防止超长文案（如隐私政策）挤占预算。 */
    private const val MAX_TEXT_CHARS = 120

    /**
     * 把节点树格式化为多行文本。
     *
     * @return 形如：
     * ```
     * display=5 pkg=com.android.settings nodes=42
     * [0] FrameLayout clickable=true bounds=(0,0,1080,2400)
     * [1] TextView text="WLAN" id=android:id/title bounds=(48,120,540,180) clickable-ancestor=3
     * ```
     * 若界面无可读内容，返回带说明的单行，**不返回空串**——空串会被上层误判为「dump 失败」。
     */
    fun format(displayId: Int, root: AccessibilityNodeInfo?, maxNodes: Int = MAX_NODES): String {
        if (root == null) return "display=$displayId 节点树为空（可能应用未启动或界面尚未就绪）"

        val sb = StringBuilder()
        val counter = intArrayOf(0)
        val collected = ArrayList<NodeLine>()

        collect(root, 0, collected, maxNodes)

        val packages = collected.mapNotNull { it.packageName }.distinct()
        sb.append("display=").append(displayId)
            .append(" pkg=").append(packages.joinToString(",").ifEmpty { "?" })
            .append(" nodes=").append(collected.size)
            .append('\n')
        collected.forEachIndexed { index, line ->
            sb.append('[').append(index).append("] ").append(line.render())
            if (index < collected.size - 1) sb.append('\n')
        }
        if (collected.size >= maxNodes) {
            sb.append("\n… 已达 $maxNodes 节点上限，界面可能未完整展示")
        }
        return sb.toString()
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: ArrayList<NodeLine>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes || depth > MAX_DEPTH) return

        val text = node.text?.toString()?.take(MAX_TEXT_CHARS)
        val desc = node.contentDescription?.toString()?.take(MAX_TEXT_CHARS)
        val id = node.viewIdResourceName
        val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable

        // 只保留「有可读信息」或「可交互」的节点，其余容器直接跳过——
        // 但**仍要下钻子节点**，否则会把只包一层无用容器的真实内容一起丢掉。
        if (text.isNullOrBlank() && desc.isNullOrBlank() && id == null && !interactive) {
            for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1, out, maxNodes)
            return
        }

        out.add(
            NodeLine(
                depth = depth,
                className = node.className?.toString()?.substringAfterLast('.') ?: "?",
                text = text,
                desc = desc,
                viewId = id?.substringAfterLast('/'),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                checked = if (node.isCheckable) node.isChecked else null,
                packageName = node.packageName?.toString(),
                bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            )
        )

        for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1, out, maxNodes)
    }

    private const val MAX_DEPTH = 60

    private data class NodeLine(
        val depth: Int,
        val className: String,
        val text: String?,
        val desc: String?,
        val viewId: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val packageName: String?,
        val bounds: android.graphics.Rect
    ) {
        fun render(): String {
            val sb = StringBuilder()
            sb.append("  ".repeat(depth.coerceAtMost(8)))
            sb.append(className)
            if (!text.isNullOrBlank()) sb.append(" text=\"").append(text).append('"')
            if (!desc.isNullOrBlank()) sb.append(" desc=\"").append(desc).append('"')
            if (viewId != null) sb.append(" id=").append(viewId)
            if (clickable) sb.append(" clickable")
            if (editable) sb.append(" editable")
            if (scrollable) sb.append(" scrollable")
            if (checked != null) sb.append(if (checked) " checked" else " unchecked")
            sb.append(" bounds=(")
                .append(bounds.left).append(',').append(bounds.top).append(',')
                .append(bounds.right).append(',').append(bounds.bottom).append(')')
            return sb.toString()
        }
    }
}
