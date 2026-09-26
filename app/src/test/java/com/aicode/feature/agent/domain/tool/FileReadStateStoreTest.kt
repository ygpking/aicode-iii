package com.aicode.feature.agent.domain.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileReadStateStoreTest {

    private val store = FileReadStateStore()
    private val session = "s1"
    private val path = "~/workspace/a.kt"

    @Test
    fun unknownFileIsNotReadAndNotFresh() {
        assertFalse(store.wasRead(session, path))
        assertFalse(store.isFresh(session, path, 100L))
    }

    @Test
    fun recordedFileIsReadAndFreshAtSameMtime() {
        store.record(session, path, 100L)
        assertTrue(store.wasRead(session, path))
        assertTrue(store.isFresh(session, path, 100L))
    }

    @Test
    fun changedMtimeMakesRecordStale() {
        store.record(session, path, 100L)
        assertFalse(store.isFresh(session, path, 200L))
    }

    @Test
    fun unknownMtimeSkipsFreshnessCheck() {
        store.record(session, path, FileReadStateStore.UNKNOWN_MTIME)
        assertTrue(store.wasRead(session, path))
        assertTrue(store.isFresh(session, path, 999L))
    }

    @Test
    fun zeroCurrentMtimeSkipsFreshnessCheck() {
        store.record(session, path, 100L)
        assertTrue(store.isFresh(session, path, 0L))
    }

    @Test
    fun sessionsAreIsolated() {
        store.record(session, path, 100L)
        assertFalse(store.wasRead("other", path))
        assertFalse(store.isFresh("other", path, 100L))
    }

    @Test
    fun blankSessionOrPathIsNotRecorded() {
        store.record("", path, 100L)
        store.record(session, "", 100L)
        assertFalse(store.wasRead(session, path))
        assertFalse(store.wasRead("", path))
    }

    @Test
    fun forgetRemovesOnlyTargetFile() {
        store.record(session, path, 100L)
        store.record(session, "other.kt", 100L)
        store.forget(session, path)
        assertFalse(store.wasRead(session, path))
        assertTrue(store.wasRead(session, "other.kt"))
    }

    @Test
    fun clearSessionDropsAllState() {
        store.record(session, path, 100L)
        store.clearSession(session)
        assertFalse(store.wasRead(session, path))
    }
}
