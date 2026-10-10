package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判别层测试。用例对照移植来源（proof 的 classifier.py + truthguard 的 check-exit-code.sh）
 * 逐条验证，重点在**误判方向**：日常语句里的 done/fixed/通过 不能当成完成声明。
 */
class ClaimClassifierTest {

    // ── 真声明必须认出来 ────────────────────────────────────────────

    @Test
    fun `中文验证通过声明`() {
        assertTrue(ClaimClassifier.claimsVerification("测试通过。"))
        assertTrue(ClaimClassifier.claimsVerification("编译通过，产物已生成。"))
        assertTrue(ClaimClassifier.claimsVerification("全部用例均通过。"))
    }

    @Test
    fun `英文验证通过声明`() {
        assertTrue(ClaimClassifier.claimsVerification("All tests passed."))
        assertTrue(ClaimClassifier.claimsVerification("Build successful."))
        assertTrue(ClaimClassifier.claimsVerification("tests pass"))
    }

    @Test
    fun `中文变更声明`() {
        assertTrue(ClaimClassifier.claimsChange("已修复该问题。"))
        assertTrue(ClaimClassifier.claimsChange("改好了这个 bug。"))
        assertTrue(ClaimClassifier.claimsChange("已完成修改。"))
    }

    @Test
    fun `英文变更声明`() {
        assertTrue(ClaimClassifier.claimsChange("Fixed the bug."))
        assertTrue(ClaimClassifier.claimsChange("All done."))
        assertTrue(ClaimClassifier.claimsChange("it works now"))
        // 补齐的英文变体（对照 proof 的 _CHANGE_EXTRA 整组）
        assertTrue(ClaimClassifier.claimsChange("I've updated the parser."))
        assertTrue(ClaimClassifier.claimsChange("It is now implemented."))
        assertTrue(ClaimClassifier.claimsChange("This is fixed."))
        assertTrue(ClaimClassifier.claimsChange("the crash is fixed"))
        assertTrue(ClaimClassifier.claimsChange("All set."))
    }

    @Test
    fun `裸 deployed 算变更但未来态被抑制`() {
        assertTrue(ClaimClassifier.claimsChange("Deployed to production."))
        assertFalse(ClaimClassifier.claimsChange("will be deployed to production"))
        assertFalse(ClaimClassifier.claimsChange("to be deployed"))
    }

    // ── 误判方向：这些都不算声明 ────────────────────────────────────

    @Test
    fun `日常用语不是完成声明`() {
        // 对照 proof 的注释：NOT "done button", "not done", "done yet"
        assertFalse(ClaimClassifier.claimsChange("The done button is broken."))
        assertFalse(ClaimClassifier.claimsChange("Not done yet."))
        assertFalse(ClaimClassifier.claimsVerification("The deadline has passed."))
    }

    @Test
    fun `纯分析收尾不算变更声明`() {
        assertFalse(ClaimClassifier.claimsChange("已完成分析，结论如上。"))
        assertFalse(ClaimClassifier.claimsChange("代码库结构我梳理完了。"))
    }

    @Test
    fun `未完成态被抑制`() {
        assertFalse(ClaimClassifier.claimsVerification("尚未测试通过，需要继续。"))
        assertFalse(ClaimClassifier.claimsChange("还没修复。"))
        assertFalse(ClaimClassifier.claimsChange("let me investigate this bug"))
    }

    @Test
    fun `英文短语锚定不误伤`() {
        // 「works now」需 now 锚定；裸 works 不算变更
        assertFalse(ClaimClassifier.claimsChange("This API works over HTTP."))
    }

    // ── 点名文件 ────────────────────────────────────────────────────

    @Test
    fun `回顾性清单不算变更声明`() {
        // 「已完成的 X」是名词短语（罗列标题），不是「我做了」的断言。
        // 真实语料回归中这类误报占全部命中的绝大多数（发版步骤清单、方案回顾标题）。
        assertFalse(ClaimClassifier.claimsChange("**已完成的（都有实测证据）**"))
        assertFalse(ClaimClassifier.claimsChange("已完成的发版步骤"))
        assertFalse(ClaimClassifier.claimsChange("## 已完成的"))
    }

    @Test
    fun `非变更动作的完成态不算变更声明`() {
        // 核查/调研类动作的完成态（真实语料回归命中，均为误报）：
        // 动作词在「已完成」之前，故用后顾判定排除。
        assertFalse(ClaimClassifier.claimsChange("- schema 现状核查已完成（上表）"))
        assertFalse(ClaimClassifier.claimsChange("调研已完成"))
        // 但变更类完成态仍算，包括回顾性的（守卫本就该提醒「本回合没动手」）
        assertTrue(ClaimClassifier.claimsChange("已实现该功能。"))
        assertTrue(ClaimClassifier.claimsChange("方案批准后已实现完毕"))
    }

    @Test
    fun `疑问句与条件句不算声明`() {
        assertFalse(ClaimClassifier.claimsChange("这次改动是否已修复了问题？"))
        assertFalse(ClaimClassifier.claimsChange("是否已修复该缺陷"))
        assertFalse(ClaimClassifier.claimsChange("如果测试通过的话就可以发版。"))
        assertFalse(ClaimClassifier.claimsChange("测试通过了没有"))
    }

