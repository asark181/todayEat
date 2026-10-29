package com.todayeat.app.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 所有固定文案。平实、具体，像家里人提醒你。 */
object Copy {

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun hm(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime().format(HM)
    fun hm(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    data class Headline(val title: String, val sub: String)

    /** 首页和小组件的那一句话 */
    fun headline(info: DayInfo, now: LocalDateTime): Headline {
        val earliestDue = info.due.minByOrNull { Schedule.timeOf(it) }
        return when (info.state) {
            DayState.ALL -> {
                val last = info.doses.maxByOrNull { it.takenAt }
                val at = last?.let { hm(it.takenAt) } ?: ""
                val sleepy = now.hour < 9
                Headline("今天的药吃过了", if (sleepy) "$at · 安心再睡会儿" else "$at 吃的")
            }
            DayState.PARTIAL -> {
                val left = info.due.filter { p -> info.doses.none { it.planUid == p.uid } }
                Headline("还差 ${left.size} 样", left.joinToString("、") { it.name })
            }
            DayState.DUE -> Headline("今天还没吃", earliestDue?.let { "提醒时间是 ${hm(it.hour, it.minute)}" } ?: "")
            DayState.PENDING -> Headline("还没到时间", earliestDue?.let { "${hm(it.hour, it.minute)} 提醒你" } ?: "")
            DayState.NONE -> Headline("今天不用吃药", "好好休息")
            DayState.MISSED -> Headline("那天没有记", "")
            DayState.FUTURE -> Headline("还没到这天", "")
        }
    }

    fun greeting(now: LocalDateTime): String = when (now.hour) {
        in 4..8 -> "早"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }

    fun streakLine(days: Int): String? = when {
        days <= 1 -> null
        else -> "连续 $days 天没落下"
    }
}
