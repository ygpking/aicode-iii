package com.aicode.feature.virtualscreen.domain.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aicode.core.util.FileLogger

/**
 * 虚拟屏感知与操作服务。
 *
 * ## 为什么要独立一个 AccessibilityService
 *
 * 1. **系统只把 a11y 能力授给真实应用**。实测 `app_process` 自注册 `UiAutomation` 会被 SIGKILL，
 *    所以宿主（shell 进程）无法替代本服务。
 * 2. **必须用 `getWindowsOnAllDisplays()`**。单数 `getWindows()` 只返回当前活跃屏的窗口，
 *    看不到虚拟屏（实测），这是本服务存在的核心理由。
 *
 * ## 与主进程的进程隔离
 *
 * 声明在 `:vdscreen` 独立进程（见 AndroidManifest）。隔离目的是让「屏上操作」不随主进程被冻结/回收
 * 而中断；代价是需要跨进程取节点树，故对外经 Binder 提供服务（见 [VirtualScreenA11yBinder]）。
 *
 * ## 安全边界（合规要求）
 *
 * 本服务的 a11y 能力**只用于目标虚拟屏的 `displayId`**：所有读取与操作都以显式传入的
 * `displayId` 为参数，非该显示器的窗口一律不读。此约束写死在 [dump] / [clickByText] 里，
 * 不提供「读当前活跃屏」的入口，避免被当作全系统读屏工具滥用。
 */
class VirtualScreenA11yService : AccessibilityService() {

    companion object Registry {
        private const val TAG = "VDSA11y"

        /** 单次等待界面稳定的轮询上限。 */
        private const val WAIT_READY_TIMEOUT_MS = 5_000L
        private const val WAIT_READY_INTERVAL_MS = 200L

        private const val MAX_ANCESTOR_HOPS = 6
        private const val MAX_MATCHES = 50

        /** 截图回调的等待上限：takeScreenshot 是异步回调，而本服务对外是同步接口。 */
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L

        /**
         * 当前连接的服务实例。AccessibilityService 由系统单实例持有，用静态引用即可，
         * 无需再套一层 Binder（操作都在本进程内完成）。
         */
        @Volatile
        var instance: VirtualScreenA11yService? = null
            internal set

        /** 服务是否已启用并连接。供上层在未开启时给出「只能看不能点」的提示。 */
        val isRunning: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FileLogger.i(TAG, "虚拟屏无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意不做任何事件驱动逻辑：MIUI 的 do_freezer_trap 下 Handler.postDelayed 可能不执行，
        // 依赖定时回调的逻辑会静默失效。所有感知/操作都走「调用方主动请求」的同步路径。
    }

    override fun onInterrupt() {
        FileLogger.w(TAG, "虚拟屏无障碍服务被中断")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        FileLogger.i(TAG, "虚拟屏无障碍服务已销毁")
    }

    // ── 对外能力 ────────────────────────────────────────────────────────

    /**
     * 取指定显示器上的窗口列表。
     *
     * `flagRetrieveInteractiveWindows`（res/xml 中声明）是 `getWindows*()` 可用的前提，
     * 缺它会返回空列表。
     */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> {
        val all: SparseArray<List<AccessibilityWindowInfo>> = getWindowsOnAllDisplays()
        return all[displayId] ?: emptyList()
    }

    /** 取指定显示器最上层窗口的根节点。返回 null 表示该屏当前无可用窗口。 */
    fun rootOf(displayId: Int): AccessibilityNodeInfo? =
        windowsOn(displayId)
            .maxByOrNull { it.layer }
            ?.root

    /**
     * 导出指定显示器的节点树文本。
     *
     * @param waitReady 为 true 时先轮询等待节点树出现，用于「刚投屏、界面还在起」的场景。
     */
    fun dump(displayId: Int, waitReady: Boolean = false): String {
        val root = if (waitReady) awaitRoot(displayId) else rootOf(displayId)
        return NodeTreeFormatter.format(displayId, root)
    }

    private fun awaitRoot(displayId: Int): AccessibilityNodeInfo? {
        val deadline = android.os.SystemClock.elapsedRealtime() + WAIT_READY_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = rootOf(displayId)
            if (root != null && root.childCount > 0) return root
            Thread.sleep(WAIT_READY_INTERVAL_MS)
        }
        return rootOf(displayId)
    }

