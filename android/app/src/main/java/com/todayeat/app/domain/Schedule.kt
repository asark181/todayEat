package com.todayeat.app.domain

import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.data.Repeat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 纯逻辑：哪天该吃、下次什么时候提醒、某天算什么状态。不依赖 Android，方便单测。 */
object Schedule {

    fun startOf(plan: PlanEntity): LocalDate =
        runCatching { LocalDate.parse(plan.startDate) }.getOrDefault(LocalDate.MIN)

    fun isDue(plan: PlanEntity, date: LocalDate): Boolean {
        if (plan.deleted || !plan.active) return false
        val start = startOf(plan)
        if (date.isBefore(start)) return false
        val dow = date.dayOfWeek.value
        return when (plan.repeatKind) {
            Repeat.DAILY -> true
            Repeat.WEEKDAYS -> dow in 1..5
            Repeat.WEEKENDS -> dow >= 6
            Repeat.CUSTOM -> dow in plan.weekdaySet
            Repeat.INTERVAL -> ChronoUnit.DAYS.between(start, date) % plan.intervalDays.coerceAtLeast(1) == 0L
        }
    }

    fun timeOf(plan: PlanEntity): LocalTime = LocalTime.of(plan.hour.coerceIn(0, 23), plan.minute.coerceIn(0, 59))

    /** 严格晚于 [now] 的下一次提醒时刻；一年内找不到（比如自选星期一个都没选）返回 null */
    fun nextFire(plan: PlanEntity, now: LocalDateTime): LocalDateTime? {
        if (plan.deleted || !plan.active) return null
        var day = now.toLocalDate()
        repeat(400) {
            if (isDue(plan, day)) {
                val at = day.atTime(timeOf(plan))
                if (at.isAfter(now)) return at
            }
            day = day.plusDays(1)
        }
        return null
    }

    fun describe(plan: PlanEntity): String = when (plan.repeatKind) {
        Repeat.DAILY -> "每天"
        Repeat.WEEKDAYS -> "工作日"
        Repeat.WEEKENDS -> "周末"
        Repeat.CUSTOM -> {
            val set = plan.weekdaySet.sorted()
            if (set.isEmpty()) "未选星期" else "每周" + set.joinToString("、") { WEEK_SHORT[it - 1] }
        }
        Repeat.INTERVAL -> if (plan.intervalDays <= 1) "每天" else "每 ${plan.intervalDays} 天"
    }

    val WEEK_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")
}

enum class DayState {
    /** 该吃的都吃了 */
    ALL,
    /** 吃了一部分 */
    PARTIAL,
    /** 过去的日子，一个都没吃 */
    MISSED,
    /** 今天，提醒时间已过还没吃 */
    DUE,
    /** 今天，还没到提醒时间 */
    PENDING,
    /** 这天不用吃 */
    NONE,
    /** 还没到的日子 */
    FUTURE
}

data class DayInfo(
    val date: LocalDate,
    val state: DayState,
    val due: List<PlanEntity>,
    val doses: List<DoseEntity>
) {
    fun doseFor(planUid: String): DoseEntity? = doses.firstOrNull { it.planUid == planUid }
}

object Days {

    /** [since] 之前的日子不评判（装 App 之前没有记录，不能算漏吃） */
    fun info(date: LocalDate, plans: List<PlanEntity>, doses: List<DoseEntity>, now: LocalDateTime, since: LocalDate? = null): DayInfo {
        val today = now.toLocalDate()
        val dayDoses = doses.filter { !it.deleted && it.date == date.toString() }
        val due = if (since != null && date.isBefore(since)) emptyList() else plans.filter { Schedule.isDue(it, date) }
        val takenDue = due.count { p -> dayDoses.any { it.planUid == p.uid } }
        val state = when {
            date.isAfter(today) -> if (due.isEmpty()) DayState.NONE else DayState.FUTURE
            due.isEmpty() -> if (dayDoses.isNotEmpty()) DayState.ALL else DayState.NONE
            takenDue == due.size -> DayState.ALL
            date.isBefore(today) -> if (takenDue > 0) DayState.PARTIAL else DayState.MISSED
            // 今天：只要有一种已经过了提醒时间还没吃，就算「该吃了」
            due.any { p -> dayDoses.none { it.planUid == p.uid } && !now.toLocalTime().isBefore(Schedule.timeOf(p)) } ->
                if (takenDue > 0) DayState.PARTIAL else DayState.DUE
            takenDue > 0 -> DayState.PARTIAL
            else -> DayState.PENDING
        }
        return DayInfo(date, state, due, dayDoses)
    }

    /**
     * 连续没落下的天数：从今天（今天还没吃完就从昨天）往回数，不用吃的日子跳过，
     * 遇到漏吃或部分就停。只看 [earliest] 之后，避免数到装 App 之前。
     */
    fun streak(plans: List<PlanEntity>, doses: List<DoseEntity>, now: LocalDateTime, earliest: LocalDate?): Int {
        if (earliest == null) return 0
        val today = now.toLocalDate()
        var day = today
        var count = 0
        val todayInfo = info(today, plans, doses, now)
        if (todayInfo.state == DayState.ALL && todayInfo.due.isNotEmpty()) count++
        day = day.minusDays(1)
        while (!day.isBefore(earliest)) {
            val i = info(day, plans, doses, now)
            when (i.state) {
                DayState.ALL -> if (i.due.isNotEmpty()) count++
                DayState.NONE -> Unit
                else -> return count
            }
            day = day.minusDays(1)
        }
        return count
    }
}
