package com.todayeat.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 时间、日期的显示格式 */
object Copy {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun hm(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime().format(HM)
    fun hm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    /** 10月29日 周四 */
    fun day(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日 周${Schedule.WEEK_SHORT[date.dayOfWeek.value - 1]}"

    /** 今天 / 昨天 / 10月27日 周二 */
    fun dayShort(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> day(date)
    }
}