    @Test
    fun `提取点名的文件路径`() {
        val paths = ClaimClassifier.claimedPaths("已修改 app/src/Foo.kt 的逻辑。")
        assertTrue(paths.any { it.contains("Foo.kt") })
    }

    @Test
    fun `英文点名文件`() {
        val paths = ClaimClassifier.claimedPaths("Updated Foo.kt with the fix.")
        assertTrue(paths.any { it.contains("Foo.kt") })
    }

    // ── 引用/转述不作数（真机两次误伤后补）────────────────────────

    @Test
    fun `代码块内的引用话术不算声明`() {
        val msg = "以下是给用户的演示话术：\n```\n已修复该问题，全部测试通过。\n```\n请按上面步骤验证。"
        assertFalse(ClaimClassifier.claimsChange(msg))
        assertFalse(ClaimClassifier.claimsVerification(msg))
    }

    @Test
    fun `贴 git 历史提交不算声明`() {
        val msg = "历史提交：\n```\na1b2c3 fixed the crash in parser\n```\n以上为已有记录。"
        assertFalse(ClaimClassifier.claimsChange(msg))
    }

    @Test
    fun `代码块外真实声明仍命中`() {
        assertTrue(ClaimClassifier.claimsChange("已修复该问题。\n```\n全部测试通过\n```"))
    }

    @Test
    fun `未闭合代码块剥到文末`() {
        // 锁死「剥到文末」语义——若改成「未闭合不剥」，真机第 1 次误伤会复发。
        assertFalse(ClaimClassifier.claimsChange("说明如下：\n```\n已修复该问题"))
    }

    @Test
    fun `行内 code 不被剥离`() {
        // CLAIMED_PATH_RE 依赖反引号包裹路径，剥掉会让路径声明整体失配。
        assertTrue(ClaimClassifier.claimsChange("已修改 `app/src/Foo.kt` 的逻辑。"))
    }

    @Test
    fun `引文标记句不算声明`() {
        assertFalse(ClaimClassifier.claimsChange("上面引用的「已修复」是演示话术，并非本轮改动。"))
        assertFalse(ClaimClassifier.claimsChange("守卫提示里说我「声称修改了 Foo.kt」。"))
        assertTrue(ClaimClassifier.claimsChange("已修复该问题。"))
    }

    @Test
    fun `已更新记忆不算变更声明`() {
        assertFalse(ClaimClassifier.claimsChange("已更新记忆。"))
        assertFalse(ClaimClassifier.claimsChange("已更新了记忆。"))
        assertTrue(ClaimClassifier.claimsChange("已更新 ClaimClassifier.kt。"))
    }

    // ── 记忆域用句子级判断（宾语正则有词序/修饰盲区）──────────────

    @Test
    fun `记忆域的各种词序与修饰都不算变更声明`() {
        assertFalse(ClaimClassifier.claimsChange("已更新完记忆。"))
        assertFalse(ClaimClassifier.claimsChange("已更新好了记忆。"))
        assertFalse(ClaimClassifier.claimsChange("已更新了长期记忆。"))
        assertFalse(ClaimClassifier.claimsChange("已修复完备忘。"))
        assertFalse(ClaimClassifier.claimsChange("已更新完待办事项。"))
        // 前置宾语——宾语正则永远看不见（前瞻只看动词后）
        assertFalse(ClaimClassifier.claimsChange("记忆已更新。"))
        // 跨空白修饰
        assertFalse(ClaimClassifier.claimsChange("已更新了 3 处记忆"))
    }

    @Test
    fun `带路径的记忆句仍算变更声明`() {
        // 句里带文件路径就不是纯记忆域——混合句要拦。
        assertTrue(ClaimClassifier.claimsChange("已更新了长期记忆和 Foo.kt"))
        assertTrue(ClaimClassifier.claimsChange("已更新 memory.py"))
    }

    @Test
    fun `英文 memory 相关的真实修复不被排除`() {
        // memory 不进记忆域词表：「已修复 memory leak」是真实的修复声明。
        assertTrue(ClaimClassifier.claimsChange("已修复 memory leak。"))
        assertTrue(ClaimClassifier.claimsChange("已更新了 memory 缓存。"))
        // 已知代价（复核定案）：英文宾语无路径，判为声明 → R2 低频误报一次。
        assertTrue(ClaimClassifier.claimsChange("已更新 memory。"))
    }

    @Test
    fun `回顾锚词句不算本轮声明`() {
        assertFalse(ClaimClassifier.claimsChange("上一轮已修复该问题。"))
        assertFalse(ClaimClassifier.claimsChange("上一回合我更新了 Foo.kt。"))
        assertFalse(ClaimClassifier.claimsChange("上一步已修改完成。"))
        assertFalse(ClaimClassifier.claimsChange("此前已解决了该缺陷。"))
        // 「刚才/前面」刻意不进词表：它们可指本回合真声明或位置指代。
        assertTrue(ClaimClassifier.claimsChange("刚才已修复该问题。"))
    }
}
