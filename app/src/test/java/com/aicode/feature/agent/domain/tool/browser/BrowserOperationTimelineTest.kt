package com.aicode.feature.agent.domain.tool.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserOperationTimelineTest {

    @Test
    fun appendKeepsOrder() {
        val tl = BrowserOperationTimeline(maxEntries = 10)
        var list = emptyList<BrowserOperation>()
        list = tl.append(list, op("navigate", fromAi = true))
        list = tl.append(list, op("click", fromAi = true))
        assertEquals(listOf("navigate", "click"), list.map { it.action })
    }

    @Test
    fun dropsOldestWhenExceedingCapacity() {
        val tl = BrowserOperationTimeline(maxEntries = 3)
        var list = emptyList<BrowserOperation>()
        repeat(5) { i -> list = tl.append(list, op("a$i")) }
        assertEquals(3, list.size)
        assertEquals(listOf("a2", "a3", "a4"), list.map { it.action })
    }

    @Test
    fun clearEmpties() {
        val tl = BrowserOperationTimeline()
        assertEquals(emptyList<BrowserOperation>(), tl.clear())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveCapacity() {
        BrowserOperationTimeline(maxEntries = 0)
    }

    @Test
    fun labelMapsKnownActions() {
        assertEquals("访问网页", BrowserActionLabels.labelOf("navigate"))
        assertEquals("点击元素", BrowserActionLabels.labelOf("click"))
        assertEquals("填写表单", BrowserActionLabels.labelOf("fill"))
        assertEquals("截图", BrowserActionLabels.labelOf("screenshot"))
        assertEquals("处理对话框", BrowserActionLabels.labelOf("dialog"))
    }

    @Test
    fun labelFallsBackForUnknown() {
        assertEquals("操作", BrowserActionLabels.labelOf("somethingNew"))
    }

    @Test
    fun operationDefaults() {
        val o = BrowserOperation(action = "navigate", label = "访问网页")
        assertEquals("", o.target)
        assertTrue(!o.isError)
        assertTrue(!o.fromAi)
    }

    private fun op(action: String, fromAi: Boolean = false) =
        BrowserOperation(action = action, label = "l", fromAi = fromAi)
}
