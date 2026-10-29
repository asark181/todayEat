package com.todayeat.app.ui.mine

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.DayInfo
import com.todayeat.app.domain.DayState
import com.todayeat.app.domain.Schedule
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.common.PlanEditor
import com.todayeat.app.ui.common.RecordEditor
import com.todayeat.app.ui.common.RecordList
import com.todayeat.app.ui.components.Card
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.Segmented
import com.todayeat.app.ui.components.press
import com.todayeat.app.ui.theme.T
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private const val MONTHS_BACK = 120

/** 「我的」：打卡和日历是同一份记录的两种看法 */
@Composable
fun MineScreen(vm: AppViewModel, bottomPad: Dp) {
    val c = T.c
    val sheets = LocalSheets.current
    val now by vm.now.collectAsState()
    val plans by vm.plans.collectAsState()
    val names by vm.allPlans.collectAsState()
    val today = now.toLocalDate()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var selectedRaw by rememberSaveable { androidx.compose.runtime.mutableStateOf(today.toString()) }
    val selected = LocalDate.parse(selectedRaw)
    val select: (LocalDate) -> Unit = { selectedRaw = it.toString() }

    // 过了零点还停在「昨天」的话，跟着换到今天
    var lastToday by rememberSaveable { androidx.compose.runtime.mutableStateOf(today.toString()) }
    LaunchedEffect(today) {
        if (lastToday != today.toString()) {
            if (selectedRaw == lastToday) selectedRaw = today.toString()
            lastToday = today.toString()
        }
    }

    val ready by vm.ready.collectAsState()
    val dayDosesOrNull by remember(selected) { vm.doses(selected, selected) }.collectAsState(null)
    val dayDoses = dayDosesOrNull.orEmpty()
    val info = remember(selected, dayDoses, plans, now) { vm.dayInfo(selected, dayDoses) }
    // 数据还没读出来时不画按钮，免得先闪一下「打卡」
    val loaded = ready && dayDosesOrNull != null

    fun addRecord() = sheets.show { close -> RecordEditor(vm, vm.newDose(selected, info), true, close) }
    fun openRecord(d: DoseEntity) = sheets.show { close -> RecordEditor(vm, d, false, close) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = bottomPad + 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Copy.day(today), style = T.title.merge(T.digits), color = c.ink, modifier = Modifier.weight(1f))
            Segmented(listOf("打卡", "日历"), mode) { mode = it }
        }
        Spacer(Modifier.height(16.dp))

        if (mode == 0) {
            WeekStrip(vm, today, selected, select)
            Spacer(Modifier.height(36.dp))
            CheckInButton(info, today, loaded, hasPlans = plans.isNotEmpty(),
                onCheckIn = { vm.checkIn(info) },
                onAddPlan = { sheets.show { close -> PlanEditor(vm, vm.newPlan(), true, close) } })
            Spacer(Modifier.height(16.dp))
            if (loaded) StatusLine(info, today) else Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(36.dp))
        } else {
            MonthCalendar(vm, today, selected, select)
            Spacer(Modifier.height(24.dp))
        }

        RecordList(
            title = if (mode == 0 && selected == today) "今天的记录" else Copy.dayShort(selected, today),
            doses = dayDoses,
            plans = names,
            canAdd = !selected.isAfter(today),
            onAdd = { addRecord() },
            onOpen = { openRecord(it) }
        )
    }
}

// ───────────────────────── 打卡 ─────────────────────────

/** 本周七天，点哪天就看哪天 */
@Composable
private fun WeekStrip(vm: AppViewModel, today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val c = T.c
    val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val doses by remember(start) { vm.doses(start, start.plusDays(6)) }.collectAsState(emptyList())
    val plans by vm.plans.collectAsState()
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until 7) {
            val date = start.plusDays(i.toLong())
            val state = remember(date, doses, plans) { vm.dayInfo(date, doses).state }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(date) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (date == today) "今" else Schedule.WEEK_SHORT[i], style = T.tiny, color = if (date == today) c.ink else c.ink3)
                Spacer(Modifier.height(6.dp))
                DayDot(date.dayOfMonth, state, date == selected, 38.dp)
            }
        }
    }
}

