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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.data.Repeat
import com.todayeat.app.data.Repository
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.Schedule
import com.todayeat.app.sync.SyncManager
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.components.Chip
import com.todayeat.app.ui.components.ConfirmDialog
import com.todayeat.app.ui.components.Field
import com.todayeat.app.ui.components.FieldLabel
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.PhotoThumb
import com.todayeat.app.ui.components.PrimaryButton
import com.todayeat.app.ui.components.SheetHeader
import com.todayeat.app.ui.components.SoftButton
import com.todayeat.app.ui.components.Toggle
import com.todayeat.app.ui.components.WheelTimePicker
import com.todayeat.app.ui.components.press
import com.todayeat.app.ui.theme.T
import com.todayeat.app.update.AppUpdate
import com.todayeat.app.update.UpdatePhase
import com.todayeat.app.update.UpdateUi
import kotlinx.coroutines.launch
import java.io.File
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

/** 照片一排：已有的缩略图（右上角可删）+ 添加格（点开选拍照或相册） */
@Composable
fun PhotoRow(ids: List<String>, onAdd: (Uri) -> Unit, onRemove: (String) -> Unit) {
    val c = T.c
    val viewer = LocalViewer.current
    val picker = rememberPhotoPicker(onAdd)
    var menu by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ids.forEach { id ->
            Box {
                PhotoThumb(id, 76.dp) { viewer.photo = id }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(RoundedCornerShape(12.dp))
                        .background(c.ink.copy(alpha = 0.6f)).press { onRemove(id) },
                    contentAlignment = Alignment.Center
                ) { Glyph(GlyphKind.Close, c.paper, 14.dp) }
            }
        }
        if (ids.size < Repository.MAX_PHOTOS) {
            Box {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(c.paper).press { menu = true },
                    contentAlignment = Alignment.Center
                ) { Glyph(GlyphKind.Plus, c.ink2, 26.dp) }
                DropdownMenu(menu, { menu = false }, containerColor = c.card) {
                    DropdownMenuItem(
                        text = { Text("拍照", style = T.body, color = c.ink) },
                        leadingIcon = { Glyph(GlyphKind.Camera, c.ink2, 20.dp) },
                        onClick = { menu = false; picker.camera() }
                    )
                    DropdownMenuItem(
                        text = { Text("从相册选", style = T.body, color = c.ink) },
                        leadingIcon = { Glyph(GlyphKind.Image, c.ink2, 20.dp) },
                        onClick = { menu = false; picker.gallery() }
                    )
                }
            }
        }
    }
}

/** 一行可点开的设置项：左标题，右当前值 */
@Composable
fun ValueRow(label: String, value: String, open: Boolean, onClick: () -> Unit) {
    val c = T.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paper).press(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = T.body, color = c.ink2, modifier = Modifier.weight(1f))
        Text(value, style = T.bodyStrong.merge(T.digits), color = if (open) c.sage else c.ink)
    }
}

// ───────────────────────── 编辑药 ─────────────────────────

