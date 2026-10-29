package com.todayeat.app.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.data.Repeat
import com.todayeat.app.data.Repository
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.Schedule
import com.todayeat.app.sync.SyncManager
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.components.CheckDot
import com.todayeat.app.ui.components.Chip
import com.todayeat.app.ui.components.Field
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.PhotoThumb
import com.todayeat.app.ui.components.PrimaryButton
import com.todayeat.app.ui.components.SoftButton
import com.todayeat.app.ui.components.Toggle
import com.todayeat.app.ui.components.press
import com.todayeat.app.ui.theme.T
import com.todayeat.app.update.AppUpdate
import com.todayeat.app.update.UpdatePhase
import com.todayeat.app.update.UpdateUi
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.Locale

// ───────────────────────── 全屏看照片 ─────────────────────────

class Viewer {
    var photo by mutableStateOf<String?>(null)
}

val LocalViewer = staticCompositionLocalOf { Viewer() }

// ───────────────────────── 拍照 / 选图 ─────────────────────────

class PhotoPicker(val camera: () -> Unit, val gallery: () -> Unit)

/** 拍完或选完回调 uri；相机先写到 cache/camera 下的临时文件 */
@Composable
fun rememberPhotoPicker(onPicked: (Uri) -> Unit): PhotoPicker {
    val app = LocalContext.current.applicationContext as com.todayeat.app.TodayEatApp
    var pending by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pending
        if (ok && p != null) onPicked(p.first) else p?.second?.delete()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return remember {
        PhotoPicker(
            camera = {
                val target = app.photos.newCameraUri()
                pending = target
                cam.launch(target.first)
            },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    }
}

// ───────────────────────── 选时间 ─────────────────────────

@Composable
fun InlineTimePicker(hour: Int, minute: Int, onPick: (Int, Int) -> Unit) {
    val c = T.c
    val state = rememberTimePickerState(hour, minute, is24Hour = true)
    Column(Modifier.fillMaxWidth()) {
    TimePicker(
        state = state,
        modifier = Modifier.align(Alignment.CenterHorizontally),
        colors = TimePickerDefaults.colors(
            clockDialColor = c.paper, selectorColor = c.sage, containerColor = c.card,
            clockDialSelectedContentColor = c.onSage, clockDialUnselectedContentColor = c.ink,
            timeSelectorSelectedContainerColor = c.sageSoft, timeSelectorUnselectedContainerColor = c.paper,
            timeSelectorSelectedContentColor = c.ink, timeSelectorUnselectedContentColor = c.ink2
        )
    )
    PrimaryButton("就这个时间", Modifier.fillMaxWidth()) { onPick(state.hour, state.minute) }
    }
}

// ───────────────────────── 某天某种药的详情 ─────────────────────────

/**
 * 一种药在某天的记录：没记就能补记；记了可以改时间、加照片、写一句、撤销。
 * [dose] 需要外面实时传入（观察数据库），这里不自己缓存。
 */
@Composable
fun ColumnScope.DoseDetail(
    vm: AppViewModel,
    plan: PlanEntity,
    date: LocalDate,
    dose: DoseEntity?,
    close: () -> Unit
) {
    val c = T.c
    val toasts = LocalToasts.current
    val viewer = LocalViewer.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val picker = rememberPhotoPicker { uri ->
        scope.launch {
            if (!vm.attachPhoto(plan.uid, date, uri)) toasts.show("这张照片读不出来，换一张试试")
        }
    }
    var note by remember(dose?.uid) { mutableStateOf(dose?.note ?: "") }
    var picking by remember { mutableStateOf(false) }

    Text(plan.name, style = T.title, color = c.ink)
    Text(
        "${dateLabel(date)} · ${Schedule.describe(plan)} ${Copy.hm(plan.hour, plan.minute)}" + if (plan.dose.isNotBlank()) " · ${plan.dose}" else "",
        style = T.small, color = c.ink2, modifier = Modifier.padding(top = 2.dp)
    )
    Spacer(Modifier.height(20.dp))
    if (dose == null) {
        Text(if (date.isAfter(today)) "这天还没到。" else "这天没有记录。", style = T.body, color = c.ink2)
        Spacer(Modifier.height(16.dp))
        if (!date.isAfter(today)) {
            PrimaryButton(if (date == today) "我吃了" else "补记：这天吃过了", Modifier.fillMaxWidth()) {
                if (date == today) vm.take(plan.uid, date) else vm.backfill(plan, date)
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CheckDot(true, 28.dp)
        Spacer(Modifier.width(12.dp))
        Text("${Copy.hm(dose.takenAt)} 吃的", style = T.title.merge(T.digits), color = c.ink, modifier = Modifier.weight(1f))
        Text(if (picking) "收起" else "改时间", style = T.small, color = c.sage, modifier = Modifier.press { picking = !picking }.padding(8.dp))
    }
    if (picking) {
        val t = java.time.Instant.ofEpochMilli(dose.takenAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        Spacer(Modifier.height(12.dp))
        InlineTimePicker(t.hour, t.minute) { h, m ->
            vm.setTime(dose, h, m)
            picking = false
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("照片", style = T.tiny, color = c.ink3)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        dose.photoIds.forEach { id -> PhotoThumb(id, 76.dp) { viewer.photo = id } }
        if (dose.photoIds.size < Repository.MAX_PHOTOS) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(c.paper).press { picker.camera() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(GlyphKind.Camera, c.ink2, 24.dp)
                    Text("拍一张", style = T.tiny, color = c.ink2)
                }
            }
        }
    }
    if (dose.photoIds.size < Repository.MAX_PHOTOS) {
        Text("从相册选", style = T.small, color = c.sage, modifier = Modifier.padding(top = 4.dp).press { picker.gallery() }.padding(vertical = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    Field(note, { note = it.take(200) }, "写一句（可不填），比如：饭后吃的")
    if (note != dose.note) {
        Text("保存这句话", style = T.small, color = c.sage, modifier = Modifier.padding(top = 4.dp).press { vm.setNote(dose.uid, note) }.padding(vertical = 8.dp))
    }
    Spacer(Modifier.height(24.dp))
    SoftButton("记错了，撤销这条", Modifier.fillMaxWidth(), tint = c.clay) {
        vm.undo(dose.uid)
        toasts.show("撤销了")
        close()
    }
}

fun dateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日 周${Schedule.WEEK_SHORT[date.dayOfWeek.value - 1]}"
}

// ───────────────────────── 编辑药和提醒 ─────────────────────────

@Composable
fun ColumnScope.PlanEditor(vm: AppViewModel, initial: PlanEntity, isNew: Boolean, close: () -> Unit) {
    val c = T.c
    var p by remember { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }

    Text(if (isNew) "添加一种药" else "改一改", style = T.title, color = c.ink)
    Spacer(Modifier.height(16.dp))
    PlanFields(p, onChange = { p = it })
    Spacer(Modifier.height(24.dp))
    PrimaryButton("保存", Modifier.fillMaxWidth(), enabled = p.name.isNotBlank()) {
        vm.savePlan(p.copy(name = p.name.trim(), dose = p.dose.trim()))
        close()
    }
    if (!isNew) {
        Spacer(Modifier.height(10.dp))
        SoftButton(if (confirmDelete) "确定删除？以前的记录会保留" else "删除这种药", Modifier.fillMaxWidth(), tint = c.clay) {
            if (confirmDelete) {
                vm.deletePlan(p.uid)
                close()
            } else confirmDelete = true
        }
    }
}

@Composable
fun PlanFields(p: PlanEntity, onChange: (PlanEntity) -> Unit) {
    val c = T.c
    var picking by remember { mutableStateOf(false) }
    Field(p.name, { onChange(p.copy(name = it.take(20))) }, "叫什么，比如：早上的药")
    Spacer(Modifier.height(10.dp))
    Field(p.dose, { onChange(p.copy(dose = it.take(20))) }, "吃多少（可不填），比如：1 片")
    Spacer(Modifier.height(18.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paper).press { picking = !picking }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("提醒时间", style = T.body, color = c.ink2, modifier = Modifier.weight(1f))
        Text(Copy.hm(p.hour, p.minute), style = T.title.merge(T.digits), color = c.ink)
        Spacer(Modifier.width(6.dp))
        Glyph(GlyphKind.Chevron, c.ink3, 16.dp)
    }
    if (picking) {
        Spacer(Modifier.height(12.dp))
        InlineTimePicker(p.hour, p.minute) { h, m ->
            onChange(p.copy(hour = h, minute = m))
            picking = false
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("哪些天吃", style = T.tiny, color = c.ink3)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Repeat.DAILY to "每天", Repeat.WEEKDAYS to "工作日", Repeat.WEEKENDS to "周末", Repeat.CUSTOM to "自己选", Repeat.INTERVAL to "隔几天")
            .forEach { (r, label) -> Chip(label, p.repeatKind == r) { onChange(p.copy(repeat = r.name)) } }
    }
    when (p.repeatKind) {
        Repeat.WEEKDAYS -> Hint("周一到周五。法定节假日和调休不会自动跳过。")
        Repeat.CUSTOM -> {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    val on = d in p.weekdaySet
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.sage else c.paper)
                            .press { onChange(p.copy(weekdays = (if (on) p.weekdaySet - d else p.weekdaySet + d).sorted().joinToString(","))) },
                        contentAlignment = Alignment.Center
                    ) { Text(Schedule.WEEK_SHORT[d - 1], style = T.small, color = if (on) c.onSage else c.ink2) }
                }
            }
        }
        Repeat.INTERVAL -> {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每", style = T.body, color = c.ink2)
                Stepper(p.intervalDays, 2..30) { onChange(p.copy(intervalDays = it)) }
                Text("天一次，从 ${p.startDate.substring(5).replace('-', '月')}日 算起", style = T.body, color = c.ink2)
            }
        }
        else -> Unit
    }
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("没点「已吃」就再提醒", style = T.body, color = c.ink)
            Text("最多再提醒 3 次", style = T.tiny, color = c.ink3)
        }
        Toggle(p.renag) { onChange(p.copy(renag = it)) }
    }
    if (p.renag) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { m -> Chip("$m 分钟", p.renagMinutes == m) { onChange(p.copy(renagMinutes = m)) } }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("提醒开着", style = T.body, color = c.ink)
            Text("停药了可以先关掉，记录都还在", style = T.tiny, color = c.ink3)
        }
        Toggle(p.active) { onChange(p.copy(active = it)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = T.tiny, color = T.c.ink3, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val c = T.c
    Row(Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp)).background(c.paper), verticalAlignment = Alignment.CenterVertically) {
        Text("−", style = T.title, color = c.ink2, modifier = Modifier.press { onChange((value - 1).coerceIn(range)) }.padding(horizontal = 14.dp, vertical = 4.dp))
        Text("$value", style = T.title.merge(T.digits), color = c.ink)
        Text("+", style = T.title, color = c.ink2, modifier = Modifier.press { onChange((value + 1).coerceIn(range)) }.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

// ───────────────────────── 登录 / 注册 ─────────────────────────

@Composable
fun ColumnScope.LoginSheet(sync: SyncManager, startWithRegister: Boolean, close: () -> Unit, onDone: () -> Unit = {}) {
    val c = T.c
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(startWithRegister) }
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("登录", !register) { register = false; error = null }
        Chip("注册", register) { register = true; error = null }
    }
    Spacer(Modifier.height(16.dp))
    Field(name, { name = it.take(32) }, "用户名")
    Spacer(Modifier.height(10.dp))
    Field(pw, { pw = it }, "密码，至少 6 位", password = true)
    if (register) {
        Spacer(Modifier.height(10.dp))
        Field(pw2, { pw2 = it }, "再输一次密码", password = true)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "记录和照片在手机上加密后才上传，服务器看不到内容，也不知道你的密码。所以密码忘了，云端的记录就再也打不开了，请记牢。",
        style = T.tiny, color = c.ink3
    )
    error?.let { Text(it, style = T.small, color = c.clay, modifier = Modifier.padding(top = 10.dp)) }
    Spacer(Modifier.height(18.dp))
    PrimaryButton(
        when {
            busy -> "正在加密并同步…"
            register -> "注册并开始同步"
            else -> "登录"
        },
        Modifier.fillMaxWidth(),
        enabled = !busy && name.isNotBlank() && pw.isNotEmpty()
    ) {
        if (register && pw != pw2) {
            error = "两次密码不一样"
            return@PrimaryButton
        }
        busy = true
        error = null
        scope.launch {
            try {
                sync.signIn(name, pw, register)
                toasts.show(if (register) "注册好了，以后换手机也不丢" else "登录好了，记录已同步")
                onDone()
                close()
            } catch (e: Exception) {
                error = e.message ?: "没成功，再试一次"
            } finally {
                busy = false
            }
        }
    }
}

