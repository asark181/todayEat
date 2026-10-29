package com.todayeat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.DayInfo
import com.todayeat.app.domain.DayState
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.common.DoseDetail
import com.todayeat.app.ui.common.LocalViewer
import com.todayeat.app.ui.common.dateLabel
import com.todayeat.app.ui.components.Card
import com.todayeat.app.ui.components.CheckDot
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.PhotoThumb
import com.todayeat.app.ui.components.press
import com.todayeat.app.ui.theme.T
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private const val MONTHS_BACK = 120

@Composable
fun CalendarScreen(vm: AppViewModel, bottomPad: Dp) {
    val c = T.c
    val now by vm.now.collectAsState()
    val plans by vm.plans.collectAsState()
    val thisMonth = YearMonth.from(now)
    val pager = rememberPagerState(initialPage = MONTHS_BACK) { MONTHS_BACK + 1 }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(now.toLocalDate()) }
    val shown = thisMonth.minusMonths((MONTHS_BACK - pager.currentPage).toLong())
    val shownDoses by remember(shown) { vm.monthDoses(shown) }.collectAsState(emptyList())

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = bottomPad + 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.year}年${shown.monthValue}月", style = T.title.merge(T.digits), color = c.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).press { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }, contentAlignment = Alignment.Center) {
                Glyph(GlyphKind.Back, c.ink2, 18.dp)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).press(enabled = pager.currentPage < MONTHS_BACK) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                contentAlignment = Alignment.Center
            ) {
                Glyph(GlyphKind.Chevron, if (pager.currentPage < MONTHS_BACK) c.ink2 else c.line, 18.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        MonthSummary(shown, plans, shownDoses, vm)
        Spacer(Modifier.height(16.dp))

        Card(padding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, style = T.tiny, color = c.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalPager(pager, verticalAlignment = Alignment.Top, beyondViewportPageCount = 1) { page ->
                val month = thisMonth.minusMonths((MONTHS_BACK - page).toLong())
                val doses by remember(month) { vm.monthDoses(month) }.collectAsState(emptyList())
                MonthGrid(month, doses, plans, vm, selected) { selected = it }
            }
        }
        Spacer(Modifier.height(12.dp))
        Legend()
        Spacer(Modifier.height(20.dp))

        if (YearMonth.from(selected) == shown) {
            DayCard(vm, vm.dayInfo(selected, shownDoses))
        }
    }
}

@Composable
private fun MonthSummary(month: YearMonth, plans: List<PlanEntity>, doses: List<DoseEntity>, vm: AppViewModel) {
    val today = LocalDate.now()
    var dueDays = 0
    var fullDays = 0
    var d = month.atDay(1)
    while (!d.isAfter(month.atEndOfMonth()) && !d.isAfter(today)) {
        val info = vm.dayInfo(d, doses)
        if (info.due.isNotEmpty()) {
            dueDays++
            if (info.state == DayState.ALL) fullDays++
        }
        d = d.plusDays(1)
    }
    val text = when {
        dueDays == 0 -> "这个月还没有要吃药的日子"
        fullDays == dueDays -> "这个月该吃 $dueDays 天，一天没落下"
        else -> "这个月该吃 $dueDays 天，吃全了 $fullDays 天"
    }
    Text(text, style = T.small.merge(T.digits), color = T.c.ink2)
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    doses: List<DoseEntity>,
    plans: List<PlanEntity>,
    vm: AppViewModel,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val lead = month.atDay(1).dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val rows = (lead + days + 6) / 7
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        DayState.ALL -> c.sageSoft to c.sage
        DayState.PARTIAL -> c.amberSoft to c.amber
        DayState.MISSED -> c.claySoft to c.clay
        DayState.DUE -> c.amberSoft to c.ink
        DayState.PENDING, DayState.FUTURE -> Color.Transparent to c.ink
        DayState.NONE -> Color.Transparent to c.ink3
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
                    isToday -> Modifier.border(1.dp, c.ink2, shape)
                    state == DayState.FUTURE -> Modifier.border(1.dp, c.line, shape)
                    else -> Modifier
                }
            )
            .press(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("$day", style = T.bodyStrong.merge(T.digits), color = fg)
        if (hasPhoto) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp).clip(CircleShape).background(fg))
    }
}

@Composable
private fun Legend() {
    val c = T.c
    Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(c.sageSoft to "都吃了", c.amberSoft to "吃了一部分", c.claySoft to "漏了").forEach { (col, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(col))
                Spacer(Modifier.width(6.dp))
                Text(label, style = T.tiny, color = c.ink2)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(c.ink2))
            Spacer(Modifier.width(6.dp))
            Text("有照片", style = T.tiny, color = c.ink2)
        }
    }
}

@Composable
private fun DayCard(vm: AppViewModel, info: DayInfo) {
    val c = T.c
    val sheets = LocalSheets.current
    val viewer = LocalViewer.current
    val plans by vm.plans.collectAsState()
    // 这天应该吃的，加上这天有记录但现在已经不按这天提醒的
    val shownPlans = (info.due + info.doses.mapNotNull { d -> plans.firstOrNull { it.uid == d.planUid } }).distinctBy { it.uid }
    Text(dateLabel(info.date), style = T.tiny, color = c.ink3, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    Card(padding = PaddingValues(vertical = 6.dp)) {
        if (shownPlans.isEmpty()) {
            Text("这天不用吃药。", style = T.body, color = c.ink2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
        shownPlans.forEachIndexed { i, plan ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 20.dp).height(1.dp).background(c.line))
            val dose = info.doseFor(plan.uid)
            Column(
                Modifier.fillMaxWidth().press {
                    sheets.show { close ->
                        val live by remember(info.date) { vm.monthDoses(YearMonth.from(info.date)) }.collectAsState(info.doses)
                        DoseDetail(vm, plan, info.date, live.firstOrNull { it.planUid == plan.uid && it.date == info.date.toString() }, close)
                    }
                }.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckDot(dose != null, 28.dp, accent = c.sage)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(plan.name, style = T.bodyStrong, color = c.ink)
                        if (dose?.note?.isNotBlank() == true) Text(dose.note, style = T.tiny, color = c.ink2)
                    }
                    Text(
                        when {
                            dose != null -> Copy.hm(dose.takenAt)
                            info.date.isAfter(LocalDate.now()) -> "未到"
                            info.date == LocalDate.now() -> "还没吃"
                            else -> "没记"
                        },
                        style = T.small.merge(T.digits),
                        color = if (dose != null) c.sage else c.ink3
                    )
                }
                if (dose != null && dose.photoIds.isNotEmpty()) {
                    Row(Modifier.padding(start = 44.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dose.photoIds.forEach { id -> PhotoThumb(id, 56.dp) { viewer.photo = id } }
                    }
                }
            }
        }
    }
}