@Composable
fun ColumnScope.PlanEditor(vm: AppViewModel, initial: PlanEntity, isNew: Boolean, close: () -> Unit) {
    val c = T.c
    var p by remember { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }

    SheetHeader(if (isNew) "添加药品" else "编辑药品", close)
    PlanFields(p, showActive = !isNew, onChange = { p = it })
    Spacer(Modifier.height(24.dp))
    PrimaryButton("保存", Modifier.fillMaxWidth(), enabled = p.name.isNotBlank()) {
        vm.savePlan(p.copy(name = p.name.trim(), dose = p.dose.trim()))
        close()
    }
    if (!isNew) {
        Spacer(Modifier.height(10.dp))
        SoftButton("删除药品", Modifier.fillMaxWidth(), tint = c.clay) { confirmDelete = true }
    }
    if (confirmDelete) {
        ConfirmDialog("删除「${p.name}」？", "提醒会停止，已有的记录保留。", "删除", onConfirm = {
            confirmDelete = false
            vm.deletePlan(p.uid)
            close()
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
fun PlanFields(p: PlanEntity, showActive: Boolean = true, onChange: (PlanEntity) -> Unit) {
    val c = T.c
    var picking by remember { mutableStateOf(false) }
    Field(p.name, { onChange(p.copy(name = it.take(20))) }, "例如：早上的药", label = "名称")
    Spacer(Modifier.height(16.dp))
    Field(p.dose, { onChange(p.copy(dose = it.take(20))) }, "例如：1 片（选填）", label = "剂量")
    Spacer(Modifier.height(16.dp))
    ValueRow("提醒时间", Copy.hm(p.hour, p.minute), picking) { picking = !picking }
    if (picking) {
        Spacer(Modifier.height(8.dp))
        WheelTimePicker(p.hour, p.minute) { h, m -> onChange(p.copy(hour = h, minute = m)) }
    }
    Spacer(Modifier.height(16.dp))
    FieldLabel("重复")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Repeat.DAILY to "每天", Repeat.WEEKDAYS to "工作日", Repeat.WEEKENDS to "周末", Repeat.CUSTOM to "指定星期", Repeat.INTERVAL to "隔天")
            .forEach { (r, label) -> Chip(label, p.repeatKind == r) { onChange(p.copy(repeat = r.name)) } }
    }
    when (p.repeatKind) {
        Repeat.WEEKDAYS -> Hint("周一至周五，不跳过法定节假日")
        Repeat.CUSTOM -> {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    val on = d in p.weekdaySet
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) c.sage else c.paper)
                            .press { onChange(p.copy(weekdays = (if (on) p.weekdaySet - d else p.weekdaySet + d).sorted().joinToString(","))) },
                        contentAlignment = Alignment.Center
                    ) { Text(Schedule.WEEK_SHORT[d - 1], style = T.body, color = if (on) c.onSage else c.ink2) }
                }
            }
        }
        Repeat.INTERVAL -> {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每", style = T.body, color = c.ink2)
                Stepper(p.intervalDays, 2..30) { onChange(p.copy(intervalDays = it)) }
                Text("天一次", style = T.body, color = c.ink2)
            }
        }
        else -> Unit
    }
    Spacer(Modifier.height(20.dp))
    SwitchRow("未打卡时再提醒", p.renag) { onChange(p.copy(renag = it)) }
    if (p.renag) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { m -> Chip("$m 分钟", p.renagMinutes == m) { onChange(p.copy(renagMinutes = m)) } }
        }
        Hint("最多 3 次")
    }
    if (showActive) {
        Spacer(Modifier.height(12.dp))
        SwitchRow("启用提醒", p.active) { onChange(p.copy(active = it)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.body, color = T.c.ink, modifier = Modifier.weight(1f))
        Toggle(checked, onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = T.tiny, color = T.c.ink3, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val c = T.c
    Row(Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp)).background(c.paper), verticalAlignment = Alignment.CenterVertically) {
        Text("−", style = T.title, color = c.ink2, modifier = Modifier.press { onChange((value - 1).coerceIn(range)) }.padding(horizontal = 16.dp, vertical = 6.dp))
        Text("$value", style = T.title.merge(T.digits), color = c.ink)
        Text("+", style = T.title, color = c.ink2, modifier = Modifier.press { onChange((value + 1).coerceIn(range)) }.padding(horizontal = 16.dp, vertical = 6.dp))
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

    SheetHeader(if (register) "注册" else "登录", close)
    Field(name, { name = it.take(32) }, "2–32 个字符", label = "用户名")
    Spacer(Modifier.height(14.dp))
    Field(pw, { pw = it }, "至少 6 位", label = "密码", password = true)
    if (register) {
        Spacer(Modifier.height(14.dp))
        Field(pw2, { pw2 = it }, "再输一次", label = "确认密码", password = true)
        Hint("数据加密后上传，忘记密码将无法恢复云端数据")
    }
    error?.let { Text(it, style = T.small, color = c.clay, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        when {
            busy -> "请稍候…"
            register -> "注册"
            else -> "登录"
        },
        Modifier.fillMaxWidth(),
        enabled = !busy && name.isNotBlank() && pw.isNotEmpty()
    ) {
        if (register && pw != pw2) {
            error = "两次密码不一致"
            return@PrimaryButton
        }
        busy = true
        error = null
        scope.launch {
            try {
                sync.signIn(name, pw, register)
                toasts.show(if (register) "已注册" else "已登录")
                onDone()
                close()
            } catch (e: Exception) {
                error = e.message ?: "失败，请重试"
            } finally {
                busy = false
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (register) "已有账号，去登录" else "没有账号，去注册",
        style = T.body, color = c.sage,
        modifier = Modifier.align(Alignment.CenterHorizontally).press { register = !register; error = null }.padding(10.dp)
    )
}

// ───────────────────────── 应用内更新 ─────────────────────────

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

@Composable
fun ColumnScope.UpdatePanel(ui: UpdateUi, updates: AppUpdate, close: () -> Unit) {
    val c = T.c
    val context = LocalContext.current
    SheetHeader("新版本 ${ui.offer.versionName}", close)
    if (ui.offer.notes.isNotBlank()) {
        Text(
            ui.offer.notes, style = T.body, color = c.ink,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.paper).padding(14.dp)
        )
    }
    if (ui.phase == UpdatePhase.Downloading) {
        Spacer(Modifier.height(14.dp))
        val fraction = if (ui.total > 0) (ui.received.toFloat() / ui.total).coerceIn(0f, 1f) else 0f
        val animated by animateFloatAsState(fraction, label = "update")
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.paper)) {
            Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(RoundedCornerShape(50)).background(c.sage))
        }
        Text(
            if (ui.total > 0) "${(fraction * 100).toInt()}% · ${mb(ui.received)} / ${mb(ui.total)}" else "连接中…",
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
        UpdatePhase.NeedsPermission -> "允许安装"
        UpdatePhase.Failed -> "重新下载"
    }
    if (ui.phase == UpdatePhase.Downloading) SoftButton(label, Modifier.fillMaxWidth()) { updates.onPrimary(context) }
    else PrimaryButton(label, Modifier.fillMaxWidth()) { updates.onPrimary(context) }
}