    /**
     * 在指定显示器上按文本点击。
     *
     * @param exact true 精确匹配 `text`/`contentDescription`，false 为包含匹配。
     * @param index 匹配到多个时的下标（按深度优先顺序），默认 0。
     * @return 成功触发点击返回 true。
     *
     * **点击目标的选择**：文本节点通常 `clickable=false`（实测 WLAN 的文本节点不可点，
     * 其祖先 `LinearLayout` 才可点），故先尝试节点本身，失败则上溯最近的可点击祖先。
     */
    fun clickByText(
        displayId: Int,
        text: String,
        exact: Boolean = false,
        index: Int = 0
    ): Boolean {
        val root = rootOf(displayId) ?: return false
        val matches = ArrayList<AccessibilityNodeInfo>()
        findByText(root, text, exact, matches)
        val target = matches.getOrNull(index) ?: return false

        // 先直接点，再上溯祖先。顺序不能反：若节点可点却先点祖先，会点到更大范围的容器（如整行），
        // 在多选列表里会误触到相邻项。
        if (performClick(target)) return true
        var ancestor = target.parent
        var hops = 0
        while (ancestor != null && hops < MAX_ANCESTOR_HOPS) {
            if (performClick(ancestor)) return true
            ancestor = ancestor.parent
            hops++
        }
        return false
    }

    /** 按 viewId 点击（如 `android:id/switch_widget`）。同样支持上溯祖先。 */
    fun clickById(displayId: Int, viewId: String): Boolean {
        val root = rootOf(displayId) ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return false
        val target = nodes.firstOrNull() ?: return false
        if (performClick(target)) return true
        var ancestor = target.parent
        var hops = 0
        while (ancestor != null && hops < MAX_ANCESTOR_HOPS) {
            if (performClick(ancestor)) return true
            ancestor = ancestor.parent
            hops++
        }
        return false
    }

    /** 按 bounds 中心点点击；作为文本/ id 都定位不到时的兜底。 */
    fun clickByBounds(displayId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val root = rootOf(displayId) ?: return false
        val targetRect = Rect(left, top, right, bottom)
        val hit = findClickableAt(root, targetRect) ?: return false
        return performClick(hit)
    }