@Composable
private fun DayDot(day: Int, state: DayState, selected: Boolean, size: Dp) {
    val c = T.c
    val (bg, fg) = when (state) {
        DayState.ALL -> c.sage to c.onSage
        DayState.PARTIAL -> c.amberSoft to c.ink
        DayState.MISSED -> c.claySoft to c.clay
        DayState.FUTURE, DayState.NONE -> Color.Transparent to c.ink3
        else -> Color.Transparent to c.ink
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(bg)
            .then(if (selected) Modifier.border(2.dp, c.ink, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text("$day", style = T.body.merge(T.digits), color = fg)
    }
}

/** 中间的大圆按钮 */
@Composable
private fun CheckInButton(info: DayInfo, today: LocalDate, loaded: Boolean, hasPlans: Boolean, onCheckIn: () -> Unit, onAddPlan: () -> Unit) {
    val c = T.c
    val haptic = LocalHapticFeedback.current
    val left = info.due.count { info.doseFor(it.uid) == null }
    val kind = when {
        !hasPlans -> BtnKind.NoPlan
        info.date.isAfter(today) -> BtnKind.Future
        left > 0 -> BtnKind.Todo
        info.doses.isNotEmpty() -> BtnKind.Done
        else -> BtnKind.Rest
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "btn")
    val clickable = kind == BtnKind.Todo || kind == BtnKind.NoPlan

    Box(Modifier.fillMaxWidth().height(232.dp), contentAlignment = Alignment.Center) {
        if (!loaded) return@Box
        // 外圈光晕
        Box(
            Modifier.size(232.dp).clip(CircleShape)
                .background(if (kind == BtnKind.Todo) c.sageSoft else Color.Transparent)
        )
        AnimatedContent(kind, transitionSpec = {
            (fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 400f), initialScale = 0.85f)) togetherWith fadeOut(tween(120))
        }, label = "kind") { k ->
            Box(
                Modifier
                    .size(196.dp)
                    .scale(if (clickable) scale else 1f)
                    .clip(CircleShape)
                    .background(
                        when (k) {
                            BtnKind.Todo -> c.sage
                            BtnKind.Done -> c.sageSoft
                            else -> c.card
                        }
                    )
                    .then(if (k == BtnKind.Future || k == BtnKind.Rest) Modifier.border(2.dp, c.line, CircleShape) else Modifier)
                    .clickable(source, null, enabled = clickable) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (k == BtnKind.NoPlan) onAddPlan() else onCheckIn()
                    },
                contentAlignment = Alignment.Center
            ) {
                when (k) {
                    BtnKind.Todo -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("打卡", style = BIG, color = c.onSage)
                        if (info.due.size > 1) Text("$left 样未打卡", style = T.small, color = c.onSage.copy(alpha = 0.85f))
                    }
                    BtnKind.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Glyph(GlyphKind.Check, c.sage, 72.dp)
                        Text("已打卡", style = T.title, color = c.sage)
                    }
                    BtnKind.Future -> Text("未到", style = T.title, color = c.ink3)
                    BtnKind.Rest -> Text("无需服药", style = T.title, color = c.ink3)
                    BtnKind.NoPlan -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Glyph(GlyphKind.Plus, c.sage, 40.dp)
                        Spacer(Modifier.height(6.dp))
                        Text("添加药品", style = T.title, color = c.sage)
                    }
                }
            }
        }
    }
}

private enum class BtnKind { Todo, Done, Future, Rest, NoPlan }

private val BIG = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)

