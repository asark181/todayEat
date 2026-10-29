package com.todayeat.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.DayState
import com.todayeat.app.domain.Schedule
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.HomeUi
import com.todayeat.app.ui.common.DoseDetail
import com.todayeat.app.ui.common.LocalViewer
import com.todayeat.app.ui.common.PlanEditor
import com.todayeat.app.ui.common.rememberPhotoPicker
import com.todayeat.app.ui.components.Card
import com.todayeat.app.ui.components.CheckDot
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.PhotoThumb
import com.todayeat.app.ui.components.PrimaryButton
import com.todayeat.app.ui.components.SectionLabel
import com.todayeat.app.ui.components.press
import com.todayeat.app.ui.theme.T
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Composable
fun HomeScreen(vm: AppViewModel, ui: HomeUi, plans: List<PlanEntity>, bottomPad: Dp) {
    val c = T.c
    val sheets = LocalSheets.current
    val toasts = LocalToasts.current
    val viewer = LocalViewer.current
    val scope = rememberCoroutineScope()
    val info = ui.today
    val date = info.date
    var photoFor by remember { mutableStateOf<String?>(null) }
    val picker = rememberPhotoPicker { uri ->
        val planUid = photoFor ?: return@rememberPhotoPicker
        scope.launch {
            if (vm.attachPhoto(planUid, date, uri)) toasts.show("照片留好了") else toasts.show("这张照片读不出来，换一张试试")
        }
    }

    fun openDetail(plan: PlanEntity) {
        sheets.show { close ->
            // 面板里实时读最新记录
            val now by vm.home.collectAsState()
            DoseDetail(vm, plan, date, now.today.doseFor(plan.uid), close)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = bottomPad + 24.dp)
    ) {
        Text(
            "${Copy.greeting(ui.now)} · ${date.monthValue}月${date.dayOfMonth}日 周${Schedule.WEEK_SHORT[date.dayOfWeek.value - 1]}",
            style = T.small, color = c.ink2
        )
        Spacer(Modifier.height(36.dp))

        // ───── 今天吃没吃：整页最重要的一句 ─────
        StateMark(info.state, 64.dp)
        Spacer(Modifier.height(20.dp))
        AnimatedContent(ui.headline, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "headline") { h ->
            Column {
                Text(h.title, style = T.display, color = c.ink)
                if (h.sub.isNotBlank()) Text(h.sub, style = T.body.merge(T.digits), color = c.ink2, modifier = Modifier.padding(top = 6.dp))
            }
        }

        val left = info.due.filter { info.doseFor(it.uid) == null }
        if (left.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            PrimaryButton(if (left.size == 1 && info.due.size == 1) "我吃了" else "这 ${left.size} 样都吃了", Modifier.fillMaxWidth()) {
                vm.takeAll(left, date)
            }
        } else if (info.state == DayState.ALL && info.due.isNotEmpty()) {
            val firstWithoutPhoto = info.due.firstOrNull { info.doseFor(it.uid)?.photoIds.isNullOrEmpty() }
            if (firstWithoutPhoto != null && info.doses.all { it.photoIds.isEmpty() }) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.clip(CircleShape).background(c.card).press {
                        photoFor = firstWithoutPhoto.uid
                        picker.camera()
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph(GlyphKind.Camera, c.sage, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("拍一张留底，醒来更放心", style = T.small, color = c.ink2)
                }
            }
        }

        Spacer(Modifier.height(36.dp))

        // ───── 今天的每一样 ─────
        if (info.due.isNotEmpty()) {
            SectionLabel("今天要吃的")
            Card(padding = PaddingValues(vertical = 6.dp)) {
                info.due.forEachIndexed { i, plan ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 20.dp).height(1.dp).background(c.line))
                    PlanRow(
                        plan = plan,
                        dose = info.doseFor(plan.uid),
                        onCheck = { if (info.doseFor(plan.uid) == null) vm.take(plan.uid, date) else openDetail(plan) },
                        onOpen = { openDetail(plan) },
                        onCamera = { photoFor = plan.uid; picker.camera() },
                        onPhoto = { viewer.photo = it }
                    )
                }
            }
        } else if (plans.isNotEmpty()) {
            val next = plans.mapNotNull { p -> Schedule.nextFire(p, ui.now)?.let { it to p } }.minByOrNull { it.first }
            Card {
                Text("今天这几样都不用吃。", style = T.body, color = c.ink)
                next?.let { (at, p) ->
                    Text(
                        "下次：${nextLabel(at, ui.now)} ${Copy.hm(p.hour, p.minute)} · ${p.name}",
                        style = T.small.merge(T.digits), color = c.ink2, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            Card {
                Text("还没有要提醒的药。", style = T.body, color = c.ink)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("添加一种药", Modifier.fillMaxWidth()) {
                    sheets.show { close -> PlanEditor(vm, vm.newPlan(), true, close) }
                }
            }
        }

        // ───── 底部一点点鼓励，不喧宾夺主 ─────
        val lines = listOfNotNull(Copy.streakLine(ui.streak), ui.courseDay?.let { "开始吃药的第 $it 天" })
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(lines.joinToString(" · "), style = T.small.merge(T.digits), color = c.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun nextLabel(at: LocalDateTime, now: LocalDateTime): String {
    val d = at.toLocalDate()
    return when (d) {
        now.toLocalDate() -> "今天"
        now.toLocalDate().plusDays(1) -> "明天"
        now.toLocalDate().plusDays(2) -> "后天"
        else -> "${d.monthValue}月${d.dayOfMonth}日"
    }
}

@Composable
private fun PlanRow(
    plan: PlanEntity,
    dose: com.todayeat.app.data.DoseEntity?,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
    onCamera: () -> Unit,
    onPhoto: (String) -> Unit
) {
    val c = T.c
    Column(Modifier.fillMaxWidth().press(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckDot(dose != null, 32.dp, modifier = Modifier.press(haptic = true, onClick = onCheck))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = T.bodyStrong, color = c.ink)
                Text(
                    "${Copy.hm(plan.hour, plan.minute)} 提醒" + if (plan.dose.isNotBlank()) " · ${plan.dose}" else "",
                    style = T.tiny.merge(T.digits), color = c.ink3
                )
            }
            if (dose != null) {
                Text(Copy.hm(dose.takenAt), style = T.bodyStrong.merge(T.digits), color = c.sage)
                if (dose.photoIds.isEmpty()) {
                    Box(Modifier.padding(start = 10.dp).size(36.dp).clip(CircleShape).background(c.paper).press(onClick = onCamera), contentAlignment = Alignment.Center) {
                        Glyph(GlyphKind.Camera, c.ink2, 20.dp)
                    }
                }
            }
        }
        if (dose != null && dose.photoIds.isNotEmpty()) {
            Row(Modifier.padding(start = 48.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dose.photoIds.forEach { id -> PhotoThumb(id, 56.dp) { onPhoto(id) } }
            }
        }
    }
}

/** 大号状态标记：吃过是实心带勾，该吃了是琥珀色圈，没到时间是细圈，不用吃是虚线圈 */
@Composable
fun StateMark(state: DayState, size: Dp) {
    val c = T.c
    val done = state == DayState.ALL
    val k by animateFloatAsState(if (done) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 300f), label = "mark")
    val ring = when (state) {
        DayState.DUE, DayState.PARTIAL -> c.amber
        DayState.MISSED -> c.clay
        else -> c.ink3
    }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val r = w / 2f
        if (k > 0.01f) {
            drawCircle(c.sage, r * (0.6f + 0.4f * k))
            val p = Path().apply {
                moveTo(w * 0.29f, w * 0.52f); lineTo(w * 0.44f, w * 0.66f); lineTo(w * 0.72f, w * 0.36f)
            }
            drawPath(p, c.onSage.copy(alpha = k), style = Stroke(w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            val dashed = state == DayState.NONE
            drawCircle(
                ring, r - w * 0.04f,
                style = Stroke(w * 0.06f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 0.08f, w * 0.08f)) else null)
            )
            if (state == DayState.PARTIAL) {
                drawArc(ring, -90f, 180f, true, topLeft = Offset(w * 0.04f, w * 0.04f), size = androidx.compose.ui.geometry.Size(w * 0.92f, w * 0.92f))
            }
            if (state == DayState.DUE) drawCircle(ring.copy(alpha = 0.18f), r * 0.62f)
        }
    }
}
