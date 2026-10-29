package com.todayeat.app

import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.data.Repeat
import com.todayeat.app.domain.DayState
import com.todayeat.app.domain.Days
import com.todayeat.app.domain.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleTest {
    private fun plan(repeat: Repeat = Repeat.DAILY, start: String = "2026-10-01", weekdays: String = "1,2,3,4,5", interval: Int = 2, uid: String = "p1") =
        PlanEntity(uid = uid, name = "药", hour = 6, minute = 30, repeat = repeat.name, weekdays = weekdays, intervalDays = interval, startDate = start, updatedAt = 0)

    private fun dose(date: String, uid: String = "d$date", plan: String = "p1") =
        DoseEntity(uid = uid, planUid = plan, date = date, takenAt = 1, updatedAt = 1)

    // 2026-10-29 是周四
    private val thu = LocalDate.of(2026, 10, 29)

    @Test fun weekdaysSkipWeekend() {
        val p = plan(Repeat.WEEKDAYS)
        assertTrue(Schedule.isDue(p, thu))
        assertFalse(Schedule.isDue(p, LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDateTime.of(2026, 11, 2, 6, 30), Schedule.nextFire(p, LocalDateTime.of(2026, 10, 30, 7, 0)))
    }

    @Test fun intervalCountsFromStart() {
        val p = plan(Repeat.INTERVAL, start = "2026-10-01", interval = 3)
        assertTrue(Schedule.isDue(p, LocalDate.of(2026, 10, 4)))
        assertFalse(Schedule.isDue(p, LocalDate.of(2026, 10, 5)))
        assertFalse(Schedule.isDue(p, LocalDate.of(2026, 9, 28)))
    }

    @Test fun customWithNoDaysNeverFires() {
        assertNull(Schedule.nextFire(plan(Repeat.CUSTOM, weekdays = ""), LocalDateTime.of(2026, 10, 29, 5, 0)))
    }

    @Test fun nextFireIsStrictlyLater() {
        val p = plan()
        assertEquals(LocalDateTime.of(2026, 10, 30, 6, 30), Schedule.nextFire(p, LocalDateTime.of(2026, 10, 29, 6, 30)))
        assertEquals(LocalDateTime.of(2026, 10, 29, 6, 30), Schedule.nextFire(p, LocalDateTime.of(2026, 10, 29, 6, 29)))
    }

    @Test fun todayStates() {
        val p = plan()
        assertEquals(DayState.PENDING, Days.info(thu, listOf(p), emptyList(), thu.atTime(6, 0)).state)
        assertEquals(DayState.DUE, Days.info(thu, listOf(p), emptyList(), thu.atTime(7, 0)).state)
        assertEquals(DayState.ALL, Days.info(thu, listOf(p), listOf(dose("2026-10-29")), thu.atTime(7, 0)).state)
        assertEquals(DayState.MISSED, Days.info(thu.minusDays(1), listOf(p), emptyList(), thu.atTime(7, 0)).state)
        assertEquals(DayState.NONE, Days.info(LocalDate.of(2026, 9, 1), listOf(p), emptyList(), thu.atTime(7, 0)).state)
    }

    @Test fun partialWithTwoPlans() {
        val a = plan(uid = "a")
        val b = plan(uid = "b")
        val info = Days.info(thu.minusDays(1), listOf(a, b), listOf(dose("2026-10-28", plan = "a")), thu.atTime(7, 0))
        assertEquals(DayState.PARTIAL, info.state)
    }

    @Test fun streakSkipsDaysOff() {
        val p = plan(Repeat.WEEKDAYS)
        // 周一到周四都吃了，周末本来不用吃
        val doses = listOf("2026-10-23", "2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29").map { dose(it) }
        assertEquals(5, Days.streak(listOf(p), doses, thu.atTime(8, 0), LocalDate.of(2026, 10, 23)))
    }

    @Test fun streakNotBrokenByTodayNotYetTaken() {
        val p = plan()
        val doses = listOf("2026-10-27", "2026-10-28").map { dose(it) }
        assertEquals(2, Days.streak(listOf(p), doses, thu.atTime(5, 0), LocalDate.of(2026, 10, 27)))
    }
}