    /**
     * 向可编辑且已聚焦的输入框写入文本。
     *
     * 直接 `ACTION_SET_TEXT` 而非模拟按键：虚拟屏非焦点屏、无输入视口（实测 `input -d` 无效），
     * 模拟按键不可靠；`ACTION_SET_TEXT` 直接作用于节点，绕过 IME。
     */
    fun setText(displayId: Int, text: String): Boolean {
        val root = rootOf(displayId) ?: return false
        val editable = findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    /**
     * 在指定显示器上做一次滑动（坐标相对该显示器）。
     *
     * **必须 `setDisplayId`**：`GestureDescription.Builder` 的 `mDisplayId` 默认是
     * `Display.DEFAULT_DISPLAY`（=0，即物理屏，已查 AOSP 源码确认）。不设的话手势会发到物理屏，
     * 而坐标是虚拟屏的——既滑不动虚拟屏，又会在用户屏幕上乱点乱滑，直接破「不影响用手机」的承诺。
     *
     * `setDisplayId` 是 API 30 起，比本模块其他调用的门槛高，故单处做版本门。
     */
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .setDisplayId(displayId)
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /**
     * 截取指定显示器的画面。
     *
     * 为什么要截图：节点树给出的是**语义**，但有内容它表达不了——图像、画布
     * （Canvas/WebView/游戏）、以及「布局没错但就是显示异常」的视觉问题。taixu 的
     * 实时视频流需要额外服务端编码器，这里不做；单帧截图已足够「让 AI 看见」。
     *
     * 实现约束：
     * - `takeScreenshot(displayId, executor, callback)` 是 **API 34+** 且需服务声明
     *   `canTakeScreenshot`（见 res/xml/virtual_screen_a11y.xml），否则调用失败；
     * - 它是**异步回调**，而本服务对外是同步接口，故用 latch 等待（超时即放弃，
     *   不让调用方无限挂住）；
     * - 返回的硬件位图需 copy 成软件位图后才能安全压缩：`HardwareBitmap` 在部分设备上
     *   用 `Bitmap.compress` 会抛异常，且脱离回调后会失效。同时必须 `recycle()`，
     *   否则大图会稳定泄漏 native 内存。
     *
     * 线程：可用任意线程调用，**不要放主线程**——本方法会阻塞等待回调，最长
     * [SCREENSHOT_TIMEOUT_MS]。调用方（工具层）应放在 IO 调度器上。
     */
    fun screenshot(displayId: Int, quality: Int = 90): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            FileLogger.w(TAG, "截图需要 Android 14(API 34)+，当前 SDK=${android.os.Build.VERSION.SDK_INT}")
            return null
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var png: ByteArray? = null
        var failure: String? = null
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                displayId,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        // 回调可能不在主线程，位图操作在此线程内完成即可。
                        runCatching {
                            val hardware = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            // 先 copy 成软件位图：硬件位图不能直接 compress，且 buffer 释放后即失效。
                            val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                            // 不用 use{}：Bitmap 是否实现 AutoCloseable 随 API 而异，
                            // 显式 try/finally 更稳，也保证异常路径同样回收。
                            if (software != null) {
                                try {
                                    val out = java.io.ByteArrayOutputStream()
                                    software.compress(Bitmap.CompressFormat.PNG, quality, out)
                                    png = out.toByteArray()
                                } finally {
                                    software.recycle()
                                }
                            }
                        }.onFailure { failure = "编码截图为 PNG 失败: ${it.message}" }
                        // HardwareBuffer 必须显式关闭，否则 native 内存泄漏。
                        runCatching { result.hardwareBuffer.close() }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failure = "系统拒绝截图（errorCode=$errorCode）"
                        latch.countDown()
                    }
                }
            )
            if (!latch.await(SCREENSHOT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                FileLogger.w(TAG, "截图超时（displayId=$displayId，${SCREENSHOT_TIMEOUT_MS}ms）")
                return null
            }
        } catch (t: Throwable) {
            FileLogger.w(TAG, "截图调用失败（displayId=$displayId）: $t")
            return null
        } finally {
            executor.shutdown()
        }
        failure?.let { FileLogger.w(TAG, "截图失败（displayId=$displayId）: $it") }
        return png
    }

    // ── 内部查找 ────────────────────────────────────────────────────────

    private fun performClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isClickable) return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    private fun findByText(
        node: AccessibilityNodeInfo?,
        text: String,
        exact: Boolean,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null || out.size >= MAX_MATCHES) return
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        val matched = listOfNotNull(nodeText, nodeDesc).any {
            if (exact) it == text else it.contains(text)
        }
        if (matched) out.add(node)
        for (i in 0 until node.childCount) findByText(node.getChild(i), text, exact, out)
    }

    /** 找包含/命中目标矩形的可点击节点（取最深的，即最精确的那个）。 */
    private fun findClickableAt(node: AccessibilityNodeInfo?, target: Rect): AccessibilityNodeInfo? {
        if (node == null) return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (!Rect.intersects(rect, target)) return null
        for (i in 0 until node.childCount) {
            findClickableAt(node.getChild(i), target)?.let { return it }
        }
        return node.takeIf { it.isClickable }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }
}