// ───────────────────────── 应用内更新 ─────────────────────────

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

@Composable
fun ColumnScope.UpdatePanel(ui: UpdateUi, updates: AppUpdate) {
    val c = T.c
    val context = LocalContext.current
    Text("有新版本 ${ui.offer.versionName}", style = T.title, color = c.ink)
    Text("在 App 里下载，下完直接安装，记录不会丢", style = T.small, color = c.ink2, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(14.dp))
    Text(
        ui.offer.notes.ifBlank { "修了一些小问题。" }, style = T.body, color = c.ink,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paper).padding(14.dp)
    )
    if (ui.phase == UpdatePhase.Downloading) {
        Spacer(Modifier.height(14.dp))
        val fraction = if (ui.total > 0) (ui.received.toFloat() / ui.total).coerceIn(0f, 1f) else 0f
        val animated by animateFloatAsState(fraction, label = "update")
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.paper)) {
            Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(RoundedCornerShape(50)).background(c.sage))
        }
        Text(
            if (ui.total > 0) "${(fraction * 100).toInt()}% · ${mb(ui.received)} / ${mb(ui.total)}" else "正在连接…",
            style = T.tiny.merge(T.digits), color = c.ink2, modifier = Modifier.padding(top = 6.dp)
        )
    }
    if (ui.message.isNotBlank()) {
        Text(ui.message, style = T.small, color = if (ui.phase == UpdatePhase.Failed) c.clay else c.ink2, modifier = Modifier.padding(top = 10.dp))
    }
    Spacer(Modifier.height(18.dp))
    val label = when (ui.phase) {
        UpdatePhase.Available -> "下载并安装"
        UpdatePhase.Downloading -> "取消下载"
        UpdatePhase.Ready -> "安装"
        UpdatePhase.NeedsPermission -> "去允许安装"
        UpdatePhase.Failed -> "重新下载"
    }
    if (ui.phase == UpdatePhase.Downloading) SoftButton(label, Modifier.fillMaxWidth()) { updates.onPrimary(context) }
    else PrimaryButton(label, Modifier.fillMaxWidth()) { updates.onPrimary(context) }
}
