package com.todayeat.app.sync

import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import org.json.JSONArray
import org.json.JSONObject

data class Snapshot(val plans: List<PlanEntity>, val doses: List<DoseEntity>)

data class MergeResult(
    val merged: Snapshot,
    /** 需要写回本机的（新增或比本机新的） */
    val plansToWrite: List<PlanEntity>,
    val dosesToWrite: List<DoseEntity>,
    /** 合并结果和云端不一样，需要上传 */
    val remoteOutdated: Boolean
)

/** 快照格式与合并规则（docs/SPEC.md 第 3 节）。纯逻辑，有单测。 */
object SyncMerge {
    const val SCHEMA = 1

    fun merge(local: Snapshot, remote: Snapshot?, now: Long): MergeResult {
        val r = remote ?: Snapshot(emptyList(), emptyList())
        val plans = pickNewer(local.plans, r.plans, { it.uid }, { it.updatedAt })
        val doses = pickNewer(local.doses, r.doses, { it.uid }, { it.updatedAt })

        val localPlans = local.plans.associateBy { it.uid }
        val localDoses = local.doses.associateBy { it.uid }
        val plansToWrite = plans.filter { localPlans[it.uid] != it }
        val dosesToWrite = doses.filter { localDoses[it.uid] != it }

        val remotePlans = r.plans.associateBy { it.uid }
        val remoteDoses = r.doses.associateBy { it.uid }
        val outdated = remote == null ||
            plans.size != r.plans.size || doses.size != r.doses.size ||
            plans.any { remotePlans[it.uid] != it } || doses.any { remoteDoses[it.uid] != it }
        return MergeResult(Snapshot(plans, doses), plansToWrite, dosesToWrite, outdated)
    }

    private fun <T> pickNewer(a: List<T>, b: List<T>, id: (T) -> String, time: (T) -> Long): List<T> {
        val out = LinkedHashMap<String, T>()
        for (x in a + b) {
            val cur = out[id(x)]
            if (cur == null || time(x) > time(cur)) out[id(x)] = x
        }
        return out.values.toList()
    }

    fun toJson(s: Snapshot): String {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("plans", JSONArray().apply {
            s.plans.forEach { p ->
                put(JSONObject().apply {
                    put("uid", p.uid); put("name", p.name); put("dose", p.dose)
                    put("hour", p.hour); put("minute", p.minute); put("repeat", p.repeat)
                    put("weekdays", JSONArray(p.weekdaySet.sorted()))
                    put("intervalDays", p.intervalDays); put("startDate", p.startDate)
                    put("renag", p.renag); put("renagMinutes", p.renagMinutes)
                    put("active", p.active); put("sortOrder", p.sortOrder)
                    put("updatedAt", p.updatedAt); put("deleted", p.deleted)
                })
            }
        })
        o.put("doses", JSONArray().apply {
            s.doses.forEach { d ->
                put(JSONObject().apply {
                    put("uid", d.uid); put("planUid", d.planUid); put("date", d.date)
                    put("takenAt", d.takenAt); put("note", d.note)
                    put("photos", JSONArray(d.photoIds))
                    put("updatedAt", d.updatedAt); put("deleted", d.deleted)
                })
            }
        })
        return o.toString()
    }

    fun fromJson(json: String): Snapshot {
        val o = JSONObject(json)
        val plans = o.optJSONArray("plans") ?: JSONArray()
        val doses = o.optJSONArray("doses") ?: JSONArray()
        return Snapshot(
            plans = (0 until plans.length()).map { i ->
                val p = plans.getJSONObject(i)
                val wd = p.optJSONArray("weekdays") ?: JSONArray()
                PlanEntity(
                    uid = p.getString("uid"), name = p.optString("name", "药"), dose = p.optString("dose", ""),
                    hour = p.optInt("hour", 6), minute = p.optInt("minute", 30), repeat = p.optString("repeat", "DAILY"),
                    weekdays = (0 until wd.length()).joinToString(",") { wd.getInt(it).toString() },
                    intervalDays = p.optInt("intervalDays", 2), startDate = p.optString("startDate", "2000-01-01"),
                    renag = p.optBoolean("renag", true), renagMinutes = p.optInt("renagMinutes", 10),
                    active = p.optBoolean("active", true), sortOrder = p.optInt("sortOrder", 0),
                    updatedAt = p.optLong("updatedAt", 0), deleted = p.optBoolean("deleted", false)
                )
            },
            doses = (0 until doses.length()).map { i ->
                val d = doses.getJSONObject(i)
                val ph = d.optJSONArray("photos") ?: JSONArray()
                DoseEntity(
                    uid = d.getString("uid"), planUid = d.getString("planUid"), date = d.getString("date"),
                    takenAt = d.optLong("takenAt", 0), note = d.optString("note", ""),
                    photos = (0 until ph.length()).joinToString(",") { ph.getString(it) },
                    updatedAt = d.optLong("updatedAt", 0), deleted = d.optBoolean("deleted", false)
                )
            }
        )
    }
}
