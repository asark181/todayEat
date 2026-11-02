package com.todayeat.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 重复方式；名称会写进同步快照，不要改 */
enum class Repeat { DAILY, WEEKDAYS, WEEKENDS, CUSTOM, INTERVAL }

/** 一种药和它的提醒计划 */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val uid: String,
    val name: String,
    val dose: String = "",
    val hour: Int = 6,
    val minute: Int = 30,
    val repeat: String = Repeat.DAILY.name,
    /** CUSTOM 时选中的星期，1=周一 … 7=周日，逗号分隔 */
    val weekdays: String = "1,2,3,4,5",
    /** INTERVAL 时每隔几天，从 startDate 起算 */
    val intervalDays: Int = 2,
    /** ISO 日期，计划从这天开始生效 */
    val startDate: String,
    /** 没点「已吃」时是否再提醒 */
    val renag: Boolean = true,
    val renagMinutes: Int = 10,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false
) {
    val repeatKind: Repeat get() = runCatching { Repeat.valueOf(repeat) }.getOrDefault(Repeat.DAILY)
    val weekdaySet: Set<Int> get() = weekdays.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
}

/** 一次「吃过了」的记录；同一计划同一天只应有一条未删除的 */
@Entity(tableName = "doses", indices = [Index(value = ["planUid", "date"])])
data class DoseEntity(
    @PrimaryKey val uid: String,
    val planUid: String,
    /** ISO 日期，这条记录算在哪一天 */
    val date: String,
    val takenAt: Long,
    val note: String = "",
    /** 照片 id（32 位小写 hex），逗号分隔 */
    val photos: String = "",
    val updatedAt: Long,
    val deleted: Boolean = false
) {
    val photoIds: List<String> get() = photos.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