/** 按钮下面一行：要吃什么、几点；吃过了写几点打的卡 */
@Composable
private fun StatusLine(info: DayInfo, today: LocalDate) {
    val c = T.c
    val left = info.due.filter { info.doseFor(it.uid) == null }
    val text = when {
        left.isNotEmpty() && info.date == today ->
            left.joinToString("　") { "${Copy.hm(it.hour, it.minute)} ${it.name}" }
        left.isNotEmpty() && info.date.isBefore(today) -> "补打卡按提醒时间记录"
        info.doses.isNotEmpty() -> "${Copy.hm(info.doses.maxOf { it.takenAt })} 打卡"
        else -> ""
    }
    Text(text, style = T.body.merge(T.digits), color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

// ───────────────────────── 日历 ─────────────────────────

@Composable
private fun MonthCalendar(vm: AppViewModel, today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val c = T.c
    val thisMonth = YearMonth.from(today)
    val initial = (MONTHS_BACK - java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(selected), thisMonth).toInt()).coerceIn(0, MONTHS_BACK)
    val pager = rememberPagerState(initialPage = initial) { MONTHS_BACK + 1 }
    val scope = rememberCoroutineScope()
    val shown = thisMonth.minusMonths((MONTHS_BACK - pager.currentPage).toLong())
    val plans by vm.plans.collectAsState()

    Card(padding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.year}年${shown.monthValue}月", style = T.bodyStrong.merge(T.digits), color = c.ink, modifier = Modifier.weight(1f))
            NavButton(GlyphKind.Back, pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
            NavButton(GlyphKind.Chevron, pager.currentPage < MONTHS_BACK) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
        }
        Row(Modifier.fillMaxWidth()) {
            Schedule.WEEK_SHORT.forEach {
                Text(it, style = T.tiny, color = c.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalPager(pager, verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
            val month = thisMonth.minusMonths((MONTHS_BACK - page).toLong())
            val doses by remember(month) { vm.doses(month.atDay(1), month.atEndOfMonth()) }.collectAsState(emptyList())
            MonthGrid(month, doses, plans, vm, today, selected, onSelect)
        }
    }
    Spacer(Modifier.height(10.dp))
    Legend()
}

@Composable
private fun NavButton(kind: GlyphKind, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).press(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(kind, if (enabled) T.c.ink2 else T.c.line, 18.dp)
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    doses: List<DoseEntity>,
    plans: List<PlanEntity>,
    vm: AppViewModel,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val lead = month.atDay(1).dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val rows = (lead + days + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 7) {
                    val n = r * 7 + col - lead + 1
                    if (n < 1 || n > days) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(n)
                        val info = if (plans.isEmpty() && doses.isEmpty()) null else vm.dayInfo(date, doses)
                        DayCell(n, info?.state ?: DayState.NONE, date == today, date == selected,
                            info?.doses?.any { it.photoIds.isNotEmpty() } == true, Modifier.weight(1f)) { onSelect(date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, state: DayState, isToday: Boolean, isSelected: Boolean, hasPhoto: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = T.c
    val (bg, fg) = when (state) {
        DayState.ALL -> c.sage to c.onSage
        DayState.PARTIAL -> c.amberSoft to c.ink
        DayState.MISSED -> c.claySoft to c.clay
        DayState.DUE, DayState.PENDING -> Color.Transparent to c.ink
        DayState.FUTURE, DayState.NONE -> Color.Transparent to c.ink3
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(bg)
            .then(
                when {
                    isSelected -> Modifier.border(2.dp, c.ink, shape)
                    isToday -> Modifier.border(1.5.dp, c.ink3, shape)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("$day", style = (if (isToday) T.bodyStrong else T.body).merge(T.digits), color = fg)
        if (hasPhoto) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp).clip(CircleShape).background(fg))
    }
}

@Composable
private fun Legend() {
    val c = T.c
    Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(c.sage to "已吃", c.amberSoft to "部分", c.claySoft to "漏吃").forEach { (col, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(col))
                Spacer(Modifier.width(6.dp))
                Text(label, style = T.tiny, color = c.ink2)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(c.ink2))
            Spacer(Modifier.width(6.dp))
            Text("有照片", style = T.tiny, color = c.ink2)
        }
    }
}
