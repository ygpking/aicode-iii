package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 子代理派发追踪器：只提醒一次、派发过即沉默、按会话隔离、判定不成立时占位可回退。 */
class SubAgentDelegationTrackerTest {

    private val session = "tracker-test-session"

    @Test
    fun `只写文件从未派发时首次提醒第二次不再提醒`() {
        SubAgentDelegationTracker.reset(session)
        SubAgentDelegationTracker.recordWriteSuccess(session)

        assertTrue(SubAgentDelegationTracker.shouldNotifyAndMark(session))
        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(session))
    }

    @Test
    fun `已派发子代理且有写入时不提醒`() {
        SubAgentDelegationTracker.reset(session)
        SubAgentDelegationTracker.recordWriteSuccess(session)
        SubAgentDelegationTracker.recordDelegation(session)

        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(session))
    }

    @Test
    fun `从未写入时不提醒且占位可回退`() {
        SubAgentDelegationTracker.reset(session)

        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(session))

        // 占位必须已在判定不成立时回退，否则此后条件成立也不会提醒
        SubAgentDelegationTracker.recordWriteSuccess(session)
        assertTrue(SubAgentDelegationTracker.shouldNotifyAndMark(session))
    }

    @Test
    fun `reset后回到初始态可重新提醒`() {
        SubAgentDelegationTracker.reset(session)
        SubAgentDelegationTracker.recordWriteSuccess(session)
        assertTrue(SubAgentDelegationTracker.shouldNotifyAndMark(session))

        SubAgentDelegationTracker.reset(session)

        // 已提醒与已写入一并清空：仅记录写入后应再次可提醒
        SubAgentDelegationTracker.recordWriteSuccess(session)
        assertTrue(SubAgentDelegationTracker.shouldNotifyAndMark(session))
    }

    @Test
    fun `两个会话互不影响`() {
        val other = "tracker-test-session-b"
        SubAgentDelegationTracker.reset(session)
        SubAgentDelegationTracker.reset(other)

        SubAgentDelegationTracker.recordWriteSuccess(session)
        SubAgentDelegationTracker.recordWriteSuccess(other)

        assertTrue(SubAgentDelegationTracker.shouldNotifyAndMark(session))
        assertTrue(SubAgentDelegationTracker.shouldNotifyAndMark(other))
        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(session))
        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(other))
    }

    @Test
    fun `先派发再写文件仍不提醒`() {
        SubAgentDelegationTracker.reset(session)
        SubAgentDelegationTracker.recordDelegation(session)
        SubAgentDelegationTracker.recordWriteSuccess(session)

        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(session))
        // 再问一次也不因「占位回退」而变 true
        assertFalse(SubAgentDelegationTracker.shouldNotifyAndMark(session))
    }
}
