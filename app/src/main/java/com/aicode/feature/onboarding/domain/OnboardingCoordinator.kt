package com.aicode.feature.onboarding.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 新手引导协调器：连接 UI 状态机 [OnboardingStateHolder] 与持久化。
 */
class OnboardingCoordinator(
    private val stateHolder: OnboardingStateHolder,
    private val onPersist: suspend (OnboardingStatus) -> Unit,
    private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "OnboardingCoordinator"
    }

    /**
     * 持久化状态。DataStore 写失败（磁盘满等）不能让异常从 [scope] 冒泡崩溃，
     * 引导本身是纯 UI 流程，落盘失败只记日志。
     */
    private fun persist(status: OnboardingStatus) {
        scope.launch {
            runCatching { onPersist(status) }
                .onFailure { FileLogger.w(TAG, "引导状态持久化失败: $status", it) }
        }
    }

    /** 启动引导判定：仅当未完成/进行中时激活。 */
    fun initialize(status: OnboardingStatus) {
        if (status == OnboardingStatus.IN_PROGRESS) {
            stateHolder.start()
        }
    }

    /** 推进到下一步（或完成）。 */
    fun nextStep() {
        stateHolder.nextStep()
        persist(stateHolder.state.value.status)
    }

    /** 回退或跳转到指定步骤（如关闭弹窗时回退到触发按钮）。 */
    fun goToStep(targetStep: OnboardingStep) {
        stateHolder.goToStep(targetStep)
        persist(stateHolder.state.value.status)
    }

    /** 用户主动跳过引导。 */
    fun skip() {
        stateHolder.skip()
        persist(OnboardingStatus.SKIPPED)
    }

    /** 用户完成引导。 */
    fun complete() {
        stateHolder.completeNow()
        persist(OnboardingStatus.COMPLETED)
    }

    /** 重置并重新启动引导（设置页触发）。 */
    fun resetAndStart() {
        stateHolder.start()
        persist(OnboardingStatus.IN_PROGRESS)
    }
}
