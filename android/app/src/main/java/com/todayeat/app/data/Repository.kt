package com.todayeat.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "")

/**
 * 所有写操作都从这里走，写完回调 [onChanged]（重排闹钟、刷新小组件、触发同步）。
 * 写入一律带新的 updatedAt，删除留墓碑，供多设备合并。
 */
class Repository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: (plansChanged: Boolean, fromSync: Boolean) -> Unit = { _, _ -> }
) {
    private val plans = db.plans()
    private val doses = db.doses()

    fun observePlans(): Flow<List<PlanEntity>> = plans.observeActive()
    fun observeDoses(from: LocalDate, to: LocalDate): Flow<List<DoseEntity>> = doses.observeRange(from.toString(), to.toString())
    fun observeFirstDoseDate(): Flow<String?> = doses.observeFirstDate()

    suspend fun activePlans(): List<PlanEntity> = plans.active()
    suspend fun plan(uid: String): PlanEntity? = plans.byUid(uid)
    suspend fun dosesBetween(from: LocalDate, to: LocalDate): List<DoseEntity> = doses.range(from.toString(), to.toString())
    suspend fun doseOn(planUid: String, date: LocalDate): DoseEntity? = doses.find(planUid, date.toString())

    suspend fun allPlansForAlarms(): List<PlanEntity> = plans.all()

    /** 同步用：包含墓碑的全部数据 */
    suspend fun snapshotAll(): Pair<List<PlanEntity>, List<DoseEntity>> = db.withTransaction { plans.all() to doses.all() }

    /** 写入合并结果；不再触发同步，免得来回打转 */
    suspend fun applyMerged(planRows: List<PlanEntity>, doseRows: List<DoseEntity>) {
        if (planRows.isEmpty() && doseRows.isEmpty()) return
        db.withTransaction {
            plans.upsertAll(planRows)
            doses.upsertAll(doseRows)
        }
        onChanged(planRows.isNotEmpty(), true)
    }

    /** 新设备第一次登录：本机还没打过卡，就丢掉引导时建的计划，以云端为准 */
    suspend fun dropLocalPlansIfFresh(): Boolean {
        if (doses.count() > 0) return false
        plans.clear()
        return true
    }

    suspend fun savePlan(plan: PlanEntity) {
        plans.upsert(plan.copy(updatedAt = clock()))
        onChanged(true, false)
    }

    suspend fun deletePlan(uid: String) {
        val p = plans.byUid(uid) ?: return
        plans.upsert(p.copy(deleted = true, updatedAt = clock()))
        onChanged(true, false)
    }

    /** 记「吃过了」；同一天已经记过就原样返回，不重复记 */
    suspend fun markTaken(planUid: String, date: LocalDate, takenAt: Long = clock()): DoseEntity {
        val made = db.withTransaction {
            doses.find(planUid, date.toString())?.let { return@withTransaction it to false }
            val d = DoseEntity(uid = newId(), planUid = planUid, date = date.toString(), takenAt = takenAt, updatedAt = clock())
            doses.upsert(d)
            d to true
        }
        if (made.second) onChanged(false, false)
        return made.first
    }

    suspend fun undo(doseUid: String) = editDose(doseUid) { it.copy(deleted = true) }

    suspend fun setTakenAt(doseUid: String, takenAt: Long) = editDose(doseUid) { it.copy(takenAt = takenAt) }

    suspend fun setNote(doseUid: String, note: String) = editDose(doseUid) { it.copy(note = note.take(200)) }

    suspend fun addPhoto(doseUid: String, photoId: String) = editDose(doseUid) {
        it.copy(photos = (it.photoIds + photoId).distinct().takeLast(MAX_PHOTOS).joinToString(","))
    }

    suspend fun removePhoto(doseUid: String, photoId: String) = editDose(doseUid) {
        it.copy(photos = (it.photoIds - photoId).joinToString(","))
    }

    private suspend fun editDose(uid: String, change: (DoseEntity) -> DoseEntity) {
        val d = doses.byUid(uid) ?: return
        doses.upsert(change(d).copy(updatedAt = clock()))
        onChanged(false, false)
    }

    companion object {
        const val MAX_PHOTOS = 4
    }
}
