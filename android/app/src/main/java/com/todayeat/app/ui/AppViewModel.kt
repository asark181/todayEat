package com.todayeat.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.todayeat.app.TodayEatApp
import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.data.newId
import com.todayeat.app.domain.DayInfo
import com.todayeat.app.domain.Days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as TodayEatApp
    private val repo = app.repository
    private val prefs = application.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now.asStateFlow()

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    /** 正在用的药；数据库第一次读出来之前是 null */
    private val plansOrNull: StateFlow<List<PlanEntity>?> = repo.observePlans().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val plans: StateFlow<List<PlanEntity>> = plansOrNull.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val ready: StateFlow<Boolean> = plansOrNull.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 含已删除的药，用来给老记录显示药名 */
    val allPlans: StateFlow<Map<String, PlanEntity>> = repo.observeAllPlans().map { list -> list.associateBy { it.uid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch {
            while (true) {
                delay(20_000)
                tick()
            }
        }
    }

    fun tick() {
        _now.value = LocalDateTime.now()
    }

    fun doses(from: LocalDate, to: LocalDate): Flow<List<DoseEntity>> = repo.observeDoses(from, to)

    fun dayInfo(date: LocalDate, doses: List<DoseEntity>): DayInfo = Days.info(date, plans.value, doses, _now.value)

    // ───────── 记录 ─────────

    /** 大按钮：这天该吃还没记的全部记上。今天记现在的时间，过去的日子按提醒时间补记 */
    fun checkIn(info: DayInfo) {
        val left = info.due.filter { info.doseFor(it.uid) == null }
        val today = _now.value.toLocalDate()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            left.forEach { p ->
                val at = if (info.date == today) now else epoch(info.date, p.hour, p.minute)
                repo.markTaken(p.uid, info.date, at)
            }
        }
    }

    /** 新记录的默认值：这天还没记的第一种药；今天用现在的时间，别的日子用提醒时间 */
    fun newDose(date: LocalDate, info: DayInfo?): DoseEntity {
        val plan = info?.due?.firstOrNull { info.doseFor(it.uid) == null } ?: info?.due?.firstOrNull() ?: plans.value.firstOrNull()
        val at = when {
            date == LocalDate.now() -> System.currentTimeMillis()
            plan != null -> epoch(date, plan.hour, plan.minute)
            else -> epoch(date, 8, 0)
        }
        return DoseEntity(uid = newId(), planUid = plan?.uid ?: "", date = date.toString(), takenAt = at, updatedAt = 0)
    }

    fun saveDose(dose: DoseEntity) = viewModelScope.launch { repo.saveDose(dose) }

    fun deleteDose(uid: String) = viewModelScope.launch { repo.deleteDose(uid) }

    /** 导入一张照片，返回照片 id；读不出来返回 null */
    suspend fun importPhoto(uri: Uri): String? = withContext(Dispatchers.IO) { app.photos.import(uri) }

    // ───────── 药 ─────────

    fun newPlan(): PlanEntity = PlanEntity(
        uid = newId(), name = "", startDate = LocalDate.now().toString(),
        sortOrder = plans.value.size, updatedAt = System.currentTimeMillis()
    )

    fun savePlan(plan: PlanEntity) = viewModelScope.launch { repo.savePlan(plan) }

    fun deletePlan(uid: String) = viewModelScope.launch { repo.deletePlan(uid) }

    fun finishOnboarding(plan: PlanEntity) {
        viewModelScope.launch {
            repo.savePlan(plan)
            markOnboarded()
        }
    }

    /** 登录后云端已有药时直接跳过引导 */
    fun markOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboarded.value = true
    }

    companion object {
        private const val KEY_ONBOARDED = "onboarded"

        fun epoch(date: LocalDate, hour: Int, minute: Int): Long =
            date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
