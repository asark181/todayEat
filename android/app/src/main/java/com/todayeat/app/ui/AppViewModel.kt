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
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.DayInfo
import com.todayeat.app.domain.Days
import com.todayeat.app.domain.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HomeUi(
    val now: LocalDateTime,
    val today: DayInfo,
    val headline: Copy.Headline,
    val streak: Int,
    /** 开始吃药的第几天；没设开始日期为 null */
    val courseDay: Long?,
    val ready: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as TodayEatApp
    private val repo = app.repository
    private val prefs = application.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now.asStateFlow()

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    private val _courseStart = MutableStateFlow(prefs.getString(KEY_COURSE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    val courseStart: StateFlow<LocalDate?> = _courseStart.asStateFlow()

    val plans: StateFlow<List<PlanEntity>> = repo.observePlans().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val today = _now.map { it.toLocalDate() }.distinctUntilChanged()

    private val recent: Flow<List<DoseEntity>> = today.flatMapLatest { repo.observeDoses(it.minusDays(800), it.plusDays(1)) }

    private val firstDate = repo.observeFirstDoseDate().map { s -> s?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }

    private var loaded = false

    val home: StateFlow<HomeUi> = combine(_now, plans, recent, firstDate, _courseStart) { now, plans, doses, first, course ->
        val info = Days.info(now.toLocalDate(), plans, doses, now)
        loaded = true
        HomeUi(
            now = now,
            today = info,
            headline = Copy.headline(info, now),
            streak = Days.streak(plans, doses, now, first),
            courseDay = course?.let { ChronoUnit.DAYS.between(it, now.toLocalDate()) + 1 }?.takeIf { it > 0 },
            ready = true
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope, SharingStarted.Eagerly,
        HomeUi(LocalDateTime.now(), DayInfo(LocalDate.now(), com.todayeat.app.domain.DayState.NONE, emptyList(), emptyList()),
            Copy.Headline("", ""), 0, null, false)
    )

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

    fun monthDoses(month: YearMonth): Flow<List<DoseEntity>> = repo.observeDoses(month.atDay(1), month.atEndOfMonth())

    fun dayInfo(date: LocalDate, doses: List<DoseEntity>): DayInfo = Days.info(date, plans.value, doses, _now.value)

    // ───────── 打卡 ─────────

    fun take(planUid: String, date: LocalDate = _now.value.toLocalDate(), at: Long = System.currentTimeMillis()) {
        viewModelScope.launch { repo.markTaken(planUid, date, at) }
    }

    fun takeAll(plans: List<PlanEntity>, date: LocalDate) {
        viewModelScope.launch {
            val at = System.currentTimeMillis()
            plans.forEach { repo.markTaken(it.uid, date, at) }
        }
    }

    fun undo(doseUid: String) = viewModelScope.launch { repo.undo(doseUid) }

    fun setTime(dose: DoseEntity, hour: Int, minute: Int) = viewModelScope.launch {
        val at = LocalDate.parse(dose.date).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo.setTakenAt(dose.uid, at)
    }

    /** 补记过去某天：时间按计划的提醒时间算 */
    fun backfill(plan: PlanEntity, date: LocalDate) = viewModelScope.launch {
        val at = date.atTime(Schedule.timeOf(plan)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo.markTaken(plan.uid, date, at)
    }

    fun setNote(doseUid: String, note: String) = viewModelScope.launch { repo.setNote(doseUid, note) }

    /** 给某次记录加照片；还没记的话先记上 */
    suspend fun attachPhoto(planUid: String, date: LocalDate, uri: Uri): Boolean {
        val id = withContext(Dispatchers.IO) { app.photos.import(uri) } ?: return false
        val dose = repo.doseOn(planUid, date) ?: repo.markTaken(planUid, date)
        repo.addPhoto(dose.uid, id)
        return true
    }

    fun removePhoto(doseUid: String, photoId: String) = viewModelScope.launch { repo.removePhoto(doseUid, photoId) }

    // ───────── 计划 ─────────

    fun newPlan(): PlanEntity = PlanEntity(
        uid = newId(), name = if (plans.value.isEmpty()) "早上的药" else "", startDate = LocalDate.now().toString(),
        sortOrder = plans.value.size, updatedAt = System.currentTimeMillis()
    )

    fun savePlan(plan: PlanEntity) = viewModelScope.launch { repo.savePlan(plan) }

    fun deletePlan(uid: String) = viewModelScope.launch { repo.deletePlan(uid) }

    fun finishOnboarding(plan: PlanEntity) {
        viewModelScope.launch {
            repo.savePlan(plan)
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            _onboarded.value = true
        }
    }

    /** 登录后云端已有计划时直接跳过引导 */
    fun markOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboarded.value = true
    }

    fun setCourseStart(date: LocalDate?) {
        prefs.edit().apply { if (date == null) remove(KEY_COURSE) else putString(KEY_COURSE, date.toString()) }.apply()
        _courseStart.value = date
    }

    companion object {
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_COURSE = "course_start"
    }
}
