package com.todayeat.app

import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.sync.Snapshot
import com.todayeat.app.sync.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val plan = PlanEntity(uid = "p", name = "早上的药", startDate = "2026-10-01", updatedAt = 10)

    private fun dose(uid: String, takenAt: Long, updated: Long = takenAt, photos: String = "", deleted: Boolean = false) =
        DoseEntity(uid = uid, planUid = "p", date = "2026-10-29", takenAt = takenAt, photos = photos, updatedAt = updated, deleted = deleted)

    @Test fun firstUploadWhenRemoteEmpty() {
        val r = SyncMerge.merge(Snapshot(listOf(plan), listOf(dose("a", 100))), null, 1000)
        assertTrue(r.remoteOutdated)
        assertTrue(r.plansToWrite.isEmpty())
    }

    @Test fun newerWinsAndTombstonesPropagate() {
        val local = Snapshot(listOf(plan), listOf(dose("a", 100, updated = 100)))
        val remote = Snapshot(listOf(plan.copy(name = "改过", updatedAt = 20)), listOf(dose("a", 100, updated = 200, deleted = true)))
        val r = SyncMerge.merge(local, remote, 1000)
        assertEquals("改过", r.merged.plans.single().name)
        assertTrue(r.merged.doses.single().deleted)
        assertEquals(2, r.plansToWrite.size + r.dosesToWrite.size)
        assertFalse(r.remoteOutdated)
    }

    @Test fun twoDevicesSameDayKeepEarlierAndMergePhotos() {
        val local = Snapshot(listOf(plan), listOf(dose("a", 100, photos = "x")))
        val remote = Snapshot(listOf(plan), listOf(dose("b", 90, photos = "y")))
        val r = SyncMerge.merge(local, remote, 1000)
        val live = r.merged.doses.filter { !it.deleted }
        assertEquals(1, live.size)
        assertEquals("b", live.single().uid)
        assertEquals(listOf("y", "x"), live.single().photoIds)
        assertTrue(r.remoteOutdated)
        // 再合并一次应当稳定
        val again = SyncMerge.merge(r.merged, r.merged, 2000)
        assertFalse(again.remoteOutdated)
    }

    @Test fun jsonRoundTrip() {
        val s = Snapshot(listOf(plan.copy(weekdays = "1,3,5", dose = "1 片")), listOf(dose("a", 100, photos = "x,y")))
        assertEquals(s, SyncMerge.fromJson(SyncMerge.toJson(s)))
    }
}
