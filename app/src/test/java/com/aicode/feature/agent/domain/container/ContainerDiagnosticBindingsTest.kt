package com.aicode.feature.agent.domain.container

import android.content.Context
import android.content.ContextWrapper
import com.aicode.core.util.AILogger
import com.aicode.core.util.EventTrace
import com.aicode.core.util.FileLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断目录只读视图的绑定契约。
 *
 * 这层绑定把**日志与轨迹**（原本写在外部私有目录、容器不可见）挂进容器，
 * 让 AI 能读自己的运行痕迹。它有三个容易悄悄坏掉的地方，故用测试钉住：
 *
 * 1. **与产生端一致**：目录名必须与 [FileLogger]/[AILogger]/[EventTrace] 实际写入的位置相同。
 *    三者各自算自己的目录，任何一方改了子目录名而这里没跟上，挂进去就是空目录——
 *    而**不会报错**，只表现为「AI 说看不到日志」这种难以归因的现象。
 * 2. **源目录必须存在**：proot 的 `-b` 要求源路径已存在，而这三个目录都是首次写日志时才创建。
 *    若容器先于首次日志启动，挂载会失败，故绑定处必须 `mkdirs`。
 * 3. **目标命名规则**：统一在 `/root/.aicode/` 下、且带 `-view` 后缀——与可写数据区区分开
 *    （PRoot 下无法用权限真正阻止写入，只能靠命名语义 + 提示词约束降低误写风险）。
 *
 * 用 [FakeContext] 而非 Robolectric：本容器（Android 上的 PRoot）加载不了 Robolectric 的
 * conscrypt native 库，而这里只需 `getFilesDir`/`getExternalFilesDir`，手写假实现即可。
 */
class ContainerDiagnosticBindingsTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "diag-bindings-${System.nanoTime()}")
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val externalDir = File(root, "external").apply { mkdirs() }
    private val context = FakeContext(filesDir, externalDir)
    private val installer = ContainerInstaller(context, ContainerOsDetector(context))

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun bindingsCoverLogsTracesAndAiLogs() {
        val bindings = installer.diagnosticViewBindings

        assertEquals("诊断视图应覆盖 logs / ai-logs / traces 三类", 3, bindings.size)
    }

    @Test
    fun sourceDirNamesMatchProducers() {
        val sources = installer.diagnosticViewBindings.map { it.first.name }

        assertTrue("logs 目录名须与 FileLogger 一致（实际 $sources）", sources.contains(FileLogger.DIR_NAME))
        assertTrue("ai-logs 目录名须与 AILogger 一致（实际 $sources）", sources.contains(AILogger.DIR_NAME))
        assertTrue("traces 目录名须与 EventTrace 一致（实际 $sources）", sources.contains(EventTrace.DIR_NAME))
    }

    @Test
    fun sourceDirsAreCreatedSoProotCanBind() {
        // proot 的 -b 要求源路径存在；绑定处应已 mkdirs（这三个目录平时是首次写日志才创建）
        for ((src, dst) in installer.diagnosticViewBindings) {
            assertTrue("源目录应已创建：${src.absolutePath}（供 $dst 绑定）", src.isDirectory)
        }
    }

    @Test
    fun targetsAreUnderAicodeDirAndMarkedAsView() {
        for ((_, dst) in installer.diagnosticViewBindings) {
            assertTrue("目标应位于 /root/.aicode 下：$dst", dst.startsWith("/root/.aicode/"))
            assertTrue("目标名应带 -view 后缀，与可写数据区区分：$dst", dst.endsWith("-view"))
        }
    }

    @Test
    fun targetNamesMirrorSourceNamesToStayRecognizable() {
        // 目标名应能认出对应哪个源（logs → logs-view），避免挂错位置却无人察觉
        for ((src, dst) in installer.diagnosticViewBindings) {
            assertEquals("目标名应由源目录名派生：$src -> $dst", "/root/.aicode/${src.name}-view", dst)
        }
    }

    @Test
    fun fallsBackToFilesDirWhenExternalUnavailable() {
        // 外部私有目录不可用时（模拟取不到），应回退到 filesDir——与三处产生端的口径一致
        val noExternal = FakeContext(filesDir, external = null)
        val bindings = ContainerInstaller(noExternal, ContainerOsDetector(noExternal)).diagnosticViewBindings

        for ((src, _) in bindings) {
            assertEquals("应回退到 filesDir 下", filesDir, src.parentFile)
        }
    }

    /** 只覆写本测试用到的方法；`getExternalFilesDir(null)` 传 null 时表示「外部存储不可用」。 */
    private class FakeContext(
        private val files: File,
        private val external: File?,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getExternalFilesDir(type: String?): File? = external
    }
}
